"""Reproducibility: the full /analyze output for fixed games equals the checked-in golden files.

Same image (pinned base images, hashed dependencies, checksum-pinned Stockfish) and same engine
settings must give byte-identical output (ADR 0002). The files were generated on arm64 and are
compared in CI on amd64. Regenerate only on purpose (tests/golden/regenerate.py) and explain the
change in the devlog.
"""

import json
from pathlib import Path

import pytest
from fastapi.testclient import TestClient

from app.main import app

pytestmark = pytest.mark.engine

GOLDEN = sorted((Path(__file__).parent / "golden").glob("*.json"))
client = TestClient(app)


def test_there_are_golden_files_for_both_colours():
    colours = {json.loads(f.read_text())["request"]["userColor"] for f in GOLDEN}
    assert colours == {"WHITE", "BLACK"}


@pytest.mark.parametrize("golden", GOLDEN, ids=lambda f: f.stem)
def test_analysis_output_equals_the_golden_file(golden):
    expected = json.loads(golden.read_text())
    response = client.post("/analyze", json=expected["request"])
    assert response.status_code == 200
    actual = response.json()
    # Compare the engine settings first: a different hash size or version explains any other diff.
    assert actual["engine"] == expected["response"]["engine"]
    for got, want in zip(actual["moves"], expected["response"]["moves"], strict=True):
        assert got == want, f"ply {want['ply']} differs"
    assert actual["findings"] == expected["response"]["findings"]
    assert actual == expected["response"]
