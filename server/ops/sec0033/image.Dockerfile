# Offline SEC-003 candidate; freeze_candidate.py checks the existing local base ID.
ARG BASE_IMAGE=aura-security-lot2:normalized-sec0024-amd64
FROM ${BASE_IMAGE}
ARG SOURCE_DATE_EPOCH
ARG SOURCE_MANIFEST_SHA256
LABEL org.opencontainers.image.title="AURA SEC-003.3 candidate" \
      org.opencontainers.image.base.name="sha256:3dedda8e3518d5f12ffc65f7765d92310afa46d83b63e006bbec61fdbd619bb2" \
      aura.source.manifest.sha256="${SOURCE_MANIFEST_SHA256}"
# Discard all inherited visible application and validation code, including bytecode.
RUN python -B -c "import shutil; [shutil.rmtree(p,ignore_errors=True) for p in ['/app/app','/app/scripts','/opt/aura-validation']]"
COPY runtime/app /app/app
COPY runtime/pyproject.toml runtime/README.md /app/
COPY validation /opt/aura-validation
COPY source-manifest.json /opt/aura-source-manifest.json
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 APP_DEBUG=false ENABLE_STARTUP_RECOVERY=false ENABLE_CACHE_BACKFILL=false
CMD ["uvicorn", "app.core.operational_logging:create_app", "--factory", "--workers", "1", "--host", "0.0.0.0", "--port", "8000", "--log-config", "/app/app/core/security-logging.json"]
