# ---- Pinned Stockfish: one official release binary, checksum-verified ----
# Stockfish 19 ships "universal" binaries that detect the CPU's features at
# runtime, so the same binary runs on any x86-64 CI runner. CI builds for
# amd64; the arm64 binary only exists so the image also builds natively on
# Apple Silicon. Checksums are the SHA-256 values GitHub publishes for the
# release assets: https://github.com/official-stockfish/Stockfish/releases/tag/sf_19
FROM debian:bookworm-slim AS stockfish
ARG TARGETARCH
ARG STOCKFISH_VERSION=19
ARG STOCKFISH_SHA256_AMD64=9defc0d4e55d49c65a6d042f3e571a39fcea499ade6dbe741b53b8c65e03611f
ARG STOCKFISH_SHA256_ARM64=fe26cfd1d9db4c8af3d21e24d9ff34cacb31c1f940085a7583da11796f2bac01
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && rm -rf /var/lib/apt/lists/*
RUN set -eu; \
    case "${TARGETARCH:-amd64}" in \
      amd64) asset=stockfish-linux-x86-64-universal; sha="${STOCKFISH_SHA256_AMD64}" ;; \
      arm64) asset=stockfish-linux-arm64-universal; sha="${STOCKFISH_SHA256_ARM64}" ;; \
      *) echo "No pinned Stockfish build for ${TARGETARCH}" >&2; exit 1 ;; \
    esac; \
    curl -fsSL -o /tmp/stockfish.tar.gz \
      "https://github.com/official-stockfish/Stockfish/releases/download/sf_${STOCKFISH_VERSION}/${asset}.tar.gz"; \
    echo "${sha}  /tmp/stockfish.tar.gz" | sha256sum -c -; \
    tar -xzf /tmp/stockfish.tar.gz -C /tmp; \
    install -m 0755 "/tmp/stockfish/${asset}" /usr/local/bin/stockfish; \
    cp /tmp/stockfish/Copying.txt /usr/local/share/stockfish-COPYING.txt

# ---- Runtime base ----
FROM python:3.13-slim AS base
ARG STOCKFISH_VERSION=19
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    STOCKFISH_PATH=/usr/local/bin/stockfish \
    STOCKFISH_VERSION=${STOCKFISH_VERSION}
COPY --from=stockfish /usr/local/bin/stockfish /usr/local/bin/stockfish
# Stockfish is GPL-3.0; its licence travels with the binary.
COPY --from=stockfish /usr/local/share/stockfish-COPYING.txt /usr/local/share/doc/stockfish/COPYING.txt
WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app ./app
RUN useradd --system --uid 1001 app

# ---- Tests: same engine and dependencies as the runtime image ----
# CI: docker build --target test -t analysis-test . && docker run --rm analysis-test
FROM base AS test
COPY requirements-dev.txt pytest.ini ./
RUN pip install --no-cache-dir -r requirements-dev.txt
COPY tests ./tests
USER app
CMD ["python", "-m", "pytest", "-v", "-p", "no:cacheprovider"]

# ---- Runtime (default target) ----
FROM base AS runtime
USER app
EXPOSE 8000
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8000"]
