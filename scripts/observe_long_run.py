#!/usr/bin/env python3
"""Generate deterministic bilateral input and report measured ingestion behavior."""

from __future__ import annotations

import argparse
import json
import math
import os
import random
import sys
import time
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from http_tools import describe_error, join_url, request_json

ADC_MAX = 4095  # 12-bit RAW ADC scale; 4095 is the session adcMax


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def positive_float(value: str) -> float:
    parsed = float(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def nonnegative_integer(value: str) -> int:
    parsed = int(value)
    if parsed < 0:
        raise argparse.ArgumentTypeError("must be non-negative")
    return parsed


def bounded_batch_ms(value: str) -> int:
    parsed = int(value)
    if not 100 <= parsed <= 200:
        raise argparse.ArgumentTypeError("must be between 100 and 200")
    return parsed


def uuid_value(value: str) -> str:
    try:
        return str(uuid.UUID(value))
    except ValueError as error:
        raise argparse.ArgumentTypeError("must be a UUID") from error


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-url",
        default=os.getenv("SMART_INSOLE_BASE_URL", "http://localhost:8080"),
    )
    parser.add_argument("--session-id", type=uuid_value, default=os.getenv("SMART_INSOLE_SESSION_ID"))
    parser.add_argument(
        "--left-device-id", type=uuid_value, default=os.getenv("SMART_INSOLE_LEFT_DEVICE_ID")
    )
    parser.add_argument(
        "--right-device-id", type=uuid_value, default=os.getenv("SMART_INSOLE_RIGHT_DEVICE_ID")
    )
    parser.add_argument("--receiver-key", default=os.getenv("SMART_INSOLE_RECEIVER_KEY"))
    parser.add_argument("--receiver-id", default="LONG-RUN-SIMULATED")
    parser.add_argument("--duration-seconds", type=positive_float, default=60.0)
    parser.add_argument("--batch-ms", type=bounded_batch_ms, default=100)
    parser.add_argument("--sample-rate-hz", type=int, choices=[50, 100], default=100,
                        help="frames per second per foot; must equal the session sampleRateHz")
    parser.add_argument("--sensor-count", type=int, choices=[6, 8], default=8)
    parser.add_argument("--seed", type=int, default=20260902)
    parser.add_argument("--start-sequence", type=nonnegative_integer, default=0)
    parser.add_argument("--start-device-time-ms", type=nonnegative_integer, default=0)
    parser.add_argument("--request-timeout-seconds", type=positive_float, default=15.0)
    parser.add_argument("--max-attempts", type=int, choices=range(1, 11), default=3)
    parser.add_argument("--retry-backoff-ms", type=positive_float, default=250.0)
    parser.add_argument("--no-pace", action="store_true", help="send as quickly as responses allow")
    parser.add_argument("--dry-run", action="store_true", help="generate and measure locally without HTTP")
    parser.add_argument("--output", type=Path, help="optional path for the JSON observation report")
    return parser.parse_args()


def sensor_values(
    tick: int,
    side: str,
    sensor_count: int,
    sample_rate_hz: int,
    random_source: random.Random,
) -> list[int]:
    gait_period = max(1, round(sample_rate_hz * 0.56))
    side_offset = 0 if side == "LEFT" else gait_period // 2
    phase = ((tick + side_offset) % gait_period) / gait_period
    contact_wave = max(0.0, math.sin(math.pi * min(phase / 0.72, 1.0))) if phase <= 0.72 else 0.0
    weights = [0.42, 0.58, 0.74, 1.0, 0.88, 0.62, 0.45, 0.32][:sensor_count]
    values: list[int] = []
    for weight in weights:
        jitter = random_source.randint(-12, 12)
        value = round(24 + contact_wave * 900 * weight + jitter)
        values.append(max(0, min(ADC_MAX, value)))
    return values


def percentile(values: list[float], percentage: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = max(0, min(len(ordered) - 1, math.ceil(len(ordered) * percentage) - 1))
    return ordered[index]


def create_report(
    args: argparse.Namespace,
    *,
    started_at: str,
    finished_at: str,
    wall_seconds: float,
    request_latencies: list[float],
    requests: int,
    attempts: int,
    generated: int,
    accepted: int,
    duplicates: int,
    rejected: int,
    last_sequences: dict[str, int],
    failure: str | None,
) -> dict[str, Any]:
    return {
        "notice": "Synthetic observation only. No SLA or clinical claim is made.",
        "mode": "DRY_RUN" if args.dry_run else "HTTP",
        "startedAt": started_at,
        "finishedAt": finished_at,
        "configuration": {
            "baseUrl": args.base_url.rstrip("/"),
            "durationSecondsRequested": args.duration_seconds,
            "batchWindowMs": args.batch_ms,
            "sampleRateHzPerFoot": args.sample_rate_hz,
            "sensorCount": args.sensor_count,
            "seed": args.seed,
            "paced": not args.no_pace and not args.dry_run,
        },
        "measured": {
            "wallSeconds": round(wall_seconds, 6),
            "requests": requests,
            "requestAttempts": attempts,
            "generatedFrames": generated,
            "acceptedFrames": accepted,
            "duplicateFrames": duplicates,
            "rejectedFrames": rejected,
            "generatedFramesPerSecond": (
                round(generated / wall_seconds, 3) if wall_seconds > 0 and not args.dry_run else None
            ),
            "requestLatencyMs": {
                "minimum": round(min(request_latencies), 3) if request_latencies else None,
                "p50": round(percentile(request_latencies, 0.50), 3) if request_latencies else None,
                "p95": round(percentile(request_latencies, 0.95), 3) if request_latencies else None,
                "maximum": round(max(request_latencies), 3) if request_latencies else None,
            },
            "lastSequenceByDevice": last_sequences,
        },
        "failure": failure,
    }


def main() -> int:
    args = parse_args()
    required = {
        "--session-id or SMART_INSOLE_SESSION_ID": args.session_id,
        "--left-device-id or SMART_INSOLE_LEFT_DEVICE_ID": args.left_device_id,
        "--right-device-id or SMART_INSOLE_RIGHT_DEVICE_ID": args.right_device_id,
    }
    if not args.dry_run:
        required["--receiver-key or SMART_INSOLE_RECEIVER_KEY"] = args.receiver_key
    missing = [name for name, value in required.items() if not value]
    if missing:
        print(f"[FAIL] Missing required values: {', '.join(missing)}", file=sys.stderr)
        return 2

    ticks_total = max(1, round(args.duration_seconds * args.sample_rate_hz))
    ticks_per_batch = max(1, round(args.sample_rate_hz * args.batch_ms / 1000))
    sample_period_ms = round(1000 / args.sample_rate_hz)
    endpoint = join_url(
        args.base_url,
        f"/internal/v1/measurement-sessions/{args.session_id}/frame-batches",
    )
    random_source = random.Random(args.seed)
    started_at = utc_now()
    started_clock = time.perf_counter()
    next_deadline = time.monotonic()
    request_latencies: list[float] = []
    requests = 0
    request_attempts = 0
    generated = 0
    accepted = 0
    duplicates = 0
    rejected = 0
    last_sequences: dict[str, int] = {}
    failure: str | None = None

    print(
        f"[OBSERVE][SIMULATED] duration={args.duration_seconds:.3f}s "
        f"rate={args.sample_rate_hz}/foot batchWindowMs={args.batch_ms} seed={args.seed}"
    )
    print("[OBSERVE] This records measurements; it does not assert an SLA.")

    for batch_start in range(0, ticks_total, ticks_per_batch):
        tick_count = min(ticks_per_batch, ticks_total - batch_start)
        frames: list[dict[str, Any]] = []
        for local_tick in range(tick_count):
            tick = batch_start + local_tick
            sequence = args.start_sequence + tick
            device_time_ms = args.start_device_time_ms + tick * sample_period_ms
            for side, device_id in (("LEFT", args.left_device_id), ("RIGHT", args.right_device_id)):
                frames.append(
                    {
                        "deviceId": device_id,
                        "footSide": side,
                        "sequence": sequence,
                        "deviceTimeMs": device_time_ms,
                        "sensorValues": sensor_values(
                            tick, side, args.sensor_count, args.sample_rate_hz, random_source
                        ),
                    }
                )
        generated += len(frames)
        requests += 1

        if args.dry_run:
            accepted += len(frames)
            last_sequences[args.left_device_id] = frames[-2]["sequence"]
            last_sequences[args.right_device_id] = frames[-1]["sequence"]
            continue

        if not args.no_pace:
            remaining = next_deadline - time.monotonic()
            if remaining > 0:
                time.sleep(remaining)
        payload = {
            "schemaVersion": "1.0",
            "receiverId": args.receiver_id,
            "sentAt": utc_now(),
            "frames": frames,
        }
        try:
            result = request_json(
                "POST",
                endpoint,
                payload=payload,
                headers={"X-Receiver-Key": args.receiver_key},
                timeout_seconds=args.request_timeout_seconds,
                max_attempts=args.max_attempts,
                retry_backoff_seconds=args.retry_backoff_ms / 1000.0,
            )
        except RuntimeError as error:
            failure = str(error)
            break
        request_attempts += result.attempts
        request_latencies.append(result.elapsed_ms)
        if result.status != 200 or not isinstance(result.body, dict):
            failure = describe_error(result)
            break
        counts = [result.body.get(key) for key in ("acceptedCount", "duplicateCount", "rejectedCount")]
        if not all(isinstance(value, int) and value >= 0 for value in counts):
            failure = "response lacks non-negative accepted/duplicate/rejected counts"
            break
        accepted += counts[0]
        duplicates += counts[1]
        rejected += counts[2]
        response_sequences = result.body.get("lastSequenceByDevice", {})
        if isinstance(response_sequences, dict):
            for device_id, sequence in response_sequences.items():
                if isinstance(sequence, int):
                    last_sequences[device_id] = max(
                        last_sequences.get(device_id, -1), sequence
                    )
        next_deadline += args.batch_ms / 1000.0

    if failure is None and accepted + duplicates + rejected != generated:
        failure = (
            "response counts do not match generated frames: "
            f"processed={accepted + duplicates + rejected}, generated={generated}"
        )
    if failure is None and rejected > 0:
        failure = f"server rejected {rejected} generated frame(s)"

    wall_seconds = time.perf_counter() - started_clock
    report = create_report(
        args,
        started_at=started_at,
        finished_at=utc_now(),
        wall_seconds=wall_seconds,
        request_latencies=request_latencies,
        requests=requests,
        attempts=request_attempts,
        generated=generated,
        accepted=accepted,
        duplicates=duplicates,
        rejected=rejected,
        last_sequences=last_sequences,
        failure=failure,
    )
    rendered = json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True)
    print(rendered)
    if args.output:
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(rendered + "\n", encoding="utf-8")
        print(f"[OBSERVE] report written to {args.output}")
    if failure:
        print(f"[FAIL] Long-run observation stopped: {failure}", file=sys.stderr)
        return 1
    print("[PASS] Long-run observation completed; review the measured report for bottlenecks.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
