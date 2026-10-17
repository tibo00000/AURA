# Offline derivative of the reviewed production-Dockerfile image.
# The preparation script must verify BASE_IMAGE's ID before and after the build.
ARG BASE_IMAGE=aura-security-lot1:review-20261016-py312-amd64
FROM ${BASE_IMAGE}
COPY server/app /app/app
COPY server/scripts /app/scripts
COPY server/.env.example /app/.env.example
CMD ["uvicorn", "app.core.operational_logging:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--log-config", "/app/app/core/security-logging.json"]
