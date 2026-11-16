"""Regenerate the golden /analyze outputs. Run only on purpose, never in CI.

The golden files pin the full output of POST /analyze for fixed games: every
evaluation, classification and finding. test_golden.py compares the live
output with them exactly. Regenerating them is a deliberate change: the PR
that does it adds a devlog entry explaining why the output changed (a new
Stockfish, other engine settings, a new detector version), as CONTRIBUTING.md
requires.

Inside the test image, from analysis-service/:

    docker build --target test -t analysis-test .
    docker run --rm -u root -v "$PWD/tests/golden:/app/tests/golden" analysis-test \\
        python -m tests.golden.regenerate --write

Without --write it only reports which files would change. Each file keeps its
own request; --from-demo PATH ID... creates files for demo games (Lichess
export NDJSON, as in backend/src/main/resources/demo/games.ndjson).
"""

import argparse
import json
import sys
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app

GOLDEN = Path(__file__).parent


def demo_request(path: Path, game_id: str) -> dict:
    for line in path.read_text(encoding="utf-8").splitlines():
        game = json.loads(line)
        if game["id"] == game_id:
            color = "WHITE" if game["players"]["white"]["user"]["id"] == "demo" else "BLACK"
            return {"pgn": game["pgn"], "userColor": color}
    raise SystemExit(f"{game_id} is not in {path}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--write", action="store_true", help="write the files (default: only report)")
    parser.add_argument("--from-demo", nargs="+", metavar=("NDJSON", "ID"), help="create files for demo games")
    args = parser.parse_args()

    requests = {f.stem: json.loads(f.read_text())["request"] for f in sorted(GOLDEN.glob("*.json"))}
    if args.from_demo:
        path, *ids = args.from_demo
        for game_id in ids:
            requests[game_id] = demo_request(Path(path), game_id)

    client = TestClient(app)
    changed = 0
    for name, request in requests.items():
        response = client.post("/analyze", json=request)
        if response.status_code != 200:
            raise SystemExit(f"{name}: /analyze answered {response.status_code}: {response.text}")
        content = json.dumps({"request": request, "response": response.json()}, indent=1, sort_keys=True) + "\n"
        target = GOLDEN / f"{name}.json"
        if target.exists() and target.read_text() == content:
            print(f"{name}: unchanged")
            continue
        changed += 1
        print(f"{name}: {'written' if args.write else 'would change'}")
        if args.write:
            target.write_text(content)
    return 0 if args.write or changed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
