#!/usr/bin/env python3
"""Hardware-free end-to-end run: backend + mock BLE gateway + STOMP subscriber.

This script is NOT part of the default verification chain. It needs, already running:

  1. MySQL (docker compose --env-file .env up -d) and the backend (cd backend && gradlew bootRun)
     with RECEIVER_API_KEY set; loopback HTTP is fine for the gateway.
  2. The BLE gateway package (smart-insole-ble-gateway) installed so that its CLI can be started,
     e.g. `smart-insole-gateway` on PATH or `python -m smart_insole_gateway.cli` via --gateway-command.

Flow (plan section 5, "하드웨어 없는 E2E"):

  signup -> register two devices on layout-s01s08-v1 -> create session (sourceType SIMULATED, 50 Hz)
  -> start -> receiver-side session lookup (X-Receiver-Key) -> STOMP subscribe with JSON Schema check
  -> gateway `run --mode mock --session-id ... --duration-seconds N --metrics-json run.json`
  -> (optional) replay pass with `--mock-script REPLAY_LAST` -> complete -> result polling.

Pass criteria (defaults, all overridable):

  - every STOMP message validates against contracts/realtime-message.schema.json and at least
    0.8 x 10 Hz x duration messages arrived
  - gateway MetricsSnapshot: outbox_pending == 0, batches_terminal_failed == 0,
    outbox_terminal_failed == 0, every queue overflows == 0
  - backend frame count (from the realtime snapshot lastSequence per foot, sequences start at 0
    after START_AND_SYNC) matches the gateway accepted count within --frame-tolerance
  - replay pass (if requested) reports >= 3 duplicates and the backend cursor does not move
  - result COMPLETED with algorithmVersion rule-v1.2.0 and sourceType SIMULATED

The gateway environment variable names follow the gateway's settings.py; override any of them with
--gateway-env KEY=VALUE. Credentials are never printed.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

from e2e_smoke import (
    StompClient,
    StompConnectionClosedError,
    StompTimeoutError,
    bearer,
    call,
    checked,
    default_websocket_url,
    positive_float,
    public_http_endpoint,
    receive_realtime_message,
    register_or_find_device,
    required_uuid,
    stomp_topic,
    validate_http_base_url,
    validate_websocket_origin,
    validate_websocket_url,
)

SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parent
LAYOUT_VERSION = "layout-s01s08-v1"
ALGORITHM_VERSION = "rule-v1.2.0"
DEFAULT_GATEWAY_ENV = {
    "SMART_INSOLE_BACKEND_ENABLED": "true",
    "SMART_INSOLE_BACKEND_CONTRACT_PROFILE": "openapi-1.1",
    "SMART_INSOLE_BACKEND_AUTHENTICATION_POLICY": "static-header",
    "SMART_INSOLE_BACKEND_AUTH_HEADER_NAME": "X-Receiver-Key",
    "SMART_INSOLE_BACKEND_ALLOW_INSECURE_LOOPBACK_HTTP": "true",
    "SMART_INSOLE_TRANSFER_MODE": "streaming",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--base-url", default=os.getenv("SMART_INSOLE_BASE_URL", "http://localhost:8080"))
    parser.add_argument("--ws-url", default=os.getenv("SMART_INSOLE_WS_URL"))
    parser.add_argument("--ws-origin", default=os.getenv("SMART_INSOLE_WS_ORIGIN", "http://localhost:5173"))
    parser.add_argument("--receiver-key", default=os.getenv("SMART_INSOLE_RECEIVER_KEY"))
    parser.add_argument("--run-id", default=os.getenv("SMART_INSOLE_E2E_RUN_ID", time.strftime("%Y%m%d%H%M%S")))
    parser.add_argument("--email", default=os.getenv("SMART_INSOLE_E2E_EMAIL"))
    parser.add_argument("--password", default=os.getenv("SMART_INSOLE_E2E_PASSWORD"))
    parser.add_argument("--sample-rate-hz", type=int, choices=[50, 100], default=50)
    parser.add_argument("--duration-seconds", type=positive_float, default=60.0)
    parser.add_argument("--publish-hz", type=positive_float, default=10.0, help="backend app.realtime.publish-hz")
    parser.add_argument("--message-ratio", type=positive_float, default=0.8,
                        help="required STOMP messages as a fraction of publish-hz x duration")
    parser.add_argument("--frame-tolerance", type=positive_float, default=0.10,
                        help="allowed relative difference between backend and gateway frame counts")
    parser.add_argument(
        "--gateway-command",
        default=os.getenv("SMART_INSOLE_GATEWAY_COMMAND", "smart-insole-gateway"),
        help="gateway CLI (shell-split), e.g. 'python -m smart_insole_gateway.cli'",
    )
    parser.add_argument("--gateway-cwd", type=Path, default=os.getenv("SMART_INSOLE_GATEWAY_DIR"))
    parser.add_argument(
        "--gateway-key-env",
        default=os.getenv("SMART_INSOLE_GATEWAY_KEY_ENV", "SMART_INSOLE_BACKEND_AUTH_TOKEN"),
        help="environment variable that carries the receiver key for the gateway",
    )
    parser.add_argument(
        "--gateway-base-url-env",
        default=os.getenv("SMART_INSOLE_GATEWAY_BASE_URL_ENV", "SMART_INSOLE_BACKEND_BASE_URL"),
    )
    parser.add_argument("--gateway-env", action="append", default=[], metavar="KEY=VALUE",
                        help="extra/override gateway environment variables (repeatable)")
    parser.add_argument("--gateway-extra-arg", action="append", default=[],
                        help="extra argument appended to the gateway run command (repeatable)")
    parser.add_argument("--metrics-json", type=Path, default=REPOSITORY_ROOT / "tmp" / "gateway-run.json")
    parser.add_argument("--replay", action="store_true",
                        help="run a second gateway pass with --mock-script REPLAY_LAST before completing")
    parser.add_argument("--result-timeout-seconds", type=positive_float, default=60.0)
    parser.add_argument("--poll-interval-seconds", type=positive_float, default=0.5)
    parser.add_argument("--request-timeout-seconds", type=positive_float, default=15.0)
    parser.add_argument("--stomp-timeout-seconds", type=positive_float, default=5.0)
    parser.add_argument("--gateway-grace-seconds", type=positive_float, default=60.0,
                        help="extra time the gateway may take beyond --duration-seconds")
    return parser.parse_args()


def serial_for(side: str, run_id: str) -> str:
    """SMART-INSOLE-{L|R}-{hex8}: the backend serial rule derived from a stable hash of the run id."""

    digest = hashlib.sha256(f"{run_id}:{side}".encode("utf-8")).hexdigest()[:8]
    return f"SMART-INSOLE-{side[0]}-{digest}"


def receiver_headers(key: str) -> dict[str, str]:
    return {"X-Receiver-Key": key}


class MessageCollector:
    """Receives STOMP messages on a background thread and validates each against the schema."""

    def __init__(self, client: StompClient, session_id: str, timeout_seconds: float):
        self._client = client
        self._session_id = session_id
        self._timeout_seconds = timeout_seconds
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, name="stomp-collector", daemon=True)
        self.count = 0
        self.last_message: dict[str, Any] | None = None
        self.error: str | None = None

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()
        self._thread.join(timeout=self._timeout_seconds * 2)

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                message = receive_realtime_message(
                    self._client, self._session_id, min(1.0, self._timeout_seconds),
                    "gateway realtime publication", announce=False,
                )
            except StompTimeoutError:
                continue
            except (StompConnectionClosedError, RuntimeError) as error:
                self.error = str(error)
                return
            self.count += 1
            self.last_message = message


def gateway_environment(args: argparse.Namespace) -> dict[str, str]:
    environment = dict(os.environ)
    environment.update(DEFAULT_GATEWAY_ENV)
    environment[args.gateway_base_url_env] = args.base_url
    environment[args.gateway_key_env] = args.receiver_key
    environment["SMART_INSOLE_SAMPLE_RATE_HZ"] = str(args.sample_rate_hz)
    for item in args.gateway_env:
        if "=" not in item:
            raise RuntimeError(f"--gateway-env expects KEY=VALUE, got {item!r}")
        key, value = item.split("=", 1)
        environment[key.strip()] = value
    return environment


def run_gateway(args: argparse.Namespace, session_id: str, metrics_path: Path, *, replay: bool) -> dict[str, Any]:
    command = [
        *shlex.split(args.gateway_command),
        "run",
        "--mode", "mock",
        "--session-id", session_id,
        "--duration-seconds", str(int(args.duration_seconds)),
        "--metrics-json", str(metrics_path),
        *(["--mock-script", "REPLAY_LAST"] if replay else []),
        *args.gateway_extra_arg,
    ]
    metrics_path.parent.mkdir(parents=True, exist_ok=True)
    log_path = metrics_path.with_suffix(".log")
    label = "replay" if replay else "streaming"
    print(f"[GATEWAY] {label} pass: {' '.join(command[: len(command) - len(args.gateway_extra_arg)])}")
    started = time.monotonic()
    with log_path.open("w", encoding="utf-8") as log_handle:
        try:
            completed = subprocess.run(
                command,
                cwd=str(args.gateway_cwd) if args.gateway_cwd else None,
                env=gateway_environment(args),
                stdout=log_handle,
                stderr=subprocess.STDOUT,
                timeout=args.duration_seconds + args.gateway_grace_seconds,
                check=False,
            )
        except FileNotFoundError as error:
            raise RuntimeError(f"gateway command not found: {error}") from error
        except subprocess.TimeoutExpired as error:
            raise RuntimeError(f"gateway did not exit within the allowed time: {error}") from error
    elapsed = time.monotonic() - started
    print(f"[GATEWAY] {label} pass exited with code {completed.returncode} after {elapsed:.1f}s (log: {log_path})")
    if completed.returncode != 0:
        raise RuntimeError(f"gateway {label} pass failed with exit code {completed.returncode}; see {log_path}")
    if not metrics_path.is_file():
        raise RuntimeError(f"gateway did not write metrics to {metrics_path}")
    with metrics_path.open("r", encoding="utf-8") as handle:
        metrics = json.load(handle)
    if not isinstance(metrics, dict):
        raise RuntimeError("gateway metrics JSON must be an object")
    return metrics


def metric(metrics: dict[str, Any], *path: str, default: Any = None) -> Any:
    node: Any = metrics
    for key in path:
        if not isinstance(node, dict) or key not in node:
            return default
        node = node[key]
    return node


def gateway_accepted(metrics: dict[str, Any]) -> int:
    """Total accepted frames from MetricsSnapshot: top-level accepted, or the sum over feet."""

    accepted = metric(metrics, "accepted")
    if isinstance(accepted, int):
        return accepted
    feet = metric(metrics, "feet")
    if isinstance(feet, dict):
        total = 0
        for snapshot in feet.values():
            if isinstance(snapshot, dict) and isinstance(snapshot.get("accepted"), int):
                total += snapshot["accepted"]
        return total
    raise RuntimeError("gateway metrics lack an accepted frame count (accepted or feet.*.accepted)")


def check_gateway_metrics(metrics: dict[str, Any]) -> None:
    for key in ("outbox_pending", "batches_terminal_failed", "outbox_terminal_failed"):
        value = metric(metrics, key)
        if not isinstance(value, int):
            raise RuntimeError(f"gateway metrics lack integer {key}")
        if value != 0:
            raise RuntimeError(f"gateway metrics {key}={value}, expected 0")
    queues = metric(metrics, "queues")
    if isinstance(queues, dict):
        for name, snapshot in queues.items():
            overflows = snapshot.get("overflows") if isinstance(snapshot, dict) else None
            if overflows not in (0, None):
                raise RuntimeError(f"gateway queue {name} overflows={overflows}, expected 0")
    print("[PASS] gateway metrics: outbox_pending=0, no terminal failures, no queue overflows")


def backend_frame_count(args: argparse.Namespace, session_id: str, token: str) -> int:
    snapshot = checked(
        call(args, "GET", f"/api/v1/measurement-sessions/{session_id}/realtime-snapshot", headers=bearer(token)),
        {200},
        "realtime snapshot for frame count",
    )
    total = 0
    for side in ("left", "right"):
        foot = snapshot.get(side)
        if not isinstance(foot, dict) or not isinstance(foot.get("lastSequence"), int):
            raise RuntimeError(f"realtime snapshot lacks {side} lastSequence")
        total += foot["lastSequence"] + 1
    return total


def main() -> int:
    args = parse_args()
    if not args.receiver_key:
        print("[FAIL] --receiver-key or SMART_INSOLE_RECEIVER_KEY is required.", file=sys.stderr)
        return 2
    run_id = re.sub(r"[^A-Za-z0-9-]", "-", args.run_id).strip("-")[:40]
    if not run_id:
        print("[FAIL] --run-id must contain a letter, digit, or hyphen.", file=sys.stderr)
        return 2
    email = args.email or f"gateway-e2e+{run_id.lower()}@example.com"
    password = args.password or f"Gw-{run_id}-Aa!1"
    try:
        validate_http_base_url(args.base_url)
        ws_url = validate_websocket_url(args.ws_url or default_websocket_url(args.base_url))
        validate_websocket_origin(args.ws_origin)
    except ValueError as error:
        print(f"[FAIL] arguments: {error}", file=sys.stderr)
        return 2

    print(f"[GATEWAY-E2E] baseUrl={public_http_endpoint(args.base_url)} runId={run_id} "
          f"sampleRateHz={args.sample_rate_hz} durationSeconds={args.duration_seconds:.0f}")
    print("[GATEWAY-E2E] synthetic account, SIMULATED session; credentials are not displayed")

    stomp_client: StompClient | None = None
    collector: MessageCollector | None = None
    try:
        checked(call(args, "POST", "/api/v1/auth/signup",
                     payload={"email": email, "password": password, "name": "Gateway E2E User"}),
                {201, 409}, "signup or existing synthetic account")
        signin = checked(call(args, "POST", "/api/v1/auth/signin",
                              payload={"email": email, "password": password}), {200}, "signin")
        token = signin.get("accessToken")
        if not isinstance(token, str) or not token:
            raise RuntimeError("signin response lacks accessToken")

        args.sensor_layout_version = LAYOUT_VERSION
        left_serial = serial_for("LEFT", run_id)
        right_serial = serial_for("RIGHT", run_id)
        left_id = register_or_find_device(args, token, serial_number=left_serial, display_name="Gateway LEFT",
                                          foot_side="LEFT", sensor_count=8)
        right_id = register_or_find_device(args, token, serial_number=right_serial, display_name="Gateway RIGHT",
                                           foot_side="RIGHT", sensor_count=8)

        session = checked(
            call(args, "POST", "/api/v1/measurement-sessions", headers=bearer(token), payload={
                "leftDeviceId": left_id,
                "rightDeviceId": right_id,
                "sampleRateHz": args.sample_rate_hz,
                "sourceType": "SIMULATED",
                "memo": f"Gateway mock E2E {run_id}",
            }),
            {201}, "create SIMULATED session",
        )
        session_id = required_uuid(session, "sessionId", "create session")
        if session.get("sourceType") != "SIMULATED" or session.get("sampleRateHz") != args.sample_rate_hz:
            raise RuntimeError("session response does not echo sourceType SIMULATED / sampleRateHz")
        if session.get("adcMax") != 4095:
            raise RuntimeError(f"session adcMax={session.get('adcMax')!r}, expected 4095")

        checked(call(args, "POST", f"/api/v1/measurement-sessions/{session_id}/start", headers=bearer(token)),
                {200}, "start session")

        receiver_view = checked(
            call(args, "GET", f"/internal/v1/measurement-sessions/{session_id}",
                 headers=receiver_headers(args.receiver_key)),
            {200}, "receiver session lookup",
        )
        if receiver_view.get("status") != "MEASURING":
            raise RuntimeError("receiver session lookup does not report MEASURING")
        for side, device_id, serial in (("left", left_id, left_serial), ("right", right_id, right_serial)):
            device = receiver_view.get(side)
            if not isinstance(device, dict) or device.get("deviceId") != device_id \
                    or device.get("serialNumber") != serial or device.get("adcMax") != 4095 \
                    or device.get("sensorLayoutVersion") != LAYOUT_VERSION:
                raise RuntimeError(f"receiver session lookup {side} device does not match the registration")
        listing = checked(
            call(args, "GET", f"/internal/v1/measurement-sessions?status=MEASURING&deviceSerial={left_serial}",
                 headers=receiver_headers(args.receiver_key)),
            {200}, "receiver MEASURING session list",
        )
        items = listing.get("items")
        if not isinstance(items, list) or not any(
                isinstance(item, dict) and item.get("sessionId") == session_id for item in items):
            raise RuntimeError("receiver session list does not contain the started session")

        stomp_client = StompClient.connect(ws_url, origin=args.ws_origin, token=token,
                                           timeout_seconds=args.stomp_timeout_seconds)
        stomp_client.subscribe(stomp_topic(session_id), "gateway-e2e")
        collector = MessageCollector(stomp_client, session_id, args.stomp_timeout_seconds)
        collector.start()
        print("[PASS] owner STOMP subscription active; collecting realtime messages")

        metrics = run_gateway(args, session_id, args.metrics_json, replay=False)
        check_gateway_metrics(metrics)
        accepted = gateway_accepted(metrics)
        duplicates = metric(metrics, "duplicates", default=0)
        rejected = metric(metrics, "rejected", default=0)
        if duplicates not in (0, None) or rejected not in (0, None):
            raise RuntimeError(f"streaming pass reported duplicates={duplicates} rejected={rejected}, expected 0")
        expected_frames = 2 * args.sample_rate_hz * args.duration_seconds
        if abs(accepted - expected_frames) > args.frame_tolerance * expected_frames:
            raise RuntimeError(
                f"gateway accepted {accepted} frames, expected {expected_frames:.0f} +-{args.frame_tolerance:.0%}")
        backend_frames = backend_frame_count(args, session_id, token)
        if abs(backend_frames - accepted) > args.frame_tolerance * max(1, accepted):
            raise RuntimeError(f"backend frame estimate {backend_frames} differs from gateway accepted {accepted}")
        print(f"[PASS] frames: gateway accepted={accepted} backend estimate={backend_frames}")

        if args.replay:
            replay_metrics = run_gateway(args, session_id, args.metrics_json.with_name("gateway-replay.json"),
                                         replay=True)
            replay_duplicates = metric(replay_metrics, "duplicates")
            if not isinstance(replay_duplicates, int) or replay_duplicates < 3:
                raise RuntimeError(f"replay pass duplicates={replay_duplicates!r}, expected >= 3")
            if backend_frame_count(args, session_id, token) != backend_frames:
                raise RuntimeError("replay pass moved the backend cursor; idempotency violated")
            print(f"[PASS] replay pass: duplicates={replay_duplicates}, backend cursor unchanged")

        collector.stop()
        if collector.error:
            raise RuntimeError(f"STOMP collector failed: {collector.error}")
        required_messages = args.message_ratio * args.publish_hz * args.duration_seconds
        if collector.count < required_messages:
            raise RuntimeError(f"received {collector.count} STOMP messages, expected >= {required_messages:.0f}")
        print(f"[PASS] STOMP: {collector.count} schema-valid messages (>= {required_messages:.0f})")

        completed = checked(call(args, "POST", f"/api/v1/measurement-sessions/{session_id}/complete",
                                 headers=bearer(token)), {200}, "complete session")
        if completed.get("status") != "PROCESSING":
            raise RuntimeError(f"complete returned status={completed.get('status')!r}")
        deadline = time.monotonic() + args.result_timeout_seconds
        result_body: dict[str, Any] | None = None
        while time.monotonic() < deadline:
            result = call(args, "GET", f"/api/v1/measurement-sessions/{session_id}/result", headers=bearer(token))
            if result.status == 200:
                result_body = checked(result, {200}, "analysis result")
                break
            if result.status != 202:
                checked(result, {200, 202}, "poll result")
            time.sleep(args.poll_interval_seconds)
        if result_body is None:
            raise RuntimeError("result did not complete in time")
        if result_body.get("status") != "COMPLETED" or result_body.get("algorithmVersion") != ALGORITHM_VERSION:
            raise RuntimeError("result is not a COMPLETED rule-v1.2.0 result")
        summary = result_body.get("observationSummary")
        if not isinstance(summary, list) or len(summary) != 6:
            raise RuntimeError("rule-v1.2.0 result lacks the six-entry observationSummary")
        final_session = checked(call(args, "GET", f"/api/v1/measurement-sessions/{session_id}",
                                     headers=bearer(token)), {200}, "final session")
        if final_session.get("sourceType") != "SIMULATED" or final_session.get("status") != "COMPLETED":
            raise RuntimeError("final session is not COMPLETED/SIMULATED")
        print(f"[PASS] gateway mock E2E complete: sessionId={session_id} accepted={accepted} "
              f"stompMessages={collector.count}")
        return 0
    except RuntimeError as error:
        print(f"[FAIL] gateway mock E2E: {error}", file=sys.stderr)
        return 1
    finally:
        if collector is not None:
            collector.stop()
        if stomp_client is not None:
            stomp_client.close()


if __name__ == "__main__":
    raise SystemExit(main())
