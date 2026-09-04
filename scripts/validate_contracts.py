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
}
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
    return f"OpenAPI 3.0.3 with {len(actual)} operations"


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


def check_sensor_layout(document: dict[str, Any]) -> str:
    schema = dereference_schema(document["components"]["schemas"]["SensorLayoutResponse"], document)
    Draft7Validator.check_schema(schema)
    validator = Draft7Validator(schema)

    def layout(sensor_count: int, point_count: int) -> dict[str, Any]:
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
                }
                for index in range(point_count)
            ],
        }

    require(not list(validator.iter_errors(layout(6, 6))), "valid six-sensor layout was rejected")
    require(not list(validator.iter_errors(layout(8, 8))), "valid eight-sensor layout was rejected")
    require(bool(list(validator.iter_errors(layout(8, 7)))), "seven-point layout must be rejected")
    require(
        bool(list(validator.iter_errors(layout(6, 8)))),
        "sensorCount and points length mismatch must be rejected",
    )
    return "6/8 layouts accepted; seven-point and mismatched layouts rejected"


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
    return f"{len(paths)} frame batches conform; seven-sensor payload rejected"


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
    return "normal/asymmetry/duplicate/gap/stuck/disconnect/order/6-sensor intent confirmed"


def check_manifest(fixtures: Path, frame_paths: list[Path], realtime_paths: list[Path]) -> str:
    manifest = load_json(fixtures / "manifest.json")
    require(manifest.get("fixtureVersion") == "1.0", "manifest fixtureVersion must be 1.0")
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
