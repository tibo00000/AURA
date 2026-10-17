"""Opt-in operational logging: emit structure, never request/exception free text."""

import contextvars
import json
import logging
import re
import traceback
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID, uuid4


request_id = contextvars.ContextVar("operational_request_id", default=None)
_symbol = re.compile(r"^[A-Za-z_][A-Za-z0-9_<>.]*$")


def _code_symbol(value):
    return value if isinstance(value, str) and _symbol.fullmatch(value) else "unknown"


class SafeFormatter(logging.Formatter):
    """Allowlist fields; don't evaluate getMessage(), repr(exc), extra or source lines."""

    def __init__(self):
        super().__init__()
        logging.captureWarnings(True)

    def format(self, record):
        try:
            return self._structured(record)
        except Exception:
            # logging.handleError would otherwise print the raw message/arguments.
            return '{"event":"logging_format_failed","level":"ERROR"}'

    def _structured(self, record):
        event = {
            "timestamp": datetime.fromtimestamp(record.created, timezone.utc).isoformat(),
            "level": logging.getLevelName(record.levelno),
            "event": "exception" if record.exc_info else "log",
            "source": {"module": _code_symbol(record.module), "function": _code_symbol(record.funcName), "line": record.lineno},
            "request_id": request_id.get(),
        }
        if record.name == "uvicorn.access":
            event["event"] = "http_access"
            args = record.args
            if isinstance(args, tuple) and len(args) == 5:
                _, method, target, _, status = args
                if method in ("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"):
                    event["method"] = method
                if isinstance(target, str):
                    event["path"] = target.partition("?")[0]
                if isinstance(status, int) and 100 <= status <= 599:
                    event["status"] = status
        if record.exc_info:
            kind, _, tb = record.exc_info
            event["error_type"] = _code_symbol(getattr(kind, "__name__", None))
            # extract_tb() can read source; walking the traceback accesses frames only.
            event["frames"] = [
                {"module": _code_symbol(Path(frame.f_code.co_filename).stem),
                 "function": _code_symbol(frame.f_code.co_name), "line": line}
                for frame, line in traceback.walk_tb(tb)
            ][-20:]
        return json.dumps(event, ensure_ascii=True, separators=(",", ":"))


class RequestContext:
    """ASGI pass-through: correlate logs without buffering or reading request bodies."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        identifier = None
        for name, value in scope.get("headers", ()):
            if name.lower() == b"x-request-id":
                try:
                    candidate = str(UUID(value.decode("ascii")))
                    if candidate == value.decode("ascii").lower():
                        identifier = candidate
                except (ValueError, UnicodeError):
                    pass
                break
        identifier = identifier or str(uuid4())
        context = request_id.set(identifier)

        async def response(message):
            if message["type"] == "http.response.start":
                headers = [(k, v) for k, v in message.get("headers", ()) if k.lower() != b"x-request-id"]
                message = {**message, "headers": [*headers, (b"x-request-id", identifier.encode("ascii"))]}
            await send(message)

        try:
            await self.app(scope, receive, response)
        except Exception:
            # Uvicorn logs again outside our context; retain one correlated error here.
            logging.getLogger(__name__).exception("request_failed")
            raise
        finally:
            request_id.reset(context)


def create_app():
    # Deferred until after Uvicorn dictConfig, including warnings during app imports.
    from app.main import app
    return RequestContext(app)
