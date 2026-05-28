FROM python:3.12-slim

ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

# ffmpeg is required by yt-dlp for audio extraction and conversion.
# curl and unzip are needed to install Deno (the JS runtime required by yt-dlp to bypass bot-checks).
RUN apt-get update && \
    apt-get install -y --no-install-recommends ffmpeg curl unzip && \
    curl -fsSL https://deno.land/install.sh | sh && \
    apt-get purge -y --auto-remove curl unzip && \
    rm -rf /var/lib/apt/lists/*

# Add Deno to PATH
ENV DENO_INSTALL="/root/.deno"
ENV PATH="$DENO_INSTALL/bin:$PATH"

WORKDIR /app

COPY server/pyproject.toml server/README.md ./
COPY server/app ./app
RUN pip install --no-cache-dir --upgrade pip && \
    pip install --no-cache-dir .

COPY server/.env.example ./.env.example

# Directory for downloaded audio files
RUN mkdir -p /app/downloads

EXPOSE 8000

CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
