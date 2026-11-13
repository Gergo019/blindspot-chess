# 0002: Chess computation in Python with python-chess and Stockfish

- Status: accepted
- Date: 2026-11-12, updated 2026-11-13 (pinned Stockfish binary, reproducibility settings, classification)

## Context

The coach needs legal move generation, PGN parsing, UCI engine control, motif detection (for example "does this move allow a knight fork?") and a verifier that replays lines to check claims in explanations. It also needs LLM calls and evaluation scripts.

Every stored evaluation must be reproducible (product rule 4). A Stockfish installed from a Linux distribution's package repository changes with the base image, and multi-threaded search gives different results from run to run.

## Decision

- Use [python-chess](https://python-chess.readthedocs.io/) for chess logic and to drive [Stockfish](https://stockfishchess.org/) over UCI, in a FastAPI service.
- **Pinned engine:** the service's Docker image contains one official release binary from [github.com/official-stockfish/Stockfish/releases](https://github.com/official-stockfish/Stockfish/releases), currently **Stockfish 19**, the `stockfish-linux-x86-64-universal` build. The Dockerfile pins the version and verifies the SHA-256 checksum published with the release. Since Stockfish 19 the official Linux binaries are "universal": one binary detects the CPU's features at runtime, so there is no separate AVX-specific build to avoid, and it runs on any x86-64 CI runner. The matching `linux-arm64-universal` binary, also checksum-pinned, is used only when the image is built on Apple Silicon.
- **Fixed search:** `Threads=1`, `Hash=64` MB and a fixed depth (default 16), configurable through `ENGINE_THREADS`, `ENGINE_HASH_MB` and `ENGINE_DEPTH`. One engine process per request, and every position is searched after `ucinewgame`, which clears the hash and history tables. Every response, and every stored evaluation, records engine name, version and depth.
- **Tests use the same engine:** CI builds the image's `test` stage and runs pytest inside it; no distribution package of Stockfish is installed on the runner.
- **Classification by winning chances** with the Lichess formula and thresholds (drop of 0.1 / 0.2 / 0.3 for inaccuracy / mistake / blunder), so already-won or already-lost positions are not flagged. Sources are linked in `analysis-service/app/classify.py`.

## Consequences

- Mature, well-tested chess primitives instead of writing move generation by hand.
- LLM tooling and evaluation code live in the same language as the verifier.
- Engine analysis is CPU-bound: the backend sends bounded batches and processes them in the background, never on a request thread.
- Results are reproducible for a given engine version, depth and hash size. In a check during #2, the same game analysed with the x86-64 and the arm64 binaries gave identical output.
- Upgrading Stockfish is a deliberate change: new version and checksums in the Dockerfile, and stored evaluations from the old version are either kept with their recorded version or re-analysed.
- Single-threaded search at depth 16 is slower than using all cores; acceptable for background analysis of one game at a time.
- Stockfish is GPL-3.0. Running it as a separate program keeps this project's own code under MIT; the image ships Stockfish's licence next to the binary.
