"""Admission precedes multipart parsing, including spooling to disk."""
import inspect
import errno
import tempfile

from fastapi import HTTPException
from fastapi.routing import APIRoute
from starlette.formparsers import MultiPartException, MultiPartParser

from app.core.auth import get_current_user
from app.services.download_lifecycle import blocking
from app.services.storage_safety import MIB, StorageLimit, budget


class LimitedMultipartParser(MultiPartParser):
    def __init__(self, request, guard, token, spool):
        self.guard, self.token, self.spool = guard, token, spool
        self.body_bytes = self.file_bytes = self.part_bytes = self.header_bytes = 0
        super().__init__(request.headers, self.stream(request), max_files=1,
                         max_fields=8, max_part_size=64 * 1024)

    async def stream(self, request):
        async for chunk in request.stream():
            self.body_bytes += len(chunk)
            if self.body_bytes > self.guard.max_file + MIB:
                raise StorageLimit(413, 'upload_body_too_large')
            await blocking(self.guard.check)
            yield chunk
            # Parser has finished writing this chunk before asking for the next one.
            self.guard.allocated(self.token, self.file_bytes)

    def on_part_begin(self):
        self.part_bytes = self.header_bytes = 0
        super().on_part_begin()

    def on_header_field(self, data, start, end):
        self.header_bytes += end - start
        if self.header_bytes > 16 * 1024:
            raise StorageLimit(413, 'upload_headers_too_large')
        super().on_header_field(data, start, end)

    def on_header_value(self, data, start, end):
        self.header_bytes += end - start
        if self.header_bytes > 16 * 1024:
            raise StorageLimit(413, 'upload_headers_too_large')
        super().on_header_value(data, start, end)

    def on_headers_finished(self):
        super().on_headers_finished()
        if self._current_part.file is not None:
            # Replace the empty default spool before any byte is written. Never use root /tmp.
            old = self._current_part.file.file
            private = tempfile.SpooledTemporaryFile(max_size=MIB, dir=self.spool)
            old.close()
            self._current_part.file.file = private
            self._files_to_close_on_error[-1] = private

    def on_part_data(self, data, start, end):
        if self._current_part.file is not None:
            self.part_bytes += end - start
            self.file_bytes += end - start
            self.guard.received(self.token.owner, end - start)
            if self.part_bytes > self.guard.max_file:
                raise StorageLimit(413, 'upload_file_too_large')
        super().on_part_data(data, start, end)

    async def parse(self):
        try:
            return await super().parse()
        except BaseException:
            # Also safe with older Starlette: cancellation/disconnect closes every spool.
            for stream in self._files_to_close_on_error:
                stream.close()
            raise


class UploadAdmissionRoute(APIRoute):
    def get_route_handler(self):
        original = super().get_route_handler()
        if not self.path.endswith('/me/sync/files/{track_id}') or 'POST' not in self.methods:
            return original

        async def admitted(request):
            # Respect server-side FastAPI overrides used by isolated unit tests, never HTTP input.
            overrides = getattr(self.dependency_overrides_provider, 'dependency_overrides', {})
            override = overrides.get(get_current_user)
            if override:
                user = override()
                if inspect.isawaitable(user):
                    user = await user
            else:
                user = await get_current_user(authorization=request.headers.get('authorization'),
                                              token=request.query_params.get('token'))
            from app.api.routes.sync_files import _base_dir
            guard = None
            reservation = None
            form = None
            parser = None
            try:
                guard = await blocking(budget, _base_dir())
                reservation = await blocking(guard.acquire, user.id)
                request.state.upload_reservation = guard, reservation
                request.state.upload_owner = user.id
                spool = guard.root / '_upload_spool'
                def prepare():
                    if spool.is_symlink() or spool.is_junction():
                        raise StorageLimit(503, 'unsafe_storage')
                    spool.mkdir(mode=0o700, exist_ok=True)
                await blocking(prepare)
                if request.headers.get('content-type', '').partition(';')[0].strip().lower() != 'multipart/form-data':
                    raise HTTPException(400, 'Multipart audio required')
                parser = LimitedMultipartParser(request, guard, reservation, spool)
                form = await parser.parse()
                request._form = form  # Parse exactly once; FastAPI keeps its existing UploadFile contract.
                response = await original(request)
                warnings = guard.warnings(user.id)
                if warnings:
                    response.headers['X-Aura-Upload-Warnings'] = ','.join(warnings)
                return response
            except StorageLimit as error:
                raise HTTPException(error.status, error.code,
                                    headers={'Retry-After': '10'} if error.status == 429 else None) from None
            except MultiPartException:
                raise HTTPException(400, 'Invalid multipart audio') from None
            except OSError as error:
                if error.errno == errno.ENOSPC:
                    raise HTTPException(507, 'Upload storage unavailable') from None
                raise
            finally:
                try:
                    if parser is not None:
                        # An unfinished part may not appear in FormData at all.
                        await blocking(lambda: [stream.close() for stream in parser._files_to_close_on_error])
                finally:
                    if reservation is not None:
                        guard.release(reservation)
        return admitted
