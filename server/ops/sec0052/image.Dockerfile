ARG BASE_IMAGE=aura-security-lot3:sec0033-20261020-amd64
FROM ${BASE_IMAGE}
ARG SOURCE_DATE_EPOCH
ARG SOURCE_MANIFEST_SHA256
LABEL org.opencontainers.image.title="AURA SEC-005.2 local candidate" \
      org.opencontainers.image.base.digest="sha256:cc5fcd84e4c5c58ff095b9f6f290e34f95eddb0d26b5fa36f1998eee73fb60ec" \
      aura.source.manifest.sha256="${SOURCE_MANIFEST_SHA256}"
RUN python -B -c "import shutil; [shutil.rmtree(p,ignore_errors=True) for p in ['/app/app','/app/scripts','/opt/aura-validation']]"
COPY runtime/app /app/app
COPY runtime/pyproject.toml runtime/README.md /app/
COPY validation /opt/aura-validation
COPY source-manifest.json /opt/aura-source-manifest.json
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1 APP_DEBUG=false ENABLE_STARTUP_RECOVERY=false ENABLE_CACHE_BACKFILL=false
CMD ["uvicorn", "app.core.operational_logging:create_app", "--factory", "--workers", "1", "--host", "0.0.0.0", "--port", "8000", "--log-config", "/app/app/core/security-logging.json"]
