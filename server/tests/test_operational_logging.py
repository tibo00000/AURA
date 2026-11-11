import asyncio
import io
import json
import logging
import logging.config
import time
from pathlib import Path

import pytest

from app.core.operational_logging import RequestContext, SafeFormatter, request_id


MARKER = "SYNTHETIC_OPERATIONAL_TOKEN_MARKER"


@pytest.mark.parametrize("name", ["uvicorn.error", "app.provider", "httpx", "httpcore", "py.warnings"])
def test_free_text_args_extra_and_stack_are_not_emitted(name):
    record = logging.LogRecord(name, logging.ERROR, __file__, 20, "url=https://example.test/?token=%s", (MARKER,), None)
    record.secret = MARKER
    record.stack_info = MARKER
    output = SafeFormatter().format(record)
    assert MARKER not in output
    assert "token=" not in output
    assert json.loads(output)["level"] == "ERROR"


@pytest.mark.parametrize("target", [
    "/me/sync/files/fixture?token=" + MARKER,
    "/me/sync/files/fixture?token=Bearer%20" + MARKER,
    "/me/sync/files/fixture?%74oken=" + MARKER,
    "/me/sync/files/fixture?token=" + MARKER + "&token=" + MARKER,
])
def test_access_retains_path_method_status_without_query(target):
    record = logging.LogRecord("uvicorn.access", logging.INFO, __file__, 20, MARKER, (MARKER, "GET", target, "1.1", 206), None)
    data = json.loads(SafeFormatter().format(record))
    assert data["path"] == "/me/sync/files/fixture"
    assert data["method"] == "GET" and data["status"] == 206
    assert MARKER not in json.dumps(data)


@pytest.mark.parametrize("group", [False, True])
def test_exception_chain_group_and_source_lines_are_never_formatted(group):
    try:
        try:
            raise ValueError(MARKER)
        except ValueError as cause:
            if group:
                raise ExceptionGroup(MARKER, [cause]) from cause
            raise RuntimeError(MARKER) from cause
    except Exception:
        import sys
        record = logging.LogRecord("uvicorn.error", logging.ERROR, __file__, 50, MARKER, (), sys.exc_info())
    data = json.loads(SafeFormatter().format(record))
    assert data["error_type"] in ("RuntimeError", "ExceptionGroup")
    assert data["frames"] and data["frames"][-1]["line"] > 0
    assert MARKER not in json.dumps(data)


def test_formatter_failure_is_closed_without_logging_handle_error():
    record = logging.LogRecord("app", logging.ERROR, __file__, 1, MARKER, (), None)
    record.created = float("inf")
    assert json.loads(SafeFormatter().format(record))["event"] == "logging_format_failed"


@pytest.mark.parametrize("supplied", [None, b"aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", MARKER.encode()])
def test_asgi_preserves_streaming_and_correlates_and_cleans_context(supplied):
    sent = []
    seen = []

    async def original(scope, receive, send):
        seen.append(request_id.get())
        await send({"type": "http.response.start", "status": 206, "headers": [(b"content-range", b"bytes 0-1/2")]})
        await send({"type": "http.response.body", "body": b"a", "more_body": True})
        await send({"type": "http.response.body", "body": b"b", "more_body": False})

    async def send(message):
        sent.append(message)

    async def receive():
        return {"type": "http.request", "body": b""}

    scope = {"type": "http", "headers": [] if supplied is None else [(b"x-request-id", supplied)]}
    asyncio.run(RequestContext(original)(scope, receive, send))
    assert request_id.get() is None
    assert MARKER not in seen[0]
    if supplied and supplied != MARKER.encode():
        assert seen[0] == supplied.decode()
    assert sent[0]["status"] == 206
    assert (b"x-request-id", seen[0].encode()) in sent[0]["headers"]
    assert (b"content-range", b"bytes 0-1/2") in sent[0]["headers"]
    assert [s["body"] for s in sent[1:]] == [b"a", b"b"]
    assert sent[1]["more_body"] is True


def test_real_yt_dlp_warning_and_error_use_logging_not_raw_stderr():
    # Existing cache tests replace yt_dlp with a MagicMock at collection time.
    # A child interpreter exercises the installed library, never that mock.
    import subprocess
    import sys
    code = '''
import io,json,logging,yt_dlp
from app.core.operational_logging import SafeFormatter
output=io.StringIO()
logger=logging.Logger("synthetic.provider")
handler=logging.StreamHandler(output);handler.setFormatter(SafeFormatter());logger.addHandler(handler)
with yt_dlp.YoutubeDL({"quiet":True,"logger":logger,"ignoreerrors":True}) as downloader:
    downloader.report_warning("https://example.test/?token=SYNTHETIC_OPERATIONAL_TOKEN_MARKER")
    downloader.report_error("SYNTHETIC_OPERATIONAL_TOKEN_MARKER")
assert "SYNTHETIC_OPERATIONAL_TOKEN_MARKER" not in output.getvalue()
assert len(output.getvalue().splitlines()) >= 2
print(json.dumps({"safe":True}))
'''
    result = subprocess.run([sys.executable, "-B", "-c", code], capture_output=True, text=True, check=True)
    assert json.loads(result.stdout) == {"safe": True}
    assert MARKER not in result.stderr


def test_download_options_route_provider_output_through_logging(tmp_path):
    from app.services.download_service import _build_yt_dlp_opts
    opts = _build_yt_dlp_opts(tmp_path, "synthetic")
    assert isinstance(opts["logger"], logging.Logger)
