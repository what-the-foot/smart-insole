#!/usr/bin/env python3
"""Replay a FrameBatch fixture to the Receiver API as SIMULATED data."""

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


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def parse_args() -> argparse.Namespace:
    repository_root = SCRIPTS_DIR.parent
    parser = argparse.ArgumentParser(description=__doc__)
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
        help="replacement LEFT device UUID (or SMART_INSOLE_LEFT_DEVICE_ID)",
    )
    parser.add_argument(
        "--right-device-id",
        type=uuid_value,
        default=os.getenv("SMART_INSOLE_RIGHT_DEVICE_ID"),
        help="replacement RIGHT device UUID (or SMART_INSOLE_RIGHT_DEVICE_ID)",
    )
    parser.add_argument(
        "--receiver-key",
        default=os.getenv("SMART_INSOLE_RECEIVER_KEY"),
        help="Receiver credential (or SMART_INSOLE_RECEIVER_KEY); never printed",
    )
    parser.add_argument("--receiver-id", help="optional receiverId override")
    parser.add_argument("--batch-ms", type=bounded_integer(100, 200), default=100)
    parser.add_argument("--sample-rate-hz", type=positive_integer, default=100)
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
    return value


def prepare_frames(
    fixture: dict[str, Any],
    *,
    left_device_id: str | None,
    right_device_id: str | None,
    repeats: int,
    sample_rate_hz: int,
) -> list[dict[str, Any]]:
    source_frames: list[dict[str, Any]] = fixture["frames"]
    sides = {frame["footSide"] for frame in source_frames}
    if "LEFT" in sides and not left_device_id:
        raise ValueError("--left-device-id is required for a fixture containing LEFT frames")
    if "RIGHT" in sides and not right_device_id:
        raise ValueError("--right-device-id is required for a fixture containing RIGHT frames")

    sequence_span: dict[str, int] = {}
    time_span: dict[str, int] = {}
    sample_period_ms = max(1, round(1000 / sample_rate_hz))
    for side in sides:
        side_frames = [frame for frame in source_frames if frame["footSide"] == side]
        sequences = [frame["sequence"] for frame in side_frames]
        times = [frame["deviceTimeMs"] for frame in side_frames]
        sequence_span[side] = max(sequences) - min(sequences) + 1
        time_span[side] = max(times) - min(times) + sample_period_ms

    replacement = {"LEFT": left_device_id, "RIGHT": right_device_id}
    prepared: list[dict[str, Any]] = []
    for repeat_index in range(repeats):
        for source in source_frames:
            frame = copy.deepcopy(source)
            side = frame["footSide"]
            frame["deviceId"] = replacement[side]
            frame["sequence"] += repeat_index * sequence_span[side]
            frame["deviceTimeMs"] += repeat_index * time_span[side]
            prepared.append(frame)
    return prepared


def chunks(frames: list[dict[str, Any]], batch_ms: int, sample_rate_hz: int) -> list[list[dict[str, Any]]]:
    side_count = len({frame["footSide"] for frame in frames})
    target = max(1, round(sample_rate_hz * batch_ms / 1000)) * side_count
    return [frames[index : index + target] for index in range(0, len(frames), target)]


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
        frames = prepare_frames(
            fixture,
            left_device_id=args.left_device_id,
            right_device_id=args.right_device_id,
            repeats=args.repeat,
            sample_rate_hz=args.sample_rate_hz,
        )
    except (OSError, json.JSONDecodeError, ValueError) as error:
        print(f"[FAIL] {error}", file=sys.stderr)
        return 2

    prepared_batches = chunks(frames, args.batch_ms, args.sample_rate_hz)
    endpoint = join_url(
        args.base_url,
        f"/internal/v1/measurement-sessions/{args.session_id}/frame-batches",
    )
    print(
        f"[SIMULATED] fixture={fixture_path.name} frames={len(frames)} "
        f"batches={len(prepared_batches)} batchWindowMs={args.batch_ms} repeat={args.repeat}"
    )
    print(f"[SIMULATED] target={endpoint}; credentials are not displayed")

    if args.dry_run:
        sizes = [len(batch) for batch in prepared_batches]
        print(f"[SIMULATED] dry-run batchSizes={sizes}")
        print("[PASS] No network requests were sent.")
        return 0

    totals = {"acceptedCount": 0, "duplicateCount": 0, "rejectedCount": 0}
    last_sequences: dict[str, int] = {}
    next_deadline = time.monotonic()
    for index, batch in enumerate(prepared_batches, start=1):
        if not args.no_wait:
            remaining = next_deadline - time.monotonic()
            if remaining > 0:
                time.sleep(remaining)
        payload = {
            "schemaVersion": fixture.get("schemaVersion", "1.0"),
            "receiverId": args.receiver_id or fixture.get("receiverId", "SIMULATED-RECEIVER"),
            "sentAt": utc_now(),
            "frames": batch,
        }
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
            print(
                f"[FAIL] batch {index}/{len(prepared_batches)} rejected "
                f"{result.body['rejectedCount']} frame(s); use --allow-rejected only for a negative test",
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
