# Offline candidate. freeze_candidate.py pins and verifies the locally archived base.
ARG BASE_IMAGE=aura-security-lot1:prepared-tests-20261017-amd64
FROM ${BASE_IMAGE}
ARG SOURCE_DATE_EPOCH
ARG SOURCE_MANIFEST_SHA256
LABEL org.opencontainers.image.title="AURA SEC-002.4 candidate" \
      org.opencontainers.image.base.name="sha256:5c541f103b62672bc5551aa093f840c878c7129797665ed63d2f0db5a874e16e" \
      aura.source.manifest.sha256="${SOURCE_MANIFEST_SHA256}"
# Remove the inherited application distribution and all visible old application sources.
RUN pip uninstall -y aura-server && python -c "import shutil,pathlib; [shutil.rmtree(p,ignore_errors=True) for p in ['/app/app','/app/scripts']]; [p.unlink() for p in pathlib.Path('/app').glob('.env*') if p.is_file()]"
COPY runtime/app /app/app
COPY runtime/pyproject.toml runtime/README.md /app/
COPY validation /opt/aura-validation
COPY source-manifest.json /opt/aura-source-manifest.json
WORKDIR /app
ENV PYTHONDONTWRITEBYTECODE=1 PYTHONUNBUFFERED=1
CMD ["uvicorn", "app.core.operational_logging:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--log-config", "/app/app/core/security-logging.json"]
