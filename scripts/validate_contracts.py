#!/usr/bin/env python3
"""Validate the Smart Insole OpenAPI, realtime schema, and fixtures."""

from __future__ import annotations

import argparse
import copy
import json
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable, Iterable

try:
    import yaml
    from jsonschema import Draft7Validator, Draft202012Validator, FormatChecker
except ImportError as error:  # pragma: no cover - exercised in dependency-free environments
    print(
        "[FAIL] Missing validator dependency. Install with: "
        "python -m pip install -r scripts/requirements-contracts.txt",
        file=sys.stderr,
    )
    print(f"       {error}", file=sys.stderr)
    raise SystemExit(2) from error


HTTP_METHODS = frozenset({"get", "put", "post", "delete", "options", "head", "patch", "trace"})
EXPECTED_ENUMS = {
    "FootSide": ["LEFT", "RIGHT"],
    "MeasurementStatus": [
        "CREATED",
        "MEASURING",
        "PROCESSING",
        "COMPLETED",
        "CANCELLED",
        "FAILED",
    ],
    "SourceType": ["DEVICE", "SIMULATED"],
    "QualityLevel": ["GOOD", "ACCEPTABLE", "POOR"],
    "PatternSeverity": ["INFO", "CAUTION", "RECHECK"],
    "ContactState": ["NO_CONTACT", "CONTACT", "UNKNOWN"],
    "ObservationLevel": ["NOT_OBSERVED", "PARTIALLY_OBSERVED", "REPEATEDLY_OBSERVED"],
    "DataMode": ["RAW", "FILTERED"],
    "ReceiverUploadState": ["STREAMING", "UPLOADING", "UPLOAD_COMPLETE"],
}
EXPECTED_OPENAPI_VERSION = "1.1.0"
EXPECTED_FIXTURE_VERSION = "1.1"
ADC_MAX = 4095
SEQUENCE_MAX = 4294967295
SAMPLE_RATES = [50, 100]
FRAME_SCHEMA_VERSIONS = ["1.0", "1.1"]
FRAME_1_1_FIELDS = (
    "protocolVersion",
    "receivedAt",
    "dataMode",
    "calibrated",
    "imuAvailable",
    "accelMg",
    "gyroDps10",
    "flags",
)
PATTERN_CODES = [
    "MEDIAL_LOAD_TENDENCY",
    "LATERAL_LOAD_TENDENCY",
    "LEFT_RIGHT_ASYMMETRY",
    "LOW_HALLUX_SIGNAL",
    "FOREFOOT_LOAD_TENDENCY",
    "REARFOOT_LOAD_TENDENCY",
]
REQUIRED_OPERATIONS = {
    ("post", "/api/v1/auth/signup"),
    ("post", "/api/v1/auth/signin"),
    ("get", "/api/v1/devices"),
    ("post", "/api/v1/devices"),
    ("get", "/api/v1/sensor-layouts/{version}"),
    ("post", "/api/v1/measurement-sessions"),
    ("get", "/api/v1/measurement-sessions"),
    ("get", "/api/v1/measurement-sessions/{sessionId}"),
    ("post", "/api/v1/measurement-sessions/{sessionId}/start"),
    ("post", "/api/v1/measurement-sessions/{sessionId}/complete"),
    ("post", "/api/v1/measurement-sessions/{sessionId}/cancel"),
    ("get", "/api/v1/measurement-sessions/{sessionId}/realtime-snapshot"),
    ("get", "/api/v1/measurement-sessions/{sessionId}/result"),
    ("get", "/api/v1/recommendations/{code}"),
    ("post", "/internal/v1/measurement-sessions/{sessionId}/frame-batches"),
    ("post", "/internal/v1/devices/{deviceId}/heartbeat"),
    ("get", "/internal/v1/measurement-sessions"),
    ("get", "/internal/v1/measurement-sessions/{sessionId}"),
    ("post", "/internal/v1/measurement-sessions/{sessionId}/receiver-status"),
}
SENSITIVE_KEYS = frozenset(
    {"password", "accessToken", "refreshToken", "receiverKey", "receiverApiKey", "apiKey", "jwtSecret"}
)


class ContractError(AssertionError):
    pass


@dataclass(frozen=True)
class Check:
    name: str
    run: Callable[[], str]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ContractError(message)


def load_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def resolve_pointer(document: Any, reference: str) -> Any:
    require(reference.startswith("#/"), f"non-local $ref is not allowed: {reference}")
    current = document
    for raw_part in reference[2:].split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        require(isinstance(current, dict) and part in current, f"broken local $ref: {reference}")
        current = current[part]
    return current


def all_references(node: Any) -> Iterable[str]:
    if isinstance(node, dict):
        for key, value in node.items():
            if key == "$ref" and isinstance(value, str):
                yield value
            else:
                yield from all_references(value)
    elif isinstance(node, list):
        for value in node:
            yield from all_references(value)


def dereference_schema(schema: Any, document: dict[str, Any], stack: tuple[str, ...] = ()) -> Any:
    if isinstance(schema, list):
        return [dereference_schema(item, document, stack) for item in schema]
    if not isinstance(schema, dict):
        return copy.deepcopy(schema)

    if "$ref" in schema:
        reference = schema["$ref"]
        require(reference not in stack, f"cyclic schema $ref is unsupported by fixture validator: {reference}")
        resolved = dereference_schema(resolve_pointer(document, reference), document, stack + (reference,))
        siblings = {key: value for key, value in schema.items() if key != "$ref"}
        if siblings:
            resolved = {"allOf": [resolved, dereference_schema(siblings, document, stack)]}
        return resolved

    converted = {
        key: dereference_schema(value, document, stack)
        for key, value in schema.items()
        if key != "nullable"
    }
    if schema.get("nullable") is True:
        converted = {"anyOf": [converted, {"type": "null"}]}
    return converted


def format_validation_errors(validator: Any, instance: Any) -> str:
    errors = sorted(validator.iter_errors(instance), key=lambda item: list(item.absolute_path))
    if not errors:
        return ""
    rendered: list[str] = []
    for error in errors[:10]:
        location = "/".join(str(part) for part in error.absolute_path) or "<root>"
        rendered.append(f"{location}: {error.message}")
    if len(errors) > 10:
        rendered.append(f"... and {len(errors) - 10} more")
    return "; ".join(rendered)


def walk_sensitive_keys(node: Any, path: str = "$") -> list[str]:
    findings: list[str] = []
    if isinstance(node, dict):
        for key, value in node.items():
            child = f"{path}.{key}"
            if key in SENSITIVE_KEYS:
                findings.append(child)
            findings.extend(walk_sensitive_keys(value, child))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            findings.extend(walk_sensitive_keys(value, f"{path}[{index}]"))
    return findings


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--root",
        type=Path,
        default=Path(__file__).resolve().parents[1],
        help="repository root (defaults to the parent of scripts/)",
    )
    parser.add_argument("--json", action="store_true", dest="json_output", help="emit a JSON report")
    args = parser.parse_args()
    root = args.root.resolve()
    contracts = root / "contracts"
    fixtures = root / "fixtures"

    try:
        with (contracts / "openapi.yaml").open("r", encoding="utf-8") as handle:
            openapi = yaml.safe_load(handle)
        realtime_schema = load_json(contracts / "realtime-message.schema.json")
    except (OSError, json.JSONDecodeError, yaml.YAMLError) as error:
        print(f"[FAIL] Unable to load contracts: {error}", file=sys.stderr)
        return 1

    fixture_paths = sorted(fixtures.glob("frame-batch-*.json"))
    realtime_paths = sorted(fixtures.glob("realtime-*.json"))
    parsed_fixtures: dict[str, Any] = {}
    for path in [*fixture_paths, *realtime_paths]:
        try:
            parsed_fixtures[path.name] = load_json(path)
        except (OSError, json.JSONDecodeError) as error:
            print(f"[FAIL] Unable to load {path.relative_to(root)}: {error}", file=sys.stderr)
            return 1

    checks = [
        Check("OpenAPI parse and required operations", lambda: check_openapi(openapi)),
        Check("OpenAPI local references", lambda: check_references(openapi)),
        Check("OpenAPI operationId uniqueness", lambda: check_operation_ids(openapi)),
        Check("Common enum values", lambda: check_common_enums(openapi, realtime_schema)),
        Check("Password byte policy", lambda: check_password_policy(openapi)),
        Check("Request non-blank parity", lambda: check_non_blank_policy(openapi)),
        Check("Heartbeat receiver identity policy", lambda: check_heartbeat_policy(openapi)),
        Check("Contract 1.1 field policy", lambda: check_contract_1_1(openapi)),
        Check("Sensor layout cardinality", lambda: check_sensor_layout(openapi)),
        Check(
            "Realtime JSON Schema and examples",
            lambda: check_realtime(realtime_schema, realtime_paths, parsed_fixtures),
        ),
        Check(
            "FrameBatch fixtures against OpenAPI",
            lambda: check_frame_fixtures(openapi, fixture_paths, parsed_fixtures),
        ),
        Check(
            "Fixture scenario semantics",
            lambda: check_fixture_semantics(parsed_fixtures),
        ),
        Check(
            "Fixture manifest coverage",
            lambda: check_manifest(fixtures, fixture_paths, realtime_paths),
        ),
        Check("Fixture secret hygiene", lambda: check_fixture_secrets(parsed_fixtures)),
    ]

    results: list[dict[str, str]] = []
    failures = 0
    for check in checks:
        try:
            detail = check.run()
            results.append({"status": "PASS", "check": check.name, "detail": detail})
        except (ContractError, KeyError, TypeError, ValueError) as error:
            failures += 1
            results.append({"status": "FAIL", "check": check.name, "detail": str(error)})

    if args.json_output:
        print(
            json.dumps(
                {"root": str(root), "passed": len(checks) - failures, "failed": failures, "checks": results},
                ensure_ascii=False,
                indent=2,
            )
        )
    else:
        for result in results:
            print(f"[{result['status']}] {result['check']}: {result['detail']}")
        print(f"Contract validation: {len(checks) - failures} passed, {failures} failed")
    return 1 if failures else 0


def check_openapi(document: dict[str, Any]) -> str:
    require(isinstance(document, dict), "OpenAPI document must be an object")
    require(document.get("openapi") == "3.0.3", "expected openapi: 3.0.3")
    info_version = document.get("info", {}).get("version")
    require(
        info_version == EXPECTED_OPENAPI_VERSION,
        f"expected info.version {EXPECTED_OPENAPI_VERSION}, found {info_version}",
    )
    paths = document.get("paths")
    require(isinstance(paths, dict), "paths must be an object")
    actual = {
        (method, path)
        for path, path_item in paths.items()
        if isinstance(path_item, dict)
        for method in path_item
        if method in HTTP_METHODS
    }
    missing = sorted(REQUIRED_OPERATIONS - actual)
    require(not missing, f"missing required operations: {missing}")
    return f"OpenAPI 3.0.3 ({EXPECTED_OPENAPI_VERSION}) with {len(actual)} operations"


def check_references(document: dict[str, Any]) -> str:
    references = list(all_references(document))
    for reference in references:
        resolve_pointer(document, reference)
    return f"{len(references)} local references resolved"


def check_operation_ids(document: dict[str, Any]) -> str:
    operation_ids: list[str] = []
    for path, path_item in document["paths"].items():
        if not isinstance(path_item, dict):
            continue
        for method, operation in path_item.items():
            if method not in HTTP_METHODS:
                continue
            require(isinstance(operation, dict), f"{method.upper()} {path} must be an object")
            operation_id = operation.get("operationId")
            require(isinstance(operation_id, str) and operation_id, f"{method.upper()} {path} lacks operationId")
            operation_ids.append(operation_id)
    duplicates = sorted({item for item in operation_ids if operation_ids.count(item) > 1})
    require(not duplicates, f"duplicate operationId values: {duplicates}")
    return f"{len(operation_ids)} unique operationId values"


def check_common_enums(document: dict[str, Any], realtime_schema: dict[str, Any]) -> str:
    schemas = document["components"]["schemas"]
    for name, expected in EXPECTED_ENUMS.items():
        actual = schemas.get(name, {}).get("enum")
        require(actual == expected, f"{name} must be {expected}, found {actual}")

    realtime_quality = realtime_schema["$defs"]["quality"]["properties"]["level"]["enum"]
    realtime_contact = (
        realtime_schema["$defs"]["foot"]["oneOf"][1]["properties"]["contactState"]["enum"]
    )
    require(realtime_quality == EXPECTED_ENUMS["QualityLevel"], "realtime QualityLevel differs from OpenAPI")
    require(realtime_contact == EXPECTED_ENUMS["ContactState"], "realtime ContactState differs from OpenAPI")
    return f"{len(EXPECTED_ENUMS)} enums match expected values"


def check_password_policy(document: dict[str, Any]) -> str:
    schemas = document["components"]["schemas"]
    for name in ("SignupRequest", "SigninRequest"):
        password = schemas[name]["properties"]["password"]
        require(password.get("x-maxUtf8Bytes") == 72, f"{name} must declare x-maxUtf8Bytes: 72")
        require(
            "UTF-8" in password.get("description", "") and "72" in password.get("description", ""),
            f"{name} must document the BCrypt UTF-8 byte limit",
        )
    return "signup/signin declare the 72-byte UTF-8 BCrypt limit"


def check_non_blank_policy(document: dict[str, Any]) -> str:
    schemas = document["components"]["schemas"]
    fields = {
        "SignupRequest": ("email", "password", "name"),
        "SigninRequest": ("email", "password"),
        "RegisterDeviceRequest": (
            "serialNumber",
            "displayName",
            "sensorLayoutVersion",
            "firmwareVersion",
        ),
        "FrameBatchRequest": ("receiverId",),
        "DeviceHeartbeatRequest": ("receiverId",),
    }
    for schema_name, names in fields.items():
        for field_name in names:
            field = schemas[schema_name]["properties"][field_name]
            require(
                field.get("pattern") == ".*\\S.*",
                f"{schema_name}.{field_name} must declare a non-whitespace pattern",
            )
    return f"{sum(len(names) for names in fields.values())} @NotBlank fields reject whitespace-only values"


def check_heartbeat_policy(document: dict[str, Any]) -> str:
    schema = dereference_schema(document["components"]["schemas"]["DeviceHeartbeatRequest"], document)
    Draft7Validator.check_schema(schema)
    validator = Draft7Validator(schema, format_checker=FormatChecker())

    def heartbeat(receiver_id: str) -> dict[str, Any]:
        return {
            "receiverId": receiver_id,
            "observedAt": "2026-09-02T07:11:10.500Z",
            "connected": True,
        }

    require(not list(validator.iter_errors(heartbeat("RECEIVER-PC-001"))), "valid receiverId was rejected")
    require(bool(list(validator.iter_errors(heartbeat("")))), "empty receiverId must be rejected")
    require(bool(list(validator.iter_errors(heartbeat("   ")))), "blank receiverId must be rejected")
    return "non-empty receiverId accepted; empty and blank values rejected"


def check_contract_1_1(document: dict[str, Any]) -> str:
    schemas = document["components"]["schemas"]
    frame = schemas["PressureFrameInput"]["properties"]
    require(
        frame["sensorValues"]["items"].get("maximum") == ADC_MAX,
        f"PressureFrameInput.sensorValues.items.maximum must be {ADC_MAX}",
    )
    require(
        frame["sequence"].get("maximum") == SEQUENCE_MAX,
        f"PressureFrameInput.sequence.maximum must be {SEQUENCE_MAX}",
    )
    missing_fields = [name for name in FRAME_1_1_FIELDS if name not in frame]
    require(not missing_fields, f"PressureFrameInput lacks 1.1 optional fields: {missing_fields}")
    required = set(schemas["PressureFrameInput"].get("required", []))
    leaked = sorted(required.intersection(FRAME_1_1_FIELDS))
    require(not leaked, f"1.1 frame fields must stay optional: {leaked}")
    require(
        schemas["FrameBatchRequest"]["properties"]["schemaVersion"].get("enum") == FRAME_SCHEMA_VERSIONS,
        f"FrameBatchRequest.schemaVersion enum must be {FRAME_SCHEMA_VERSIONS}",
    )
    require("batchId" in schemas["FrameBatchRequest"]["properties"], "FrameBatchRequest lacks batchId")
    require(
        schemas["FrameBatchRequest"].get("additionalProperties") is False
        and schemas["PressureFrameInput"].get("additionalProperties") is False,
        "FrameBatchRequest and PressureFrameInput must keep additionalProperties: false",
    )

    session = schemas["CreateMeasurementSessionRequest"]["properties"]
    require(
        session["sampleRateHz"].get("enum") == SAMPLE_RATES,
        f"CreateMeasurementSessionRequest.sampleRateHz enum must be {SAMPLE_RATES}",
    )
    require(
        session.get("sourceType", {}).get("default") == "DEVICE",
        "CreateMeasurementSessionRequest.sourceType default must be DEVICE",
    )
    require(
        "sourceType" not in schemas["CreateMeasurementSessionRequest"].get("required", []),
        "sourceType must be optional (default DEVICE)",
    )

    label = schemas["SensorPoint"]["properties"].get("label")
    require(isinstance(label, dict) and label.get("nullable") is True, "SensorPoint.label must be nullable")
    require("label" not in schemas["SensorPoint"].get("required", []), "SensorPoint.label must be optional")

    device_request = schemas["RegisterDeviceRequest"]["properties"]
    require(
        device_request.get("adcMax", {}).get("enum") == [ADC_MAX]
        and device_request["adcMax"].get("default") == ADC_MAX,
        f"RegisterDeviceRequest.adcMax must allow only {ADC_MAX}",
    )
    require("adcMax" in schemas["DeviceResponse"]["properties"], "DeviceResponse lacks adcMax")

    heartbeat = schemas["DeviceHeartbeatRequest"]["properties"]
    require(
        "batteryMv" in heartbeat and "firmwareVersion" in heartbeat,
        "DeviceHeartbeatRequest lacks batteryMv/firmwareVersion",
    )

    receiver_session = schemas["ReceiverSessionResponse"]["properties"]
    for name in ("sessionId", "status", "sampleRateHz", "sourceType", "startedAt", "endedAt", "left", "right",
                 "receiverState", "receiverPendingBatches"):
        require(name in receiver_session, f"ReceiverSessionResponse lacks {name}")
    device_fields = schemas["ReceiverSessionDevice"]["properties"]
    for name in ("deviceId", "serialNumber", "footSide", "sensorCount", "sensorLayoutVersion", "adcMax",
                 "firmwareVersion"):
        require(name in device_fields, f"ReceiverSessionDevice lacks {name}")
    receiver_status = schemas["ReceiverStatusRequest"]["properties"]
    for name in ("receiverId", "state", "pendingBatchCount", "observedAt"):
        require(name in receiver_status, f"ReceiverStatusRequest lacks {name}")

    pattern = schemas["PatternResult"]["properties"]
    for name in ("observationLevel", "occurrenceRate", "observedCount", "windowCount"):
        require(name in pattern, f"PatternResult lacks {name}")
    summary_codes = schemas["ObservationSummaryItem"]["properties"]["code"].get("enum")
    require(summary_codes == PATTERN_CODES, f"ObservationSummaryItem.code enum must be {PATTERN_CODES}")
    result = schemas["AnalysisResultResponse"]["properties"]
    require(
        result.get("observationSummary", {}).get("nullable") is True,
        "AnalysisResultResponse.observationSummary must be nullable for legacy results",
    )
    distribution = schemas["PressureDistribution"]["properties"]
    require(
        "leftSensorSharePct" in distribution and "rightSensorSharePct" in distribution,
        "PressureDistribution lacks sensor share fields",
    )

    schema = dereference_schema(schemas["FrameBatchRequest"], document)
    validator = Draft7Validator(schema, format_checker=FormatChecker())

    def batch(schema_version: str, frame_overrides: dict[str, Any]) -> dict[str, Any]:
        values = {
            "deviceId": "b4b96290-ad73-42d9-ae21-1446f1258861",
            "footSide": "LEFT",
            "sequence": 1,
            "deviceTimeMs": 20,
            "sensorValues": [0, 0, 0, 0, 0, 0, 0, 0],
        }
        values.update(frame_overrides)
        return {
            "schemaVersion": schema_version,
            "receiverId": "GATEWAY-DEV-001",
            "sentAt": "2026-09-04T01:02:03.456789Z",
            "frames": [values],
        }

    require(not list(validator.iter_errors(batch("1.0", {}))), "minimal 1.0 batch was rejected")
    require(
        not list(validator.iter_errors(batch("1.1", {
            "protocolVersion": 1,
            "receivedAt": "2026-09-04T01:02:03.401234Z",
            "dataMode": "RAW",
            "calibrated": False,
            "imuAvailable": True,
            "accelMg": [10, -20, 995],
            "gyroDps10": [3, -4, 5],
        }))),
        "full 1.1 frame was rejected",
    )
    require(
        bool(list(validator.iter_errors(batch("1.1", {"sensorValues": [ADC_MAX + 1] * 8})))),
        f"sensor value {ADC_MAX + 1} must be rejected",
    )
    require(
        bool(list(validator.iter_errors(batch("1.1", {"sequence": SEQUENCE_MAX + 1})))),
        "sequence above u32 must be rejected",
    )
    require(
        bool(list(validator.iter_errors(batch("1.1", {"accelMg": [1, 2]})))),
        "two-axis IMU vector must be rejected",
    )
    require(
        bool(list(validator.iter_errors(batch("1.1", {"flags": 256})))),
        "flags above 255 must be rejected",
    )
    require(
        bool(list(validator.iter_errors(batch("1.1", {"dataMode": "SMOOTHED"})))),
        "unknown dataMode must be rejected",
    )
    require(
        bool(list(validator.iter_errors(batch("1.2", {})))),
        "schemaVersion 1.2 must be rejected",
    )
    return (
        f"ADC max {ADC_MAX}, u32 sequence, sampleRateHz {SAMPLE_RATES}, sourceType default DEVICE, "
        "1.1 optional frame fields, receiver session/status schemas, observation fields"
    )


def check_sensor_layout(document: dict[str, Any]) -> str:
    schema = dereference_schema(document["components"]["schemas"]["SensorLayoutResponse"], document)
    Draft7Validator.check_schema(schema)
    validator = Draft7Validator(schema)

    def layout(sensor_count: int, point_count: int, *, labelled: bool = False) -> dict[str, Any]:
        return {
            "version": f"layout-{sensor_count}",
            "sensorCount": sensor_count,
            "points": [
                {
                    "index": index,
                    "x": 0.5,
                    "y": index / max(1, point_count - 1),
                    "region": "MIDFOOT",
                    "medialLateral": "CENTER",
                    **({"label": f"S{index + 1:02d}"} if labelled else {}),
                }
                for index in range(point_count)
            ],
        }

    require(not list(validator.iter_errors(layout(6, 6))), "valid six-sensor layout was rejected")
    require(not list(validator.iter_errors(layout(8, 8))), "valid eight-sensor layout was rejected")
    require(not list(validator.iter_errors(layout(8, 8, labelled=True))), "labelled S01..S08 layout was rejected")
    require(bool(list(validator.iter_errors(layout(8, 7)))), "seven-point layout must be rejected")
    require(
        bool(list(validator.iter_errors(layout(6, 8)))),
        "sensorCount and points length mismatch must be rejected",
    )
    return "6/8 layouts accepted (with or without label); seven-point and mismatched layouts rejected"


def check_realtime(
    schema: dict[str, Any], paths: list[Path], parsed: dict[str, Any]
) -> str:
    Draft202012Validator.check_schema(schema)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    require(paths, "no realtime fixture files found")
    for path in paths:
        errors = format_validation_errors(validator, parsed[path.name])
        require(not errors, f"{path.name}: {errors}")
    seven_sensor = copy.deepcopy(parsed[paths[0].name])
    foot_key = next((key for key in ("left", "right") if seven_sensor.get(key) is not None), None)
    require(foot_key is not None, "realtime examples contain no connected foot")
    seven_sensor[foot_key]["sensorValues"] = [0] * 7
    require(
        bool(list(validator.iter_errors(seven_sensor))),
        "realtime schema must reject seven-sensor arrays",
    )
    return f"schema valid; {len(paths)} examples valid; seven-sensor payload rejected"


def check_frame_fixtures(
    openapi: dict[str, Any], paths: list[Path], parsed: dict[str, Any]
) -> str:
    require(paths, "no frame batch fixture files found")
    schema = dereference_schema(openapi["components"]["schemas"]["FrameBatchRequest"], openapi)
    Draft7Validator.check_schema(schema)
    validator = Draft7Validator(schema, format_checker=FormatChecker())
    for path in paths:
        errors = format_validation_errors(validator, parsed[path.name])
        require(not errors, f"{path.name}: {errors}")
    seven_sensor = copy.deepcopy(parsed[paths[0].name])
    seven_sensor["frames"][0]["sensorValues"] = [0] * 7
    require(
        bool(list(validator.iter_errors(seven_sensor))),
        "OpenAPI must reject seven-sensor frame arrays",
    )
    saturated = copy.deepcopy(parsed[paths[0].name])
    saturated["frames"][0]["sensorValues"] = [ADC_MAX + 1] * len(saturated["frames"][0]["sensorValues"])
    require(
        bool(list(validator.iter_errors(saturated))),
        f"OpenAPI must reject sensor values above {ADC_MAX}",
    )
    for path in paths:
        for index, frame in enumerate(parsed[path.name]["frames"]):
            require(
                max(frame["sensorValues"]) <= ADC_MAX,
                f"{path.name} frame {index} exceeds the {ADC_MAX} ADC scale",
            )
    return f"{len(paths)} frame batches conform on the {ADC_MAX} scale; seven-sensor and {ADC_MAX + 1} payloads rejected"


def check_fixture_semantics(parsed: dict[str, Any]) -> str:
    required = {
        "frame-batch-normal.json",
        "frame-batch-left-asymmetry.json",
        "frame-batch-duplicate.json",
        "frame-batch-sequence-gap.json",
        "frame-batch-sensor-stuck.json",
        "frame-batch-right-disconnected.json",
        "frame-batch-out-of-order.json",
        "frame-batch-six-sensor.json",
        "frame-batch-device-v1_1.json",
    }
    missing = sorted(required - parsed.keys())
    require(not missing, f"missing required scenario fixtures: {missing}")

    def frames(name: str) -> list[dict[str, Any]]:
        value = parsed[name].get("frames")
        require(isinstance(value, list), f"{name} frames must be an array")
        return value

    normal = frames("frame-batch-normal.json")
    require({frame["footSide"] for frame in normal} == {"LEFT", "RIGHT"}, "normal must contain both feet")

    asymmetric = frames("frame-batch-left-asymmetry.json")
    totals = {
        side: sum(sum(frame["sensorValues"]) for frame in asymmetric if frame["footSide"] == side)
        for side in ("LEFT", "RIGHT")
    }
    require(totals["LEFT"] > totals["RIGHT"], "left-asymmetry must have a larger LEFT total")

    duplicate = frames("frame-batch-duplicate.json")
    duplicate_keys = [(frame["deviceId"], frame["sequence"]) for frame in duplicate]
    require(len(set(duplicate_keys)) < len(duplicate_keys), "duplicate fixture lacks a duplicate key")

    gap = frames("frame-batch-sequence-gap.json")
    gap_found = False
    for device_id in {frame["deviceId"] for frame in gap}:
        sequences = sorted({frame["sequence"] for frame in gap if frame["deviceId"] == device_id})
        gap_found = gap_found or any(right - left > 1 for left, right in zip(sequences, sequences[1:]))
    require(gap_found, "sequence-gap fixture lacks a gap")

    stuck = [frame for frame in frames("frame-batch-sensor-stuck.json") if frame["footSide"] == "LEFT"]
    require(stuck, "sensor-stuck fixture lacks LEFT frames")
    constant_sensor = any(
        len({frame["sensorValues"][index] for frame in stuck}) == 1
        for index in range(len(stuck[0]["sensorValues"]))
    )
    require(constant_sensor, "sensor-stuck fixture lacks a constant LEFT sensor")

    disconnected = frames("frame-batch-right-disconnected.json")
    require({frame["footSide"] for frame in disconnected} == {"LEFT"}, "right-disconnected must be LEFT-only")

    out_of_order = frames("frame-batch-out-of-order.json")
    unordered = False
    for device_id in {frame["deviceId"] for frame in out_of_order}:
        sequences = [frame["sequence"] for frame in out_of_order if frame["deviceId"] == device_id]
        unordered = unordered or sequences != sorted(sequences)
    require(unordered, "out-of-order fixture is already ordered")

    six_sensor = frames("frame-batch-six-sensor.json")
    require(
        six_sensor and all(len(frame["sensorValues"]) == 6 for frame in six_sensor),
        "six-sensor fixture must contain only six-value frames",
    )

    legacy = [name for name in parsed if name.startswith("frame-batch-") and name != "frame-batch-device-v1_1.json"]
    for name in legacy:
        require(parsed[name].get("schemaVersion") == "1.0", f"{name} must stay a schemaVersion 1.0 batch")
        for index, frame in enumerate(frames(name)):
            leaked = sorted(set(frame).intersection(FRAME_1_1_FIELDS))
            require(not leaked, f"{name} frame {index} carries 1.1-only fields {leaked} in a 1.0 batch")

    device = parsed["frame-batch-device-v1_1.json"]
    require(device.get("schemaVersion") == "1.1", "device fixture must declare schemaVersion 1.1")
    require(isinstance(device.get("batchId"), str) and device["batchId"], "device fixture must carry batchId")
    device_frames = frames("frame-batch-device-v1_1.json")
    require({frame["footSide"] for frame in device_frames} == {"LEFT", "RIGHT"}, "device fixture must contain both feet")
    for index, frame in enumerate(device_frames):
        for name in ("protocolVersion", "receivedAt", "dataMode", "calibrated", "imuAvailable"):
            require(name in frame, f"device fixture frame {index} lacks {name}")
        require(frame["dataMode"] == "RAW" and frame["calibrated"] is False, f"device fixture frame {index} must be RAW/uncalibrated")
        require(frame["protocolVersion"] == 1 and "flags" not in frame, f"device fixture frame {index}: flags are v2-only")
        has_vectors = "accelMg" in frame or "gyroDps10" in frame
        require(
            has_vectors == bool(frame["imuAvailable"]),
            f"device fixture frame {index} must omit IMU vectors exactly when imuAvailable is false",
        )
    require(
        any(not frame["imuAvailable"] for frame in device_frames),
        "device fixture must include at least one frame with imuAvailable=false and omitted vectors",
    )
    return "normal/asymmetry/duplicate/gap/stuck/disconnect/order/6-sensor/1.1-device intent confirmed"


def check_manifest(fixtures: Path, frame_paths: list[Path], realtime_paths: list[Path]) -> str:
    manifest = load_json(fixtures / "manifest.json")
    require(
        manifest.get("fixtureVersion") == EXPECTED_FIXTURE_VERSION,
        f"manifest fixtureVersion must be {EXPECTED_FIXTURE_VERSION}",
    )
    require(manifest.get("adcMax") == ADC_MAX, f"manifest adcMax must be {ADC_MAX}")
    cases = manifest.get("cases")
    require(isinstance(cases, list), "manifest cases must be an array")
    names = [case.get("file") for case in cases if isinstance(case, dict)]
    require(len(names) == len(set(names)), "manifest contains duplicate file entries")
    expected_names = {path.name for path in [*frame_paths, *realtime_paths]}
    require(set(names) == expected_names, f"manifest coverage differs: expected {sorted(expected_names)}, found {sorted(names)}")
    for case in cases:
        require(isinstance(case.get("purpose"), str) and case["purpose"], f"missing purpose for {case.get('file')}")
        require(isinstance(case.get("expected"), list) and case["expected"], f"missing expected list for {case.get('file')}")
        require((fixtures / case["file"]).is_file(), f"manifest target missing: {case['file']}")
    return f"{len(cases)} manifest cases exactly cover fixture files"


def check_fixture_secrets(parsed: dict[str, Any]) -> str:
    findings: list[str] = []
    for filename, value in parsed.items():
        findings.extend(f"{filename}:{path}" for path in walk_sensitive_keys(value))
    require(not findings, f"sensitive fields found: {findings}")
    return f"{len(parsed)} fixtures contain no credential fields"


if __name__ == "__main__":
    raise SystemExit(main())
