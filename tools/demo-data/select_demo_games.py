"""Select the BlindSpot demo games from a Lichess open-database month.

Reads one decompressed monthly PGN file, keeps finished standard rapid games,
analyses them in game-id order through the BlindSpot analysis service (the
Docker image, depth 16) and stops as soon as it has enough games with and
without a hanging-piece finding. No randomness: the same input file and the
same analysis service give the same selection.

Writes the games for the fictional demo player as NDJSON in the Lichess
game-export format the backend importer reads, plus a JSON report.

See README.md in this folder for the exact steps.
"""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import json
import sys
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from pathlib import Path

import chess
import chess.pgn

DEMO_USER = "demo"

# Lichess speed from the estimated game duration: initial + 40 x increment,
# in seconds. Rapid is 8 to 25 minutes (https://lichess.org/faq#time-controls).
RAPID_MIN_SECONDS = 8 * 60
RAPID_MAX_SECONDS = 25 * 60  # exclusive

FINISHED_RESULTS = {"1-0", "0-1", "1/2-1/2"}
# "Abandoned" and "Rules infraction" games did not finish on the board or clock.
FINISHED_TERMINATIONS = {"Normal", "Time forfeit"}

# Short games make poor demo material; the analysis service rejects over 300.
MIN_PLIES = 20
MAX_PLIES = 300


@dataclass
class Candidate:
    game_id: str
    offset: int
    headers: dict[str, str]


def estimated_seconds(time_control: str) -> int | None:
    """'600+8' -> 920; None for correspondence ('-') or anything unparsable."""
    try:
        initial, increment = time_control.split("+")
        return int(initial) + 40 * int(increment)
    except ValueError:
        return None


def passes_header_filter(headers: chess.pgn.Headers) -> bool:
    if not headers.get("Event", "").startswith("Rated "):
        return False
    if headers.get("Variant", "Standard") != "Standard" or "FEN" in headers:
        return False
    if headers.get("Result") not in FINISHED_RESULTS:
        return False
    if headers.get("Termination") not in FINISHED_TERMINATIONS:
        return False
    seconds = estimated_seconds(headers.get("TimeControl", "-"))
    return seconds is not None and RAPID_MIN_SECONDS <= seconds < RAPID_MAX_SECONDS


def game_id(headers: chess.pgn.Headers) -> str:
    return headers["Site"].rstrip("/").rsplit("/", 1)[-1]


def scan(pgn_path: Path) -> tuple[int, list[Candidate]]:
    """Pass 1: headers only. Returns the number of games and the header-filtered candidates."""
    total = 0
    candidates: list[Candidate] = []
    with pgn_path.open(encoding="utf-8") as pgn:
        while True:
            offset = pgn.tell()
            headers = chess.pgn.read_headers(pgn)
            if headers is None:
                break
            total += 1
            if passes_header_filter(headers):
                candidates.append(Candidate(game_id(headers), offset, dict(headers)))
    return total, candidates


def read_game(pgn_path: Path, offset: int) -> chess.pgn.Game:
    with pgn_path.open(encoding="utf-8") as pgn:
        pgn.seek(offset)
        game = chess.pgn.read_game(pgn)
    if game is None or game.errors:
        raise ValueError(f"Unreadable game at offset {offset}: {game.errors if game else 'none'}")
    return game


def movetext(game: chess.pgn.Game) -> str:
    """Moves and result only: no comments, no variations, no headers."""
    exporter = chess.pgn.StringExporter(headers=False, comments=False, variations=False)
    return game.accept(exporter).strip()


def analyse(service_url: str, pgn: str, user_color: str) -> tuple[dict, float]:
    body = json.dumps({"pgn": pgn, "userColor": user_color}).encode()
    request = urllib.request.Request(f"{service_url}/analyze", body, {"Content-Type": "application/json"})
    started = time.monotonic()
    with urllib.request.urlopen(request, timeout=600) as response:
        result = json.load(response)
    return result, time.monotonic() - started


def status(game: chess.pgn.Game, headers: dict[str, str]) -> str:
    """The Lichess status name for a finished game."""
    if headers["Termination"] == "Time forfeit":
        return "outoftime"
    board = game.end().board()
    if board.is_checkmate():
        return "mate"
    if board.is_stalemate():
        return "stalemate"
    if headers["Result"] == "1/2-1/2":
        return "draw"
    return "resign"


def created_at_millis(headers: dict[str, str]) -> int:
    stamp = dt.datetime.strptime(f"{headers['UTCDate']} {headers['UTCTime']}", "%Y.%m.%d %H:%M:%S")
    return int(stamp.replace(tzinfo=dt.timezone.utc).timestamp() * 1000)


def rating(headers: dict[str, str], key: str) -> int | None:
    value = headers.get(key, "?")
    return int(value) if value.isdigit() else None


def export_record(candidate: Candidate, game: chess.pgn.Game, user_color: str, opponent: str, month: str) -> dict:
    """One NDJSON line in the Lichess game-export format (see LichessGame.java)."""
    h = candidate.headers
    white = DEMO_USER if user_color == "WHITE" else opponent
    black = opponent if user_color == "WHITE" else DEMO_USER
    initial, increment = (int(x) for x in h["TimeControl"].split("+"))
    pgn_headers = [
        ("Event", h["Event"]),
        ("Site", h["Site"]),
        ("Date", h["UTCDate"]),
        ("White", white),
        ("Black", black),
        ("Result", h["Result"]),
        ("GameId", candidate.game_id),
        ("UTCDate", h["UTCDate"]),
        ("UTCTime", h["UTCTime"]),
        ("WhiteElo", h.get("WhiteElo", "?")),
        ("BlackElo", h.get("BlackElo", "?")),
        ("Variant", "Standard"),
        ("TimeControl", h["TimeControl"]),
        ("ECO", h.get("ECO", "?")),
        ("Opening", h.get("Opening", "?")),
        ("Termination", h["Termination"]),
    ]
    pgn = "".join(f'[{k} "{v}"]\n' for k, v in pgn_headers) + "\n" + movetext(game) + "\n"
    record = {
        "id": candidate.game_id,
        "source": f"lichess-open-database:{month}",
        "rated": True,
        "variant": "standard",
        "speed": "rapid",
        "perf": "rapid",
        "createdAt": created_at_millis(h),
        "status": status(game, h),
        "players": {
            "white": {"user": {"id": white, "name": white}, "rating": rating(h, "WhiteElo")},
            "black": {"user": {"id": black, "name": black}, "rating": rating(h, "BlackElo")},
        },
        "opening": {"eco": h.get("ECO"), "name": h.get("Opening")},
        "clock": {"initial": initial, "increment": increment},
        "pgn": pgn,
    }
    if h["Result"] == "1-0":
        record["winner"] = "white"
    elif h["Result"] == "0-1":
        record["winner"] = "black"
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--pgn", type=Path, required=True, help="decompressed monthly PGN file")
    parser.add_argument("--month", required=True, help="the file's month, e.g. 2013-01")
    parser.add_argument("--service-url", default="http://localhost:8000")
    parser.add_argument("--with-finding", type=int, default=7, help="games with a HANGING_PIECE finding")
    parser.add_argument("--without-finding", type=int, default=13, help="games without any finding")
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--report", type=Path, required=True)
    args = parser.parse_args()

    started = time.monotonic()
    total, candidates = scan(args.pgn)
    candidates.sort(key=lambda c: c.game_id)
    print(f"{total} games, {len(candidates)} finished rated standard rapid games by headers", flush=True)

    # The user's colour alternates along the sorted candidates: even index White, odd Black.
    selected: list[tuple[Candidate, chess.pgn.Game, str, dict]] = []
    analysed: list[dict] = []
    rejected_plies = 0
    with_finding = without_finding = 0
    engine = None
    for index, candidate in enumerate(candidates):
        if with_finding >= args.with_finding and without_finding >= args.without_finding:
            break
        game = read_game(args.pgn, candidate.offset)
        plies = sum(1 for _ in game.mainline_moves())
        if not MIN_PLIES <= plies <= MAX_PLIES:
            rejected_plies += 1
            continue
        user_color = "WHITE" if index % 2 == 0 else "BLACK"
        try:
            result, seconds = analyse(args.service_url, movetext(game), user_color)
        except urllib.error.HTTPError as e:
            print(f"  {candidate.game_id}: analysis service answered {e.code}, skipped", flush=True)
            analysed.append({"id": candidate.game_id, "userColor": user_color, "plies": plies,
                             "error": e.code})
            continue
        engine = result["engine"]
        hanging = [f for f in result["findings"] if f["motif"] == "HANGING_PIECE"]
        if hanging:
            take = with_finding < args.with_finding
            with_finding += take
        elif not result["findings"]:
            take = without_finding < args.without_finding
            without_finding += take
        else:
            take = False
        entry = {"id": candidate.game_id, "userColor": user_color, "plies": plies,
                 "seconds": round(seconds, 1), "hangingPiecePlies": [f["ply"] for f in hanging],
                 "findings": len(result["findings"]), "selected": take}
        analysed.append(entry)
        print(f"  {candidate.game_id} {user_color:5} {plies:3} plies {seconds:5.1f}s "
              f"hanging={entry['hangingPiecePlies']} {'SELECTED' if take else ''}", flush=True)
        if take:
            selected.append((candidate, game, user_color, entry))

    if with_finding < args.with_finding or without_finding < args.without_finding:
        print("Not enough games in this month for the requested counts", file=sys.stderr)
        return 1

    selected.sort(key=lambda s: s[0].game_id)
    lines = []
    for number, (candidate, game, user_color, _) in enumerate(selected, start=1):
        record = export_record(candidate, game, user_color, f"opponent_{number:02d}", args.month)
        lines.append(json.dumps(record, ensure_ascii=False, separators=(",", ":")))
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text("\n".join(lines) + "\n", encoding="utf-8")

    elapsed = time.monotonic() - started
    report = {
        "month": args.month,
        "source": f"https://database.lichess.org/standard/lichess_db_standard_rated_{args.month}.pgn.zst",
        "pgnSha256": hashlib.sha256(args.pgn.read_bytes()).hexdigest(),
        "engine": engine,
        "rules": {
            "headers": "Rated, standard, Result 1-0/0-1/1/2-1/2, Termination Normal or Time forfeit",
            "rapid": f"{RAPID_MIN_SECONDS} <= initial + 40 x increment < {RAPID_MAX_SECONDS} seconds",
            "plies": f"{MIN_PLIES} to {MAX_PLIES}",
            "order": "by Lichess game id; user colour alternates White/Black by index in that order",
            "quota": {"withHangingPiece": args.with_finding, "withoutAnyFinding": args.without_finding},
        },
        "counts": {
            "gamesInFile": total,
            "passedHeaderFilter": len(candidates),
            "skippedForLength": rejected_plies,
            "analysed": len(analysed),
            "analysedWithHangingPiece": sum(1 for a in analysed if a.get("hangingPiecePlies")),
            "analysedWithoutFinding": sum(1 for a in analysed if a.get("findings") == 0),
            "selected": len(selected),
        },
        "secondsTotal": round(elapsed, 1),
        "secondsAnalysing": round(sum(a.get("seconds", 0) for a in analysed), 1),
        "analysed": analysed,
    }
    args.report.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report["counts"]), f"in {elapsed:.0f} s", flush=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
