from __future__ import annotations

import unittest

from e2e_smoke import (
    build_stomp_frame,
    default_websocket_url,
    parse_stomp_frames,
    public_http_endpoint,
)


class StompFrameParserTest(unittest.TestCase):
    def test_round_trip_escapes_headers(self) -> None:
        wire = build_stomp_frame(
            "SEND",
            {
                "destination": "/app/session:a",
                "x-note": "first line\nsecond:line\\tail",
            },
            "payload",
        )

        frames, remainder = parse_stomp_frames(wire)

        self.assertEqual("", remainder)
        self.assertEqual(1, len(frames))
        self.assertEqual("SEND", frames[0].command)
        self.assertEqual("/app/session:a", frames[0].headers["destination"])
        self.assertEqual("first line\nsecond:line\\tail", frames[0].headers["x-note"])
        self.assertEqual("payload", frames[0].body)

    def test_parses_heartbeats_multiple_frames_and_utf8_content_length(self) -> None:
        body = '{"text":"한글"}'
        wire = (
            "\n\r\nCONNECTED\nversion:1.2\n\n\x00"
            "MESSAGE\n"
            "subscription:owner\n"
            "destination:/topic/test\n"
            f"content-length:{len(body.encode('utf-8'))}\n\n"
            f"{body}\x00"
        )

        frames, remainder = parse_stomp_frames(wire)

        self.assertEqual("", remainder)
        self.assertEqual(["CONNECTED", "MESSAGE"], [frame.command for frame in frames])
        self.assertEqual(body, frames[1].body)

    def test_preserves_partial_frame_tail(self) -> None:
        complete = "CONNECTED\nversion:1.2\n\n\x00"
        partial = "MESSAGE\ndestination:/topic/test\n\n{\"partial\":"

        frames, remainder = parse_stomp_frames(complete + partial)

        self.assertEqual(1, len(frames))
        self.assertEqual(partial, remainder)

    def test_rejects_incorrect_content_length(self) -> None:
        wire = "MESSAGE\ncontent-length:99\n\n{}\x00"

        with self.assertRaisesRegex(ValueError, "content-length does not match"):
            parse_stomp_frames(wire)

    def test_rejects_invalid_header_escape(self) -> None:
        wire = "MESSAGE\nx-note:bad\\tvalue\n\n{}\x00"

        with self.assertRaisesRegex(ValueError, "invalid STOMP header escape"):
            parse_stomp_frames(wire)


class EndpointHelperTest(unittest.TestCase):
    def test_derives_websocket_endpoint_from_base_path(self) -> None:
        self.assertEqual(
            "wss://example.test/service/ws",
            default_websocket_url("https://example.test/service/"),
        )

    def test_public_endpoint_redacts_userinfo_query_and_fragment(self) -> None:
        self.assertEqual(
            "https://example.test:8443/service",
            public_http_endpoint(
                "https://user:secret@example.test:8443/service?access_token=secret#fragment"
            ),
        )


if __name__ == "__main__":
    unittest.main()
