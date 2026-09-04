#!/usr/bin/env python3
"""Run the contract-level API smoke flow against a local Smart Insole backend."""

from __future__ import annotations

import argparse
import copy
import json
import os
import re
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable
from urllib.parse import urlencode, urlsplit, urlunsplit

import yaml
from jsonschema import Draft7Validator, Draft202012Validator, FormatChecker
import websocket
from websocket import WebSocket, WebSocketTimeoutException

from http_tools import HttpResult, describe_error, join_url, load_json, request_json
from validate_contracts import dereference_schema


SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY_ROOT = SCRIPT_DIR.parent
REALTIME_SCHEMA_PATH = REPOSITORY_ROOT / "contracts" / "realtime-message.schema.json"
OPENAPI_PATH = REPOSITORY_ROOT / "contracts" / "openapi.yaml"


@dataclass(frozen=True)
class StompFrame:
    command: str
    headers: dict[str, str]
    body: str


class StompTimeoutError(RuntimeError):
    pass


class StompConnectionClosedError(RuntimeError):
    pass


def _decode_stomp_header(value: str) -> str:
    decoded: list[str] = []
    index = 0
    escapes = {"n": "\n", "r": "\r", "c": ":", "\\": "\\"}
    while index < len(value):
        if value[index] != "\\":
            decoded.append(value[index])
            index += 1
            continue
        if index + 1 >= len(value) or value[index + 1] not in escapes:
            raise ValueError("invalid STOMP header escape")
        decoded.append(escapes[value[index + 1]])
        index += 2
    return "".join(decoded)


def _encode_stomp_header(value: str) -> str:
    return value.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n").replace(":", "\\c")


def parse_stomp_frames(buffer: str) -> tuple[list[StompFrame], str]:
    """Parse complete NUL-terminated STOMP frames and preserve a partial tail."""

    frames: list[StompFrame] = []
    remaining = buffer
    while True:
        while remaining.startswith("\n") or remaining.startswith("\r\n"):
            remaining = remaining[1:] if remaining.startswith("\n") else remaining[2:]
        terminator = remaining.find("\x00")
        if terminator < 0:
            return frames, remaining
        raw = remaining[:terminator]
        remaining = remaining[terminator + 1 :]
        if not raw:
            continue
        separator = re.search(r"\r?\n\r?\n", raw)
        if separator is None:
            raise ValueError("STOMP frame is missing the header/body separator")
        header_text = raw[: separator.start()].replace("\r\n", "\n")
        body = raw[separator.end() :]
        lines = header_text.split("\n")
        command = lines[0].strip()
        if not command:
            raise ValueError("STOMP frame command is empty")
        decode_headers = command not in {"CONNECT", "CONNECTED"}
        headers: dict[str, str] = {}
        for line in lines[1:]:
            if ":" not in line:
                raise ValueError("STOMP header is missing ':'")
            name, value = line.split(":", 1)
            if decode_headers:
                name = _decode_stomp_header(name)
                value = _decode_stomp_header(value)
            headers.setdefault(name, value)
        content_length = headers.get("content-length")
        if content_length is not None:
            try:
                expected_length = int(content_length)
            except ValueError as error:
                raise ValueError("STOMP content-length is not an integer") from error
            if expected_length < 0 or len(body.encode("utf-8")) != expected_length:
                raise ValueError("STOMP content-length does not match the body")
        frames.append(StompFrame(command, headers, body))


def build_stomp_frame(command: str, headers: dict[str, str], body: str = "") -> str:
    encode_headers = command not in {"CONNECT", "CONNECTED"}
    rendered_headers = [
        f"{_encode_stomp_header(name) if encode_headers else name}:"
        f"{_encode_stomp_header(value) if encode_headers else value}"
        for name, value in headers.items()
    ]
    return "\n".join([command, *rendered_headers, "", body]) + "\x00"


class StompClient:
    def __init__(self, connection: WebSocket, timeout_seconds: float):
        self._connection = connection
        self._timeout_seconds = timeout_seconds
        self._buffer = ""
        self._pending: list[StompFrame] = []

    @classmethod
    def connect(
        cls,
        url: str,
        *,
        origin: str,
        token: str,
        timeout_seconds: float,
    ) -> "StompClient":
        try:
            connection = websocket.create_connection(
                url,
                timeout=timeout_seconds,
                origin=origin,
                subprotocols=["v12.stomp"],
            )
        except (OSError, websocket.WebSocketException) as error:
            raise RuntimeError(f"STOMP WebSocket connection failed: {type(error).__name__}") from error
        client = cls(connection, timeout_seconds)
        client._send(
            "CONNECT",
            {
                "accept-version": "1.2",
                "host": urlsplit(url).hostname or "localhost",
                "Authorization": f"Bearer {token}",
                "heart-beat": "0,0",
            },
        )
        try:
            connected = client.wait_for({"CONNECTED"}, timeout_seconds)
        except RuntimeError:
            client.close(send_disconnect=False)
            raise
        if connected.headers.get("version") not in {None, "1.2"}:
            client.close(send_disconnect=False)
            raise RuntimeError(f"STOMP negotiated unsupported version {connected.headers.get('version')!r}")
        return client

    def subscribe(self, destination: str, subscription_id: str) -> None:
        receipt = f"subscribe-{subscription_id}"
        self._send(
            "SUBSCRIBE",
            {
                "id": subscription_id,
                "destination": destination,
                "ack": "auto",
                "receipt": receipt,
            },
        )
        response = self.wait_for({"RECEIPT"}, self._timeout_seconds)
        if response.headers.get("receipt-id") != receipt:
            raise RuntimeError("STOMP subscription returned an unexpected receipt")

    def expect_subscription_denied(self, destination: str, subscription_id: str) -> str:
        receipt = f"subscribe-{subscription_id}"
        self._send(
            "SUBSCRIBE",
            {
                "id": subscription_id,
                "destination": destination,
                "ack": "auto",
                "receipt": receipt,
            },
        )
        try:
            response = self.wait_for({"ERROR", "RECEIPT", "MESSAGE"}, self._timeout_seconds)
        except StompConnectionClosedError:
            return "connection closed"
        if response.command == "ERROR":
            return "ERROR frame"
        raise RuntimeError(
            f"unauthorized STOMP subscription was accepted ({response.command} received)"
        )

    def receive_message(self, timeout_seconds: float | None = None) -> StompFrame:
        return self.wait_for({"MESSAGE"}, timeout_seconds or self._timeout_seconds)

    def wait_for(self, commands: set[str], timeout_seconds: float) -> StompFrame:
        deadline = time.monotonic() + timeout_seconds
        while True:
            if self._pending:
                frame = self._pending.pop(0)
            else:
                remaining_seconds = deadline - time.monotonic()
                if remaining_seconds <= 0:
                    raise StompTimeoutError(
                        f"timed out after {timeout_seconds:.1f}s waiting for STOMP {sorted(commands)}"
                    )
                frame = self._receive_frame(remaining_seconds)
            if frame.command == "ERROR" and "ERROR" not in commands:
                raise RuntimeError("STOMP broker returned an ERROR frame")
            if frame.command in commands:
                return frame

    def close(self, *, send_disconnect: bool = True) -> None:
        if send_disconnect:
            try:
                self._send("DISCONNECT", {})
            except RuntimeError:
                pass
        try:
            self._connection.close()
        except websocket.WebSocketException:
            pass

    def _send(self, command: str, headers: dict[str, str], body: str = "") -> None:
        try:
            self._connection.send(build_stomp_frame(command, headers, body))
        except (OSError, websocket.WebSocketException) as error:
            raise StompConnectionClosedError(
                f"STOMP send failed: {type(error).__name__}"
            ) from error

    def _receive_frame(self, timeout_seconds: float) -> StompFrame:
        deadline = time.monotonic() + timeout_seconds
        while True:
            try:
                parsed, self._buffer = parse_stomp_frames(self._buffer)
            except ValueError as error:
                raise RuntimeError(f"invalid STOMP frame: {error}") from error
            if parsed:
                self._pending.extend(parsed[1:])
                return parsed[0]
            remaining_seconds = deadline - time.monotonic()
            if remaining_seconds <= 0:
                raise StompTimeoutError(
                    f"timed out after {timeout_seconds:.1f}s waiting for a STOMP frame"
                )
            self._connection.settimeout(remaining_seconds)
            try:
                payload = self._connection.recv()
            except WebSocketTimeoutException as error:
                raise StompTimeoutError(
                    f"timed out after {timeout_seconds:.1f}s waiting for a STOMP frame"
                ) from error
            except (OSError, websocket.WebSocketException) as error:
                raise StompConnectionClosedError("STOMP WebSocket connection closed") from error
            if payload in {"", b""}:
                raise StompConnectionClosedError("STOMP WebSocket connection closed")
            if isinstance(payload, bytes):
                try:
                    payload = payload.decode("utf-8")
                except UnicodeDecodeError as error:
                    raise RuntimeError("STOMP WebSocket returned non-UTF-8 data") from error
            self._buffer += payload


def utc_now() -> str:
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds").replace("+00:00", "Z")


def default_websocket_url(base_url: str) -> str:
    parsed = urlsplit(base_url)
    scheme = {"http": "ws", "https": "wss"}.get(parsed.scheme.lower())
    if scheme is None or not parsed.netloc:
        raise ValueError("base URL must be an absolute http:// or https:// URL")
    path = f"{parsed.path.rstrip('/')}/ws"
    return urlunsplit((scheme, parsed.netloc, path, "", ""))


def validate_http_base_url(url: str) -> str:
    parsed = urlsplit(url)
    try:
        host = parsed.hostname
        parsed.port
    except ValueError as error:
        raise ValueError("base URL contains an invalid host or port") from error
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc or not host:
        raise ValueError("base URL must be an absolute http:// or https:// URL")
    return url


def validate_websocket_url(url: str) -> str:
    parsed = urlsplit(url)
    try:
        host = parsed.hostname
        parsed.port
    except ValueError as error:
        raise ValueError("WebSocket URL contains an invalid host or port") from error
    if parsed.scheme.lower() not in {"ws", "wss"} or not parsed.netloc or not host:
        raise ValueError("WebSocket URL must be an absolute ws:// or wss:// URL")
    return url


def validate_websocket_origin(origin: str) -> str:
    parsed = urlsplit(origin)
    try:
        host = parsed.hostname
        parsed.port
    except ValueError as error:
        raise ValueError("WebSocket Origin contains an invalid host or port") from error
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.netloc or not host:
        raise ValueError("WebSocket Origin must be an absolute http:// or https:// origin")
    if parsed.path not in {"", "/"} or parsed.query or parsed.fragment:
        raise ValueError("WebSocket Origin must not contain a path, query, or fragment")
    return origin


def public_http_endpoint(base_url: str) -> str:
    """Render an endpoint label without URL credentials, query values, or fragments."""

    parsed = urlsplit(base_url)
    host = parsed.hostname or "<invalid-host>"
    if ":" in host and not host.startswith("["):
        host = f"[{host}]"
    authority = f"{host}:{parsed.port}" if parsed.port is not None else host
    return urlunsplit((parsed.scheme, authority, parsed.path.rstrip("/"), "", ""))


def positive_float(value: str) -> float:
    parsed = float(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be positive")
    return parsed


def parse_args() -> argparse.Namespace:
    default_run_id = datetime.now(timezone.utc).strftime("%Y%m%d%H%M%S")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--base-url",
        default=os.getenv("SMART_INSOLE_BASE_URL", "http://localhost:8080"),
    )
    parser.add_argument(
        "--ws-url",
        default=os.getenv("SMART_INSOLE_WS_URL"),
        help="STOMP WebSocket URL; defaults to <base-url>/ws (or SMART_INSOLE_WS_URL)",
    )
    parser.add_argument(
        "--ws-origin",
        default=os.getenv("SMART_INSOLE_WS_ORIGIN", "http://localhost:5173"),
        help="WebSocket Origin allowed by the backend (or SMART_INSOLE_WS_ORIGIN)",
    )
    parser.add_argument("--receiver-key", default=os.getenv("SMART_INSOLE_RECEIVER_KEY"))
    parser.add_argument("--run-id", default=os.getenv("SMART_INSOLE_E2E_RUN_ID", default_run_id))
    parser.add_argument("--email", default=os.getenv("SMART_INSOLE_E2E_EMAIL"))
    parser.add_argument("--password", default=os.getenv("SMART_INSOLE_E2E_PASSWORD"))
    parser.add_argument("--name", default=os.getenv("SMART_INSOLE_E2E_NAME", "Codex Smoke User"))
    parser.add_argument(
        "--fixture",
        type=Path,
        default=REPOSITORY_ROOT / "fixtures" / "frame-batch-normal.json",
    )
    parser.add_argument(
        "--sensor-layout-version",
        help="override the seeded layout; defaults to layout-v1 for 8 sensors and layout-v1-6 for 6",
    )
    parser.add_argument("--result-timeout-seconds", type=positive_float, default=30.0)
    parser.add_argument("--poll-interval-seconds", type=positive_float, default=0.5)
    parser.add_argument("--request-timeout-seconds", type=positive_float, default=15.0)
    parser.add_argument("--stomp-timeout-seconds", type=positive_float, default=5.0)
    parser.add_argument(
        "--foot-disconnect-timeout-seconds",
        type=positive_float,
        default=10.0,
        help="maximum time to wait for one-foot disconnect detection while the other foot continues",
    )
    return parser.parse_args()


def checked(
    result: HttpResult,
    expected: Iterable[int],
    label: str,
    *,
    require_object: bool = True,
) -> Any:
    expected_set = set(expected)
    if result.status not in expected_set:
        raise RuntimeError(f"{label}: {describe_error(result)}; expected {sorted(expected_set)}")
    if require_object and not isinstance(result.body, dict):
        raise RuntimeError(f"{label}: expected a JSON object, got {type(result.body).__name__}")
    print(f"[PASS] {label}: HTTP {result.status} ({result.elapsed_ms:.1f} ms)")
    return result.body


def call(
    args: argparse.Namespace,
    method: str,
    path: str,
    *,
    payload: Any = None,
    headers: dict[str, str] | None = None,
    attempts: int = 1,
) -> HttpResult:
    return request_json(
        method,
        join_url(args.base_url, path),
        payload=payload,
        headers=headers,
        timeout_seconds=args.request_timeout_seconds,
        max_attempts=attempts,
    )


def bearer(token: str) -> dict[str, str]:
    return {"Authorization": f"Bearer {token}"}


def register_or_find_device(
    args: argparse.Namespace,
    token: str,
    *,
    serial_number: str,
    display_name: str,
    foot_side: str,
    sensor_count: int,
) -> str:
    layout_version = args.sensor_layout_version or default_layout_version(sensor_count)
    payload = {
        "serialNumber": serial_number,
        "displayName": display_name,
        "footSide": foot_side,
        "sensorCount": sensor_count,
        "sensorLayoutVersion": layout_version,
        "firmwareVersion": "0.1.0-smoke",
    }
    result = call(args, "POST", "/api/v1/devices", payload=payload, headers=bearer(token))
    if result.status == 201:
        body = checked(result, {201}, f"register {foot_side} device")
        return required_uuid(body, "deviceId", f"register {foot_side} device")
    if result.status != 409:
        checked(result, {201}, f"register {foot_side} device")

    devices = checked(
        call(args, "GET", "/api/v1/devices", headers=bearer(token)),
        {200},
        f"find existing {foot_side} device",
        require_object=False,
    )
    if not isinstance(devices, list):
        raise RuntimeError("device list response must be an array")
    for device in devices:
        if isinstance(device, dict) and device.get("serialNumber") == serial_number:
            if (device.get("footSide") != foot_side
                    or device.get("sensorCount") != sensor_count
                    or device.get("sensorLayoutVersion") != layout_version):
                raise RuntimeError(f"existing device {serial_number} does not match the requested fixture")
            print(f"[PASS] reuse {foot_side} device after HTTP 409")
            return required_uuid(device, "deviceId", f"existing {foot_side} device")
    raise RuntimeError(f"device registration conflicted but serial {serial_number} is not owned by this user")


def required_uuid(body: dict[str, Any], field: str, label: str) -> str:
    value = body.get(field)
    try:
        return str(uuid.UUID(value))
    except (TypeError, ValueError) as error:
        raise RuntimeError(f"{label}: missing valid {field}") from error


def default_layout_version(sensor_count: int) -> str:
    if sensor_count == 6:
        return "layout-v1-6"
    if sensor_count == 8:
        return "layout-v1"
    raise RuntimeError(f"no seeded layout for {sensor_count} sensors")


def fixture_sensor_counts(fixture: dict[str, Any]) -> dict[str, int]:
    frames = fixture.get("frames")
    if not isinstance(frames, list) or not frames:
        raise RuntimeError("fixture must contain frames")
    counts: dict[str, set[int]] = {"LEFT": set(), "RIGHT": set()}
    for frame in frames:
        if not isinstance(frame, dict) or frame.get("footSide") not in counts:
            raise RuntimeError("fixture contains an invalid footSide")
        values = frame.get("sensorValues")
        if not isinstance(values, list):
            raise RuntimeError("fixture frame lacks sensorValues")
        counts[frame["footSide"]].add(len(values))
    if not counts["LEFT"] or not counts["RIGHT"]:
        raise RuntimeError("E2E fixture must contain both LEFT and RIGHT frames")
    if any(len(values) != 1 or next(iter(values)) not in {6, 8} for values in counts.values()):
        raise RuntimeError("each fixture side must consistently contain 6 or 8 sensors")
    return {side: next(iter(values)) for side, values in counts.items()}


def stomp_topic(session_id: str) -> str:
    return f"/topic/measurement-sessions/{session_id}/pressure"


def create_next_frame(
    submitted_frames: list[dict[str, Any]],
    side: str,
    device_id: str,
) -> dict[str, Any]:
    side_frames = [frame for frame in submitted_frames if frame.get("footSide") == side]
    if not side_frames:
        raise RuntimeError(f"cannot create a {side} probe without an earlier frame")
    source = max(
        side_frames,
        key=lambda frame: (int(frame["deviceTimeMs"]), int(frame["sequence"])),
    )
    values = list(source["sensorValues"])
    if values:
        values[0] = values[0] + 1 if values[0] < 65535 else values[0] - 1
    return {
        "deviceId": device_id,
        "footSide": side,
        "sequence": max(int(frame["sequence"]) for frame in side_frames) + 1,
        "deviceTimeMs": max(int(frame["deviceTimeMs"]) for frame in side_frames) + 10,
        "sensorValues": values,
    }


def ingest_frames(
    args: argparse.Namespace,
    session_id: str,
    receiver_id: str,
    frames: list[dict[str, Any]],
    label: str,
    *,
    schema_version: str = "1.0",
    require_all_accepted: bool = False,
) -> tuple[int, int, int]:
    body = checked(
        call(
            args,
            "POST",
            f"/internal/v1/measurement-sessions/{session_id}/frame-batches",
            payload={
                "schemaVersion": schema_version,
                "receiverId": receiver_id,
                "sentAt": utc_now(),
                "frames": frames,
            },
            headers={"X-Receiver-Key": args.receiver_key},
            attempts=3,
        ),
        {200},
        label,
    )
    counts = tuple(body.get(key) for key in ("acceptedCount", "duplicateCount", "rejectedCount"))
    if not all(isinstance(value, int) and not isinstance(value, bool) and value >= 0 for value in counts):
        raise RuntimeError(f"{label}: response lacks non-negative integer count fields")
    accepted, duplicates, rejected = counts
    if accepted + duplicates + rejected != len(frames):
        raise RuntimeError(f"{label}: response counts do not add up to submitted frames")
    if rejected:
        raise RuntimeError(f"{label}: server unexpectedly rejected {rejected} frame(s)")
    if require_all_accepted and (accepted != len(frames) or duplicates != 0):
        raise RuntimeError(
            f"{label}: fresh probe was not fully accepted "
            f"(accepted={accepted}, duplicate={duplicates})"
        )
    return accepted, duplicates, rejected


def secondary_user_token(args: argparse.Namespace, run_id: str, primary_email: str) -> str:
    email = f"codex-smoke-b+{run_id.lower()}@example.com"
    if email.casefold() == primary_email.casefold():
        email = f"codex-smoke-b-alt+{run_id.lower()}@example.com"
    password = f"Sm0ke-B-{run_id}-Aa!"
    signup = call(
        args,
        "POST",
        "/api/v1/auth/signup",
        payload={"email": email, "password": password, "name": "Codex Smoke User B"},
    )
    checked(signup, {201, 409}, "signup or existing secondary synthetic account")
    signin = checked(
        call(
            args,
            "POST",
            "/api/v1/auth/signin",
            payload={"email": email, "password": password},
        ),
        {200},
        "signin secondary user",
    )
    token = signin.get("accessToken")
    if not isinstance(token, str) or not token:
        raise RuntimeError("secondary signin response lacks accessToken")
    return token


def validate_realtime_schema(message: dict[str, Any]) -> None:
    schema = load_json(REALTIME_SCHEMA_PATH)
    validator = Draft202012Validator(schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(message), key=lambda error: list(error.absolute_path))
    if errors:
        rendered = "; ".join(
            f"{'/'.join(map(str, error.absolute_path)) or '<root>'}: {error.message}"
            for error in errors[:5]
        )
        raise RuntimeError(f"realtime message violates JSON Schema: {rendered}")


def receive_realtime_message(
    client: StompClient,
    session_id: str,
    timeout_seconds: float,
    label: str,
    *,
    announce: bool = True,
) -> dict[str, Any]:
    frame = client.receive_message(timeout_seconds)
    expected_destination = stomp_topic(session_id)
    if frame.headers.get("destination") != expected_destination:
        raise RuntimeError(
            f"{label}: STOMP message destination={frame.headers.get('destination')!r}, "
            f"expected {expected_destination!r}"
        )
    try:
        message = json.loads(frame.body)
    except json.JSONDecodeError as error:
        raise RuntimeError(f"{label}: STOMP message body is not valid JSON") from error
    if not isinstance(message, dict):
        raise RuntimeError(f"{label}: STOMP message body must be an object")
    validate_realtime_schema(message)
    if message.get("sessionId") != session_id or message.get("status") != "MEASURING":
        raise RuntimeError(f"{label}: STOMP message does not represent the active smoke session")
    if announce:
        print(f"[PASS] {label}: authenticated STOMP MESSAGE matches realtime JSON Schema")
    return message


def validate_realtime_snapshot(
    snapshot: dict[str, Any],
    session_id: str,
    sensor_counts: dict[str, int],
    submitted_frames: list[dict[str, Any]],
) -> None:
    validate_realtime_schema(snapshot)
    if snapshot.get("sessionId") != session_id or snapshot.get("status") != "MEASURING":
        raise RuntimeError("realtime snapshot does not represent the active smoke session")
    for side, field in (("LEFT", "left"), ("RIGHT", "right")):
        foot = snapshot.get(field)
        if not isinstance(foot, dict):
            raise RuntimeError(f"realtime snapshot lacks {side} foot data")
        values = foot.get("sensorValues")
        if not isinstance(values, list) or len(values) != sensor_counts[side]:
            raise RuntimeError(f"realtime snapshot {side} sensor count differs from the fixture")
        expected_sequence = max(
            frame["sequence"] for frame in submitted_frames if frame["footSide"] == side
        )
        if foot.get("lastSequence") != expected_sequence:
            raise RuntimeError(
                f"realtime snapshot {side} lastSequence={foot.get('lastSequence')!r}, "
                f"expected {expected_sequence}"
            )


def validate_right_disconnected(
    message: dict[str, Any],
    *,
    expected_left_sequence: int,
    expected_right_sequence: int,
) -> bool:
    left = message.get("left")
    right = message.get("right")
    quality = message.get("quality")
    if not isinstance(left, dict) or not isinstance(right, dict) or not isinstance(quality, dict):
        raise RuntimeError("one-foot timeout message lacks bilateral data or quality")
    if left.get("connected") is not True:
        raise RuntimeError("LEFT foot did not remain connected while fresh LEFT frames were ingested")
    if left.get("lastSequence") != expected_left_sequence:
        raise RuntimeError(
            f"LEFT lastSequence={left.get('lastSequence')!r}, expected {expected_left_sequence}"
        )
    if right.get("lastSequence") != expected_right_sequence:
        raise RuntimeError("RIGHT last known frame was not preserved during its timeout")
    flags = quality.get("flags")
    if not isinstance(flags, list):
        raise RuntimeError("one-foot timeout message lacks quality flags")
    if right.get("connected") is not False:
        return False
    if "RIGHT_DEVICE_DISCONNECTED" not in flags:
        raise RuntimeError("RIGHT disconnected state lacks RIGHT_DEVICE_DISCONNECTED quality flag")
    if "LEFT_DEVICE_DISCONNECTED" in flags:
        raise RuntimeError("LEFT_DEVICE_DISCONNECTED was raised despite continuing LEFT frames")
    return True


def validate_analysis_result(result: dict[str, Any]) -> None:
    with OPENAPI_PATH.open("r", encoding="utf-8") as handle:
        openapi = yaml.safe_load(handle)
    schema = dereference_schema(openapi["components"]["schemas"]["AnalysisResultResponse"], openapi)
    validator = Draft7Validator(schema, format_checker=FormatChecker())
    errors = sorted(validator.iter_errors(result), key=lambda error: list(error.absolute_path))
    if errors:
        rendered = "; ".join(
            f"{'/'.join(map(str, error.absolute_path)) or '<root>'}: {error.message}"
            for error in errors[:5]
        )
        raise RuntimeError(f"analysis result violates OpenAPI schema: {rendered}")
    if result.get("algorithmVersion") != "rule-v1.1.0":
        raise RuntimeError(
            f"analysis result version={result.get('algorithmVersion')!r}, expected rule-v1.1.0"
        )
    gait = result.get("gaitSummary")
    distribution = result.get("pressureDistribution")
    if not isinstance(gait, dict) or not isinstance(gait.get("validStepCount"), int):
        raise RuntimeError("rule-v1.1.0 result lacks a valid step count")
    required_metrics = (
        "leftMidfootRatio",
        "rightMidfootRatio",
        "leftForefootRatio",
        "rightForefootRatio",
        "leftPeakPressure",
        "rightPeakPressure",
        "leftMeanCoP",
        "rightMeanCoP",
    )
    if not isinstance(distribution, dict) or any(distribution.get(key) is None for key in required_metrics):
        raise RuntimeError("rule-v1.1.0 normal fixture result lacks expanded pressure metrics")


def main() -> int:
    args = parse_args()
    if not args.receiver_key:
        print("[FAIL] --receiver-key or SMART_INSOLE_RECEIVER_KEY is required.", file=sys.stderr)
        return 2
    run_id = re.sub(r"[^A-Za-z0-9-]", "-", args.run_id).strip("-")[:40]
    if not run_id:
        print("[FAIL] --run-id must contain a letter, digit, or hyphen.", file=sys.stderr)
        return 2
    email = args.email or f"codex-smoke+{run_id.lower()}@example.com"
    password = args.password or f"Sm0ke-{run_id}-Aa!"
    fixture_path = args.fixture.resolve()
    try:
        validate_http_base_url(args.base_url)
        ws_url = validate_websocket_url(args.ws_url or default_websocket_url(args.base_url))
        validate_websocket_origin(args.ws_origin)
        fixture = load_json(fixture_path)
        if not isinstance(fixture, dict):
            raise RuntimeError("fixture root must be an object")
        sensor_counts = fixture_sensor_counts(fixture)
    except (OSError, json.JSONDecodeError, RuntimeError, ValueError) as error:
        print(f"[FAIL] arguments or fixture: {error}", file=sys.stderr)
        return 2

    print(
        f"[E2E] baseUrl={public_http_endpoint(args.base_url)} "
        f"runId={run_id} fixture={fixture_path.name}"
    )
    print("[E2E] synthetic account and SIMULATED sensor data only; credentials are not displayed")

    stomp_client: StompClient | None = None
    try:
        signup = call(
            args,
            "POST",
            "/api/v1/auth/signup",
            payload={"email": email, "password": password, "name": args.name},
        )
        checked(signup, {201, 409}, "signup or existing synthetic account")

        signin = checked(
            call(
                args,
                "POST",
                "/api/v1/auth/signin",
                payload={"email": email, "password": password},
            ),
            {200},
            "signin",
        )
        token = signin.get("accessToken")
        if not isinstance(token, str) or not token:
            raise RuntimeError("signin response lacks accessToken")

        left_id = register_or_find_device(
            args,
            token,
            serial_number=f"SMOKE-L-{run_id}",
            display_name="Smoke LEFT",
            foot_side="LEFT",
            sensor_count=sensor_counts["LEFT"],
        )
        right_id = register_or_find_device(
            args,
            token,
            serial_number=f"SMOKE-R-{run_id}",
            display_name="Smoke RIGHT",
            foot_side="RIGHT",
            sensor_count=sensor_counts["RIGHT"],
        )

        session = checked(
            call(
                args,
                "POST",
                "/api/v1/measurement-sessions",
                payload={
                    "leftDeviceId": left_id,
                    "rightDeviceId": right_id,
                    "sampleRateHz": 100,
                    "memo": f"Synthetic API smoke {run_id}",
                },
                headers=bearer(token),
            ),
            {201},
            "create session",
        )
        session_id = required_uuid(session, "sessionId", "create session")

        checked(
            call(
                args,
                "POST",
                f"/api/v1/measurement-sessions/{session_id}/start",
                headers=bearer(token),
            ),
            {200},
            "start session",
        )

        topic = stomp_topic(session_id)
        stomp_client = StompClient.connect(
            ws_url,
            origin=args.ws_origin,
            token=token,
            timeout_seconds=args.stomp_timeout_seconds,
        )
        stomp_client.subscribe(topic, "owner-initial")
        print("[PASS] owner authenticated STOMP connection and session-topic subscription")

        secondary_token = secondary_user_token(args, run_id, email)
        secondary_client = StompClient.connect(
            ws_url,
            origin=args.ws_origin,
            token=secondary_token,
            timeout_seconds=args.stomp_timeout_seconds,
        )
        try:
            denial_mode = secondary_client.expect_subscription_denied(topic, "foreign-owner")
            print(f"[PASS] secondary user cannot subscribe to owner's topic ({denial_mode})")
        finally:
            secondary_client.close(send_disconnect=False)

        rewritten_frames: list[dict[str, Any]] = []
        for source in fixture["frames"]:
            frame = copy.deepcopy(source)
            frame["deviceId"] = left_id if frame["footSide"] == "LEFT" else right_id
            rewritten_frames.append(frame)
        receiver_id = fixture.get("receiverId", "E2E-SIMULATED")
        schema_version = fixture.get("schemaVersion", "1.0")
        if not isinstance(receiver_id, str) or not isinstance(schema_version, str):
            raise RuntimeError("fixture receiverId and schemaVersion must be strings")
        accepted, duplicates, rejected = ingest_frames(
            args,
            session_id,
            receiver_id,
            rewritten_frames,
            "ingest fixture",
            schema_version=schema_version,
        )

        initial_message = receive_realtime_message(
            stomp_client,
            session_id,
            args.stomp_timeout_seconds,
            "initial realtime publication",
        )
        validate_realtime_snapshot(initial_message, session_id, sensor_counts, rewritten_frames)
        print("[PASS] initial STOMP publication matches sensor counts and last sequences")

        snapshot = checked(
            call(
                args,
                "GET",
                f"/api/v1/measurement-sessions/{session_id}/realtime-snapshot",
                headers=bearer(token),
            ),
            {200},
            "bilateral realtime snapshot",
        )
        validate_realtime_snapshot(snapshot, session_id, sensor_counts, rewritten_frames)
        print("[PASS] realtime snapshot matches JSON Schema, sensor counts, and last sequences")

        stomp_client.close(send_disconnect=False)
        stomp_client = None
        print("[PASS] owner STOMP connection intentionally interrupted")
        stomp_client = StompClient.connect(
            ws_url,
            origin=args.ws_origin,
            token=token,
            timeout_seconds=args.stomp_timeout_seconds,
        )
        stomp_client.subscribe(topic, "owner-reconnected")
        time.sleep(0.12)
        recovery_frames = [
            create_next_frame(rewritten_frames, "LEFT", left_id),
            create_next_frame(rewritten_frames, "RIGHT", right_id),
        ]
        recovery_counts = ingest_frames(
            args,
            session_id,
            receiver_id,
            recovery_frames,
            "ingest fresh reconnect frames",
            schema_version=schema_version,
            require_all_accepted=True,
        )
        rewritten_frames.extend(recovery_frames)
        accepted += recovery_counts[0]
        duplicates += recovery_counts[1]
        rejected += recovery_counts[2]
        recovered_message = receive_realtime_message(
            stomp_client,
            session_id,
            args.stomp_timeout_seconds,
            "post-reconnect realtime publication",
        )
        validate_realtime_snapshot(recovered_message, session_id, sensor_counts, rewritten_frames)
        print("[PASS] reconnected subscription received a newly accepted bilateral frame")

        expected_right_sequence = max(
            int(frame["sequence"])
            for frame in rewritten_frames
            if frame["footSide"] == "RIGHT"
        )
        disconnect_deadline = time.monotonic() + args.foot_disconnect_timeout_seconds
        disconnected_message: dict[str, Any] | None = None
        while time.monotonic() < disconnect_deadline:
            time.sleep(min(0.5, max(0.0, disconnect_deadline - time.monotonic())))
            left_probe = create_next_frame(rewritten_frames, "LEFT", left_id)
            probe_counts = ingest_frames(
                args,
                session_id,
                receiver_id,
                [left_probe],
                "ingest LEFT keepalive probe",
                schema_version=schema_version,
                require_all_accepted=True,
            )
            rewritten_frames.append(left_probe)
            accepted += probe_counts[0]
            duplicates += probe_counts[1]
            rejected += probe_counts[2]
            remaining_seconds = disconnect_deadline - time.monotonic()
            if remaining_seconds <= 0:
                break
            probe_message = receive_realtime_message(
                stomp_client,
                session_id,
                min(args.stomp_timeout_seconds, remaining_seconds),
                "one-foot timeout publication",
                announce=False,
            )
            if validate_right_disconnected(
                probe_message,
                expected_left_sequence=int(left_probe["sequence"]),
                expected_right_sequence=expected_right_sequence,
            ):
                disconnected_message = probe_message
                break
        if disconnected_message is None:
            raise RuntimeError(
                "RIGHT foot did not become disconnected while LEFT remained active within "
                f"{args.foot_disconnect_timeout_seconds:.1f}s"
            )
        print(
            "[PASS] RIGHT timed out with RIGHT_DEVICE_DISCONNECTED while LEFT stayed connected"
        )

        timeout_snapshot = checked(
            call(
                args,
                "GET",
                f"/api/v1/measurement-sessions/{session_id}/realtime-snapshot",
                headers=bearer(token),
            ),
            {200},
            "one-foot timeout realtime snapshot",
        )
        if not validate_right_disconnected(
            timeout_snapshot,
            expected_left_sequence=max(
                int(frame["sequence"])
                for frame in rewritten_frames
                if frame["footSide"] == "LEFT"
            ),
            expected_right_sequence=expected_right_sequence,
        ):
            raise RuntimeError("REST snapshot did not retain the observed RIGHT disconnected state")
        print("[PASS] REST snapshot confirms one-foot disconnected state and retained values")

        completed = checked(
            call(
                args,
                "POST",
                f"/api/v1/measurement-sessions/{session_id}/complete",
                headers=bearer(token),
            ),
            {200},
            "complete session",
        )
        if completed.get("status") != "PROCESSING":
            raise RuntimeError(f"complete session returned status={completed.get('status')!r}, expected PROCESSING")

        deadline = time.monotonic() + args.result_timeout_seconds
        result_body: dict[str, Any] | None = None
        polls = 0
        while time.monotonic() < deadline:
            polls += 1
            result = call(
                args,
                "GET",
                f"/api/v1/measurement-sessions/{session_id}/result",
                headers=bearer(token),
            )
            if result.status == 200:
                result_body = checked(result, {200}, f"result completed after {polls} poll(s)")
                break
            if result.status != 202:
                checked(result, {200, 202}, "poll result")
            time.sleep(args.poll_interval_seconds)
        if result_body is None:
            raise RuntimeError(f"result did not complete within {args.result_timeout_seconds:.1f} seconds")
        if result_body.get("status") != "COMPLETED" or not result_body.get("algorithmVersion"):
            raise RuntimeError("completed result lacks status COMPLETED or algorithmVersion")
        validate_analysis_result(result_body)
        print("[PASS] analysis result matches OpenAPI and rule-v1.1.0 feature contract")
        disclaimer = result_body.get("disclaimer")
        if not isinstance(disclaimer, str) or not disclaimer.strip():
            raise RuntimeError("completed result lacks disclaimer")

        guide = checked(
            call(
                args,
                "GET",
                "/api/v1/recommendations/ANKLE_STABILITY_BASIC",
                headers=bearer(token),
            ),
            {200},
            "recommendation detail",
        )
        if (
            not isinstance(guide.get("purpose"), str)
            or not isinstance(guide.get("instructions"), list)
            or not guide["instructions"]
            or not isinstance(guide.get("cautionText"), str)
            or not isinstance(guide.get("relatedPatternCodes"), list)
        ):
            raise RuntimeError("recommendation detail lacks guide fields")

        history_parameters: dict[str, Any] = {
            "page": 0,
            "size": 100,
            "status": "COMPLETED",
            "from": "2020-01-01T00:00:00Z",
            "to": "2100-01-01T00:00:00Z",
            "minQualityScore": 0,
        }
        result_patterns = result_body.get("patterns")
        expected_primary_pattern: str | None = None
        if isinstance(result_patterns, list) and result_patterns and isinstance(result_patterns[0], dict):
            pattern_code = result_patterns[0].get("code")
            if isinstance(pattern_code, str) and pattern_code:
                history_parameters["patternCode"] = pattern_code
                expected_primary_pattern = pattern_code
        query = urlencode(history_parameters)
        history = checked(
            call(args, "GET", f"/api/v1/measurement-sessions?{query}", headers=bearer(token)),
            {200},
            "filtered history",
        )
        items = history.get("items")
        if not isinstance(items, list):
            raise RuntimeError("history response items must be an array")
        matching_item = next(
            (
                item
                for item in items
                if isinstance(item, dict) and item.get("sessionId") == session_id
            ),
            None,
        )
        if matching_item is None:
            raise RuntimeError("completed session is absent from history")
        if expected_primary_pattern is not None and matching_item.get("primaryPatternCode") != expected_primary_pattern:
            raise RuntimeError("history primaryPatternCode does not match the result's first pattern")

        print(
            f"[PASS] API E2E smoke complete: sessionId={session_id} "
            f"accepted={accepted} duplicate={duplicates} rejected={rejected}"
        )
        return 0
    except RuntimeError as error:
        print(f"[FAIL] API E2E smoke: {error}", file=sys.stderr)
        return 1
    finally:
        if stomp_client is not None:
            stomp_client.close()


if __name__ == "__main__":
    raise SystemExit(main())
