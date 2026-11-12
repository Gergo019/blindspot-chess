# 0002: Chess computation in Python with python-chess and Stockfish

- Status: accepted
- Date: 2026-11-12

## Context

The coach needs legal move generation, PGN parsing, UCI engine control, motif detection (for example "does this move allow a knight fork?") and a verifier that replays lines to check claims in explanations. It also needs LLM calls and evaluation scripts.

## Decision

- Use [python-chess](https://python-chess.readthedocs.io/) for chess logic and to drive [Stockfish](https://stockfishchess.org/) over UCI, in a FastAPI service.
- Pin the Stockfish version per deployment and use a fixed search depth. Every stored evaluation records engine version and depth.
- Run Stockfish as a separate process, installed in the service's Docker image.

## Consequences

- Mature, well-tested chess primitives instead of writing move generation by hand.
- LLM tooling and evaluation code live in the same language as the verifier.
- Engine analysis is CPU-bound: the backend sends bounded batches and processes them in the background, never on a request thread.
- Stockfish is GPL-3.0. Running it as a separate program keeps this project's own code under MIT.
