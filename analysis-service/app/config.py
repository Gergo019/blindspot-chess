"""Engine settings, read from environment variables.

Reproducibility (product rule 4): Stockfish runs single-threaded with a fixed
hash size and a fixed search depth. Multi-threaded search is not deterministic,
so ENGINE_THREADS should stay at 1 unless reproducibility is given up on purpose.
"""

import os
import shutil
from dataclasses import dataclass
from functools import lru_cache

DEFAULT_DEPTH = 16
DEFAULT_THREADS = 1
DEFAULT_HASH_MB = 64
# Longest game accepted by POST /analyze, in half-moves.
MAX_PLIES = 300


def _positive_int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    if not raw:
        return default
    try:
        value = int(raw)
    except ValueError as exc:
        raise ValueError(f"{name} must be a positive integer, got {raw!r}") from exc
    if value < 1:
        raise ValueError(f"{name} must be a positive integer, got {value}")
    return value


@dataclass(frozen=True)
class Settings:
    stockfish_path: str
    depth: int
    threads: int
    hash_mb: int
    max_plies: int = MAX_PLIES

    @classmethod
    def from_env(cls) -> "Settings":
        path = os.environ.get("STOCKFISH_PATH", "").strip() or shutil.which("stockfish") or "stockfish"
        return cls(
            stockfish_path=path,
            depth=_positive_int("ENGINE_DEPTH", DEFAULT_DEPTH),
            threads=_positive_int("ENGINE_THREADS", DEFAULT_THREADS),
            hash_mb=_positive_int("ENGINE_HASH_MB", DEFAULT_HASH_MB),
        )


@lru_cache
def get_settings() -> Settings:
    return Settings.from_env()
