#!/usr/bin/env python3
"""Replay a FrameBatch fixture to the Receiver API as SIMULATED data.

The replay speaks Frame Batch schemaVersion 1.1 by default (batchId, per-frame receivedAt,
protocolVersion, dataMode RAW, calibrated false, imuAvailable false unless the fixture carries IMU
vectors). Before sending it looks the session up through GET /internal/v1/measurement-sessions/{id}
so that the device ids, the sensor counts, the session sampleRateHz and the sourceType are checked:
this tool produces SIMULATED data and refuses a DEVICE session unless --allow-device-session is given.
"""

from __future__ import annotations

import argparse
import copy
import json
import os
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

SCRIPTS_DIR = Path(__file__).resolve().parents[1]
if str(SCRIPTS_DIR) not in sys.path:
    sys.path.insert(0, str(SCRIPTS_DIR))

from http_tools import describe_error, join_url, load_json, request_json  # noqa: E402

ADC_MAX = 4095
SCHEMA_1_1_FIELDS = (
    "protocolVersion",
    "receivedAt",
    "dataMode",
    "calibrated",
    "imuAvailable",
    "accelMg",
    "gyroDps10",
    "flags",
)


def bounded_integer(minimum: int, maximum: int):
    def parse(value: str) -> int:
        parsed = int(value)
        if not minimum <= parsed <= maximum:
            raise argparse.ArgumentTypeError(f"must be between {minimum} and {maximum}")
        return parsed

    return parse


def positive_integer(value: str) -> int:
    parsed = int(value)
    if parsed < 1:
        raise argparse.ArgumentTypeError("must be at least 1")
    return parsed


def uuid_value(value: str) -> str:
    try:
        return str(uuid.UUID(value))
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a UUID") from error


def utc_now(*, micro: bool = False) -> str:
    stamp = datetime.now(timezone.utc).isoformat(timespec="microseconds" if micro else "milliseconds")
    return stamp.replace("+00:00", "Z")


def parse_args() -> argparse.Namespace:
    repository_root = SCRIPTS_DIR.parent
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument(
        "--fixture",
        type=Path,
        default=repository_root / "fixtures" / "frame-batch-normal.json",
        help="FrameBatch fixture (default: fixtures/frame-batch-normal.json)",
    )
    parser.add_argument(
        "--base-url",
        default=os.getenv("SMART_INSOLE_BASE_URL", "http://localhost:8080"),
        help="backend base URL (or SMART_INSOLE_BASE_URL)",
    )
    parser.add_argument(
        "--session-id",
        type=uuid_value,
        default=os.getenv("SMART_INSOLE_SESSION_ID"),
        help="MEASURING session UUID (or SMART_INSOLE_SESSION_ID)",
    )
    parser.add_argument(
        "--left-device-id",
        type=uuid_value,
        default=os.getenv("SMART_INSOLE_LEFT_DEVICE_ID"),
        help="replacement LEFT device UUID (or SMART_INSOLE_LEFT_DEVICE_ID); defaults to the session's device",
    )
    parser.add_argument(
        "--right-device-id",
        type=uuid_value,
        default=os.getenv("SMART_INSOLE_RIGHT_DEVICE_ID"),
        help="replacement RIGHT device UUID (or SMART_INSOLE_RIGHT_DEVICE_ID); defaults to the session's device",
    )
    parser.add_argument(
        "--receiver-key",
        default=os.getenv("SMART_INSOLE_RECEIVER_KEY"),
        help="Receiver credential (or SMART_INSOLE_RECEIVER_KEY); never printed",
    )
    parser.add_argument("--receiver-id", help="optional receiverId override")
    parser.add_argument("--schema-version", choices=["1.0", "1.1"], default="1.1")
    parser.add_argument("--protocol-version", type=positive_integer, default=1,
                        help="protocolVersion stamped on 1.1 frames without one (1 = Frame A/B wire)")
    parser.add_argument("--batch-ms", type=bounded_integer(100, 200), default=100)
    parser.add_argument(
        "--sample-rate-hz",
        type=int,
        choices=[50, 100],
        default=None,
        help="frames per second per foot; defaults to the session sampleRateHz (100 in --dry-run)",
    )
    parser.add_argument("--repeat", type=positive_integer, default=1)
    parser.add_argument("--max-attempts", type=positive_integer, default=3)
    parser.add_argument("--retry-backoff-ms", type=positive_integer, default=250)
    parser.add_argument("--timeout-seconds", type=float, default=15.0)
    parser.add_argument("--no-wait", action="store_true", help="send batches without real-time pacing")
    parser.add_argument(
        "--allow-rejected",
        action="store_true",
        help="allow rejected frames for an intentional negative-test fixture",
    )
    parser.add_argument(
        "--allow-device-session",
        action="store_true",
        help="send SIMULATED fixture data into a session whose sourceType is DEVICE (normally refused)",
    )
    parser.add_argument(
        "--skip-session-check",
        action="store_true",
        help="do not look the session up before sending (device ids must then be given explicitly)",
    )
    parser.add_argument("--dry-run", action="store_true", help="prepare batches without network requests")
    return parser.parse_args()


def require_fixture(value: Any, path: Path) -> dict[str, Any]:
    if not isinstance(value, dict) or not isinstance(value.get("frames"), list):
        raise ValueError(f"{path} is not a FrameBatch object")
    if not value["frames"]:
        raise ValueError(f"{path} has no frames")
    for index, frame in enumerate(value["frames"]):
        if not isinstance(frame, dict) or frame.get("footSide") not in {"LEFT", "RIGHT"}:
            raise ValueError(f"frame {index} lacks a valid footSide")
        if not isinstance(frame.get("sequence"), int) or not isinstance(frame.get("deviceTimeMs"), int):
            raise ValueError(f"frame {index} lacks integer sequence/deviceTimeMs")
        values = frame.get("sensorValues")
        if not isinstance(values, list) or len(values) not in {6, 8}:
            raise ValueError(f"frame {index} must contain 6 or 8 sensor values")
        if any(not isinstance(item, int) or not 0 <= item <= ADC_MAX for item in values):
            raise ValueError(f"frame {index} sensor values must be integers within 0..{ADC_MAX}")
    return value


def fixture_period_ms(frames: list[dict[str, Any]], side: str) -> int:
    """Smallest positive deviceTimeMs step of one side; the fixtures are authored at 10 ms (100 Hz)."""

    times = sorted({frame["deviceTimeMs"] for frame in frames if frame["footSide"] == side})
    deltas = [later - earlier for earlier, later in zip(times, times[1:]) if later > earlier]
    return min(deltas) if deltas else 10


def prepare_frames(
    fixture: dict[str, Any],
    *,
    left_device_id: str | None,
    right_device_id: str | None,
    repeats: int,
    sample_rate_hz: int,
) -> list[dict[str, Any]]:
    """Rewrite device ids, repeat with shifted sequences and rescale deviceTimeMs to the sample period."""

    source_frames: list[dict[str, Any]] = fixture["frames"]
    sides = {frame["footSide"] for frame in source_frames}
    if "LEFT" in sides and not left_device_id:
        raise ValueError("--left-device-id is required for a fixture containing LEFT frames")
    if "RIGHT" in sides and not right_device_id:
        raise ValueError("--right-device-id is required for a fixture containing RIGHT frames")

    sample_period_ms = max(1, round(1000 / sample_rate_hz))
    sequence_span: dict[str, int] = {}
    time_span: dict[str, int] = {}
    time_origin: dict[str, int] = {}
    time_scale: dict[str, float] = {}
    for side in sides:
        side_frames = [frame for frame in source_frames if frame["footSide"] == side]
        sequences = [frame["sequence"] for frame in side_frames]
        times = [frame["deviceTimeMs"] for frame in side_frames]
        sequence_span[side] = max(sequences) - min(sequences) + 1
        time_origin[side] = min(times)
        # A 10 ms fixture replayed into a 50 Hz session must advance 20 ms per frame or the backend
        # raises SAMPLE_RATE_MISMATCH on the median deviceTimeMs delta.
        time_scale[side] = sample_period_ms / fixture_period_ms(source_frames, side)
        time_span[side] = round((max(times) - min(times)) * time_scale[side]) + sample_period_ms

    replacement = {"LEFT": left_device_id, "RIGHT": right_device_id}
    prepared: list[dict[str, Any]] = []
    for repeat_index in range(repeats):
        for source in source_frames:
            frame = copy.deepcopy(source)
            side = frame["footSide"]
            frame["deviceId"] = replacement[side]
            frame["sequence"] += repeat_index * sequence_span[side]
            scaled = round((source["deviceTimeMs"] - time_origin[side]) * time_scale[side])
            frame["deviceTimeMs"] = time_origin[side] + scaled + repeat_index * time_span[side]
            prepared.append(frame)
    return prepared


def stamp_schema(frame: dict[str, Any], *, schema_version: str, protocol_version: int) -> dict[str, Any]:
    """Add the 1.1 metadata (or strip it for a 1.0 batch); keys with no value are omitted, never null."""

    stamped = dict(frame)
    if schema_version == "1.0":
        for key in SCHEMA_1_1_FIELDS:
            stamped.pop(key, None)
        return stamped
    stamped.setdefault("protocolVersion", protocol_version)
    stamped["receivedAt"] = utc_now(micro=True)
    stamped.setdefault("dataMode", "RAW")
    stamped.setdefault("calibrated", False)
    has_imu = "accelMg" in stamped and "gyroDps10" in stamped
    stamped.setdefault("imuAvailable", has_imu)
    if not stamped["imuAvailable"]:
        stamped.pop("accelMg", None)
        stamped.pop("gyroDps10", None)
    if stamped["protocolVersion"] < 2:
        stamped.pop("flags", None)
    return stamped


def chunks(frames: list[dict[str, Any]], batch_ms: int, sample_rate_hz: int) -> list[list[dict[str, Any]]]:
    side_count = len({frame["footSide"] for frame in frames})
    target = max(1, round(sample_rate_hz * batch_ms / 1000)) * side_count
    return [frames[index : index + target] for index in range(0, len(frames), target)]


def lookup_session(args: argparse.Namespace) -> dict[str, Any]:
    result = request_json(
        "GET",
        join_url(args.base_url, f"/internal/v1/measurement-sessions/{args.session_id}"),
        headers={"X-Receiver-Key": args.receiver_key},
        timeout_seconds=args.timeout_seconds,
        max_attempts=args.max_attempts,
        retry_backoff_seconds=args.retry_backoff_ms / 1000.0,
    )
    if result.status != 200 or not isinstance(result.body, dict):
        raise RuntimeError(f"session lookup failed: {describe_error(result)}")
    return result.body


def apply_session(args: argparse.Namespace, session: dict[str, Any], fixture: dict[str, Any]) -> None:
    """Fill defaults from the session and refuse mismatches before any frame is sent."""

    if session.get("status") != "MEASURING":
        raise RuntimeError(f"session status is {session.get('status')!r}; start it before replaying")
    source_type = session.get("sourceType")
    if source_type != "SIMULATED" and not args.allow_device_session:
        raise RuntimeError(
            f"session sourceType is {source_type!r}; this tool sends SIMULATED data. Create the session with "
            "sourceType SIMULATED or pass --allow-device-session for a deliberate test"
        )
    session_rate = session.get("sampleRateHz")
    if session_rate not in (50, 100):
        raise RuntimeError(f"session sampleRateHz {session_rate!r} is not 50 or 100")
    if args.sample_rate_hz is None:
        args.sample_rate_hz = session_rate
    elif args.sample_rate_hz != session_rate:
        print(f"[SIMULATED] warning: --sample-rate-hz {args.sample_rate_hz} differs from session {session_rate}")
    fixture_sides = {frame["footSide"] for frame in fixture["frames"]}
    for side, option in (("LEFT", "left_device_id"), ("RIGHT", "right_device_id")):
        device = session.get(side.lower())
        if not isinstance(device, dict) or not isinstance(device.get("deviceId"), str):
            raise RuntimeError(f"session lookup lacks the {side} device")
        given = getattr(args, option)
        if given is None:
            setattr(args, option, device["deviceId"])
        elif given != device["deviceId"]:
            raise RuntimeError(f"--{option.replace('_', '-')} {given} is not the session's {side} device")
        if side in fixture_sides:
            lengths = {len(frame["sensorValues"]) for frame in fixture["frames"] if frame["footSide"] == side}
            if lengths != {device.get("sensorCount")}:
                raise RuntimeError(
                    f"fixture {side} sensor count {sorted(lengths)} differs from the device ({device.get('sensorCount')})"
                )
    adc_max = session.get("adcMax")
    if isinstance(adc_max, int) and adc_max < ADC_MAX:
        raise RuntimeError(f"session adcMax {adc_max} is below the fixture scale {ADC_MAX}")


def main() -> int:
    args = parse_args()
    if not args.session_id:
        print("[FAIL] --session-id or SMART_INSOLE_SESSION_ID is required.", file=sys.stderr)
        return 2
    if not args.dry_run and not args.receiver_key:
        print("[FAIL] --receiver-key or SMART_INSOLE_RECEIVER_KEY is required.", file=sys.stderr)
        return 2
    if args.timeout_seconds <= 0:
        print("[FAIL] --timeout-seconds must be positive.", file=sys.stderr)
        return 2

    fixture_path = args.fixture.resolve()
    try:
        fixture = require_fixture(load_json(fixture_path), fixture_path)
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 2

    session: dict[str, Any] | None = None
    if not args.dry_run and not args.skip_session_check:
        try:
            session = lookup_session(args)
            apply_session(args, session, fixture)
        except RuntimeError as error:
            print(f"[FAIL] {error}", file=sys.stderr)
            return 1
        print(
            f"[SIMULATED] session status={session.get('status')} sourceType={session.get('sourceType')} "
            f"sampleRateHz={session.get('sampleRateHz')} adcMax={session.get('adcMax')}"
        )
    if args.sample_rate_hz is None:
        args.sample_rate_hz = 100

    try:
        frames = prepare_frames(
            fixture,
            left_device_id=args.left_device_id,
            right_device_id=args.right_device_id,
            repeats=args.repeat,
            sample_rate_hz=args.sample_rate_hz,
        )
    except ValueError as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 2

    prepared_batches = chunks(frames, args.batch_ms, args.sample_rate_hz)
    endpoint = join_url(
        args.base_url,
        f"/internal/v1/measurement-sessions/{args.session_id}/frame-batches",
    )
    print(
        f"[SIMULATED] fixture={fixture_path.name} schemaVersion={args.schema_version} frames={len(frames)} "
        f"batches={len(prepared_batches)} batchWindowMs={args.batch_ms} sampleRateHz={args.sample_rate_hz} "
        f"repeat={args.repeat}"
    )
    print(f"[SIMULATED] target={endpoint}; credentials are not displayed")

    if args.dry_run:
        sizes = [len(batch) for batch in prepared_batches]
        sample = stamp_schema(prepared_batches[0][0], schema_version=args.schema_version,
                              protocol_version=args.protocol_version)
        print(f"[SIMULATED] dry-run batchSizes={sizes} firstFrameKeys={sorted(sample)}")
        print("[PASS] No network requests were sent.")
        return 0

    totals = {"acceptedCount": 0, "duplicateCount": 0, "rejectedCount": 0}
    last_sequences: dict[str, int] = {}
    next_deadline = time.monotonic()
    receiver_id = args.receiver_id or fixture.get("receiverId", "SIMULATED-RECEIVER")
    for index, batch in enumerate(prepared_batches, start=1):
        if not args.no_wait:
            remaining = next_deadline - time.monotonic()
            if remaining > 0:
                time.sleep(remaining)
        payload: dict[str, Any] = {
            "schemaVersion": args.schema_version,
            "receiverId": receiver_id,
            "sentAt": utc_now(micro=True),
            "frames": [
                stamp_schema(frame, schema_version=args.schema_version, protocol_version=args.protocol_version)
                for frame in batch
            ],
        }
        if args.schema_version == "1.1":
            payload["batchId"] = f"{args.session_id}-{index:06d}"
        try:
            result = request_json(
                "POST",
                endpoint,
                payload=payload,
                headers={"X-Receiver-Key": args.receiver_key},
                timeout_seconds=args.timeout_seconds,
                max_attempts=args.max_attempts,
                retry_backoff_seconds=args.retry_backoff_ms / 1000.0,
            )
        except RuntimeError as error:
            print(f"[FAIL] batch {index}/{len(prepared_batches)}: {error}", file=sys.stderr)
            return 1
        if result.status == 409 and isinstance(result.body, dict):
            details = result.body.get("details") or {}
            disposition = details.get("disposition") if isinstance(details, dict) else None
            print(
                f"[FAIL] batch {index}/{len(prepared_batches)}: {describe_error(result)} "
                f"disposition={disposition!r} (RETRY = wait for MEASURING, DROP = session ended)",
                file=sys.stderr,
            )
            return 1
        if result.status != 200 or not isinstance(result.body, dict):
            print(
                f"[FAIL] batch {index}/{len(prepared_batches)}: {describe_error(result)}",
                file=sys.stderr,
            )
            return 1
        for key in totals:
            value = result.body.get(key)
            if not isinstance(value, int) or value < 0:
                print(f"[FAIL] response lacks valid {key}", file=sys.stderr)
                return 1
            totals[key] += value
        batch_total = sum(result.body[key] for key in totals)
        if batch_total != len(batch):
            print(
                f"[FAIL] batch {index}/{len(prepared_batches)}: response counts "
                f"sum to {batch_total}, expected {len(batch)}",
                file=sys.stderr,
            )
            return 1
        if result.body["rejectedCount"] > 0 and not args.allow_rejected:
            codes = sorted({item.get("code") for item in result.body.get("rejections", []) if isinstance(item, dict)})
            print(
                f"[FAIL] batch {index}/{len(prepared_batches)} rejected "
                f"{result.body['rejectedCount']} frame(s) {codes}; use --allow-rejected only for a negative test",
                file=sys.stderr,
            )
            return 1
        response_sequences = result.body.get("lastSequenceByDevice", {})
        if isinstance(response_sequences, dict):
            for device_id, sequence in response_sequences.items():
                if isinstance(sequence, int):
                    last_sequences[device_id] = max(last_sequences.get(device_id, -1), sequence)
        print(
            f"[SIMULATED] batch={index}/{len(prepared_batches)} frames={len(batch)} "
            f"accepted={result.body['acceptedCount']} duplicate={result.body['duplicateCount']} "
            f"rejected={result.body['rejectedCount']} attempts={result.attempts} "
            f"elapsedMs={result.elapsed_ms:.1f}"
        )
        next_deadline += args.batch_ms / 1000.0

    processed = sum(totals.values())
    if processed != len(frames):
        print(
            f"[FAIL] response counts sum to {processed}, expected {len(frames)} sent frames",
            file=sys.stderr,
        )
        return 1
    print(
        "[PASS] SIMULATED replay complete: "
        f"accepted={totals['acceptedCount']} duplicate={totals['duplicateCount']} "
        f"rejected={totals['rejectedCount']}"
    )
    print(f"[SIMULATED] lastSequenceByDevice={json.dumps(last_sequences, sort_keys=True)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
