import pytest
from fastapi.testclient import TestClient

from app.config import MAX_PLIES, Settings, get_settings
from app.main import app
from tests.games import FOOLS_MATE, knight_shuffle

client = TestClient(app)


def post(pgn: str, user_color: str = "WHITE"):
    return client.post("/analyze", json={"pgn": pgn, "userColor": user_color})


class TestRejectedGames:
    """Rejected before any engine starts, so these run without Stockfish."""

    def test_unfinished_game_is_422(self):
        response = post("1. e4 e5 2. Nf3 *")
        assert response.status_code == 422
        assert "completed games" in response.json()["detail"]

    def test_illegal_pgn_is_422(self):
        response = post("1. e4 e5 2. Qxe7 1-0")
        assert response.status_code == 422
        assert "not a legal game" in response.json()["detail"]

    def test_too_long_game_is_422(self):
        response = post(knight_shuffle(MAX_PLIES + 1))
        assert response.status_code == 422
        assert f"limit is {MAX_PLIES}" in response.json()["detail"]

    def test_unknown_user_color_is_422(self):
        assert post(FOOLS_MATE, user_color="RED").status_code == 422

    def test_missing_engine_is_503(self):
        app.dependency_overrides[get_settings] = lambda: Settings(
            stockfish_path="/nonexistent/stockfish", depth=16, threads=1, hash_mb=64
        )
        try:
            response = post(FOOLS_MATE)
        finally:
            app.dependency_overrides.clear()
        assert response.status_code == 503


@pytest.mark.engine
class TestAnalyze:
    def test_response_follows_the_contract(self):
        response = post(FOOLS_MATE, user_color="BLACK")
        assert response.status_code == 200
        body = response.json()
        assert body["engine"]["name"] == "Stockfish"
        assert set(body["engine"]) == {"name", "version", "depth"}
        assert len(body["moves"]) == 4
        first = body["moves"][0]
        assert set(first) == {
            "ply", "byUser", "fenBefore", "moveUci", "moveSan", "bestMoveUci",
            "scoreBefore", "scoreAfter", "classification",
        }
        assert first["ply"] == 1 and first["byUser"] is False and first["moveUci"] == "f2f3"
        for move in body["moves"]:
            assert len(move["scoreBefore"]) == 1 and len(move["scoreAfter"]) == 1
        assert body["moves"][-1]["scoreAfter"] == {"mate": 0}
        assert body["findings"] == []

    def test_findings_follow_the_contract(self):
        response = post("1. e4 d5 2. Qg4 Bxg4 0-1", user_color="WHITE")
        assert response.status_code == 200
        [finding] = response.json()["findings"]
        assert set(finding) == {"ply", "motif", "evidenceLineUci", "detectorVersion", "facts"}
        assert finding["ply"] == 3
        assert finding["motif"] == "HANGING_PIECE"
        assert finding["detectorVersion"] == "hanging-0.2"
        assert set(finding["facts"]) == {
            "piece", "square", "capturedBy", "materialLost", "movedPiece", "movedFrom", "movedTo",
        }
        assert finding["facts"]["piece"] == "queen" and finding["facts"]["capturedBy"] == "bishop"

    def test_health_reports_the_engine_version(self):
        body = client.get("/health").json()
        assert body["status"] == "UP"
        assert body["engine"] == "UP"
        assert body["engineName"] == "Stockfish"
        assert body["engineVersion"]
        assert body["engineDepth"] >= 1
