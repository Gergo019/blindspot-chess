# 0002: Chess computation in Python with python-chess and Stockfish

- Status: accepted
- Date: 2026-11-12, updated 2026-11-13 (pinned Stockfish binary, reproducibility settings, classification), 2026-11-16 (what reproducibility guarantees, golden files, #33)

## Context

The coach needs legal move generation, PGN parsing, UCI engine control, motif detection (for example "does this move allow a knight fork?") and a verifier that replays lines to check claims in explanations. It also needs LLM calls and evaluation scripts.

Every stored evaluation must be reproducible (product rule 4). A Stockfish installed from a Linux distribution's package repository changes with the base image, and multi-threaded search gives different results from run to run.

## Decision

- Use [python-chess](https://python-chess.readthedocs.io/) for chess logic and to drive [Stockfish](https://stockfishchess.org/) over UCI, in a FastAPI service.
- **Pinned engine:** the service's Docker image contains one official release binary from [github.com/official-stockfish/Stockfish/releases](https://github.com/official-stockfish/Stockfish/releases), currently **Stockfish 19**, the `stockfish-linux-x86-64-universal` build. The Dockerfile pins the version and verifies the SHA-256 checksum published with the release. Since Stockfish 19 the official Linux binaries are "universal": one binary detects the CPU's features at runtime, so there is no separate AVX-specific build to avoid, and it runs on any x86-64 CI runner. The matching `linux-arm64-universal` binary, also checksum-pinned, is used only when the image is built on Apple Silicon.
- **Fixed search:** `Threads=1`, `Hash=64` MB and a fixed depth (default 16), configurable through `ENGINE_THREADS`, `ENGINE_HASH_MB` and `ENGINE_DEPTH`. One engine process per request, and every position is searched after `ucinewgame`, which clears the hash and history tables. Every response, and every stored evaluation, records engine name, version, depth, threads and hash size (threads and hash size since #33, Flyway V4).
- **Pinned image:** both base images are pinned by digest (`tag@sha256:…`), and the Python dependencies are exact versions with hashes (`requirements.in` compiled by pip-tools with `--generate-hashes`, installed with `--require-hashes`). The image is therefore fixed by its Dockerfile and lock files; any change to it arrives as an explicit pull request.
- **Golden files:** `analysis-service/tests/golden/` holds the full `POST /analyze` output for two demo games (one with the user as White, one as Black, each with a hanging-piece finding). `test_golden.py` compares the live output with them exactly, on every CI run. They change only through `tests/golden/regenerate.py --write`, run on purpose, with a devlog entry explaining why the output changed.
- **Tests use the same engine:** CI builds the image's `test` stage and runs pytest inside it; no distribution package of Stockfish is installed on the runner.
- **Classification by winning chances** with the Lichess formula and thresholds (drop of 0.1 / 0.2 / 0.3 for inaccuracy / mistake / blunder), so already-won or already-lost positions are not flagged. Sources are linked in `analysis-service/app/classify.py`.

## What reproducibility guarantees

- **Guaranteed:** the same image (same base-image digests, same hashed dependencies, same checksum-pinned Stockfish binary) with the same engine settings (depth, threads, hash size) gives byte-identical `/analyze` output for the same request. The golden-file test checks this on every CI run (amd64), and it was checked on arm64 (Apple Silicon, the arm64 binary) when the files were created.
- **Not guaranteed:** a different hash size or thread count, a different depth, or a new Stockfish version changes evaluations, and with them classifications and findings. That is why every stored evaluation records the engine version, depth, threads and hash size: a result can always be explained, and re-analysed with the same settings. A new detector version (for example `hanging-0.4`) changes findings but not evaluations; findings record their detector version.
- **Also not guaranteed:** results from a hand-installed Stockfish, a non-official build, or an image built from a modified Dockerfile. Evaluations stored before V4 have no recorded threads or hash size (NULL); the service ran with 1 thread and 64 MB unless configured otherwise, but this cannot be proven per row.

## Consequences

- Mature, well-tested chess primitives instead of writing move generation by hand.
- LLM tooling and evaluation code live in the same language as the verifier.
- Engine analysis is CPU-bound: the backend sends bounded batches and processes them in the background, never on a request thread.
- Results are reproducible for a given image and engine settings (see above). In a check during #2, the same game analysed with the x86-64 and the arm64 binaries gave identical output; since #33 the golden files check this continuously.
- Any intended change of output (engine, settings, detector) must regenerate the golden files in the same PR, which makes it visible in review.
- Upgrading Stockfish is a deliberate change: new version and checksums in the Dockerfile, and stored evaluations from the old version are either kept with their recorded version or re-analysed.
- Single-threaded search at depth 16 is slower than using all cores; acceptable for background analysis of one game at a time.
- Stockfish is GPL-3.0. Running it as a separate program keeps this project's own code under MIT; the image ships Stockfish's licence next to the binary.
