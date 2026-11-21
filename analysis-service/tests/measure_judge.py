"""Measure POST /judge on the demo positions: the time per drill that the budget is chosen from (#78).

Analyses the 20 demo games, takes every hanging-piece finding, and judges both drill positions of
each (ADR 0008): before the user's mistake ("Find a safe move") and after it ("See the capture").
It prints the time and the number of legal moves per position, the summary, and two checks per
finding: the mistake is rejected, and the opponent's capture is accepted. Not a test (it takes
minutes); run it on purpose, inside the test image, from analysis-service/:

    docker build --target test -t analysis-test .
    docker run --rm -v "$PWD/../backend/src/main/resources/demo:/demo:ro" analysis-test \\
        python -m tests.measure_judge /demo/games.ndjson

The budget is disabled while measuring, so every position is judged to the end.
"""

import dataclasses
import json
import statistics
import sys
import time
from pathlib import Path

import chess
from fastapi.testclient import TestClient

from app.config import Settings, get_settings
from app.main import app


def demo_requests(path: Path) -> list[tuple[str, dict]]:
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        game = json.loads(line)
        color = "WHITE" if game["players"]["white"]["user"]["id"] == "demo" else "BLACK"
        out.append((game["id"], {"pgn": game["pgn"], "userColor": color}))
    return out


def main() -> int:
    unlimited = dataclasses.replace(Settings.from_env(), judge_budget_seconds=1e9)
    app.dependency_overrides[get_settings] = lambda: unlimited
    client = TestClient(app)
    print(f"engine settings: depth {unlimited.depth}, threads {unlimited.threads}, hash {unlimited.hash_mb} MB")

    rows = []
    problems = 0
    for game_id, request in demo_requests(Path(sys.argv[1])):
        analysis = client.post("/analyze", json=request).json()
        for finding in analysis["findings"]:
            move = analysis["moves"][finding["ply"] - 1]
            before = chess.Board(move["fenBefore"])
            mistake = chess.Move.from_uci(move["moveUci"])
            capture = finding["evidenceLineUci"].split()[0]
            after = before.copy()
            after.push(mistake)
            for task, board, check_uci, want_accepted in [
                ("FIND_SAFE_MOVE", before, mistake.uci(), False),
                ("SEE_THE_CAPTURE", after, capture, True),
            ]:
                started = time.perf_counter()
                response = client.post("/judge", json={"fen": board.fen()})
                seconds = time.perf_counter() - started
                body = response.json()
                verdict = {v["uci"]: v for v in body["verdicts"]}[check_uci]
                ok = verdict["accepted"] == want_accepted
                problems += not ok
                accepted = sum(v["accepted"] for v in body["verdicts"])
                rows.append((seconds, len(body["verdicts"])))
                print(
                    f"{game_id} ply {finding['ply']:>3} {task:<15} {len(body['verdicts']):>3} moves "
                    f"{accepted:>3} accepted {seconds:6.2f} s  {check_uci} "
                    f"{'accepted' if verdict['accepted'] else 'rejected'} (drop {verdict['drop']}) "
                    f"{'ok' if ok else 'UNEXPECTED'}"
                )

    times = sorted(seconds for seconds, _ in rows)
    per_search = [seconds / moves for seconds, moves in rows]
    print(
        f"\n{len(rows)} positions: min {times[0]:.2f} s, median {statistics.median(times):.2f} s, "
        f"max {times[-1]:.2f} s; legal moves {min(m for _, m in rows)} to {max(m for _, m in rows)}; "
        f"per search: median {statistics.median(per_search):.3f} s, max {max(per_search):.3f} s"
    )
    print(f"unexpected verdicts: {problems}")
    return 1 if problems else 0


if __name__ == "__main__":
    sys.exit(main())
