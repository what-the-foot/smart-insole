"""Small standard-library HTTP helpers shared by the integration CLIs."""

from __future__ import annotations

import json
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping


JSON = dict[str, Any] | list[Any] | str | int | float | bool | None
TRANSIENT_STATUSES = frozenset({408, 425, 429, 500, 502, 503, 504})


@dataclass(frozen=True)
class HttpResult:
    status: int
    body: JSON
    elapsed_ms: float
    attempts: int


class TransportFailure(RuntimeError):
    """Raised when a request never receives an HTTP response."""


def load_json(path: Path) -> JSON:
    with path.open("r", encoding="utf-8") as handle:
        return json.load(handle)


def join_url(base_url: str, path: str) -> str:
    return f"{base_url.rstrip('/')}/{path.lstrip('/')}"


def request_json(
    method: str,
    url: str,
    *,
    payload: JSON = None,
    headers: Mapping[str, str] | None = None,
    timeout_seconds: float = 15.0,
    max_attempts: int = 1,
    retry_backoff_seconds: float = 0.25,
    retry_statuses: frozenset[int] = TRANSIENT_STATUSES,
) -> HttpResult:
    """Send JSON and return all HTTP statuses; retry only transport/transient failures."""

    if max_attempts < 1:
        raise ValueError("max_attempts must be at least 1")

    request_headers = {"Accept": "application/json", **(headers or {})}
    body_bytes: bytes | None = None
    if payload is not None:
        body_bytes = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode(
            "utf-8"
        )
        request_headers.setdefault("Content-Type", "application/json")

    last_error: BaseException | None = None
    started_all = time.perf_counter()
    for attempt in range(1, max_attempts + 1):
        request = urllib.request.Request(
            url=url,
            data=body_bytes,
            headers=request_headers,
            method=method.upper(),
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout_seconds) as response:
                raw = response.read()
                result = HttpResult(
                    status=response.status,
                    body=_decode_body(raw, response.headers.get_content_charset()),
                    elapsed_ms=(time.perf_counter() - started_all) * 1000.0,
                    attempts=attempt,
                )
        except urllib.error.HTTPError as error:
            raw = error.read()
            result = HttpResult(
                status=error.code,
                body=_decode_body(raw, error.headers.get_content_charset()),
                elapsed_ms=(time.perf_counter() - started_all) * 1000.0,
                attempts=attempt,
            )
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last_error = error
            if attempt == max_attempts:
                break
            time.sleep(retry_backoff_seconds * (2 ** (attempt - 1)))
            continue

        if result.status not in retry_statuses or attempt == max_attempts:
            return result
        time.sleep(retry_backoff_seconds * (2 ** (attempt - 1)))

    raise TransportFailure(
        f"request failed after {max_attempts} attempt(s): {type(last_error).__name__}: {last_error}"
    )


def describe_error(result: HttpResult) -> str:
    if isinstance(result.body, dict):
        code = result.body.get("code")
        message = result.body.get("message")
        if code or message:
            return f"HTTP {result.status} code={code!s} message={message!s}"
    return f"HTTP {result.status}"


def _decode_body(raw: bytes, declared_charset: str | None) -> JSON:
    if not raw:
        return None
    text = raw.decode(declared_charset or "utf-8", errors="replace")
    try:
        return json.loads(text)
    except json.JSONDecodeError:
        return text
