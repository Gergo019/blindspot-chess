package io.github.gergo019.blindspot.game;

import java.time.Instant;

import io.github.gergo019.blindspot.account.ChessAccount;
import io.github.gergo019.blindspot.common.BaseEntity;
import io.github.gergo019.blindspot.game.GameEnums.AnalysisStatus;
import io.github.gergo019.blindspot.game.GameEnums.GameResult;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;
import io.github.gergo019.blindspot.game.GameEnums.Speed;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A game imported from a chess platform, seen from the user's side of the board.
 */
@Entity
@Table(name = "games")
public class Game extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private ChessAccount account;

    @Column(name = "external_id", nullable = false, length = 100)
    private String externalId;

    @Column(name = "played_at", nullable = false)
    private Instant playedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Speed speed;

    @Column(name = "time_control", length = 20)
    private String timeControl;

    @Column(nullable = false)
    private boolean rated;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_color", nullable = false, length = 5)
    private PieceColor userColor;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 5)
    private GameResult result;

    @Column(name = "user_rating")
    private Integer userRating;

    @Column(name = "opponent_name", length = 100)
    private String opponentName;

    @Column(name = "opponent_rating")
    private Integer opponentRating;

    @Column(name = "opening_eco", length = 3)
    private String openingEco;

    @Column(name = "opening_name")
    private String openingName;

    @Column(nullable = false)
    private String pgn;

    @Enumerated(EnumType.STRING)
    @Column(name = "analysis_status", nullable = false, length = 20)
    private AnalysisStatus analysisStatus = AnalysisStatus.PENDING;

    protected Game() {
        // for JPA
    }

    public Game(ChessAccount account, String externalId, Instant playedAt, Speed speed, boolean rated,
            PieceColor userColor, GameResult result, String pgn) {
        this.account = account;
        this.externalId = externalId;
        this.playedAt = playedAt;
        this.speed = speed;
        this.rated = rated;
        this.userColor = userColor;
        this.result = result;
        this.pgn = pgn;
    }

    // --- analysis lifecycle: PENDING -> RUNNING -> DONE | FAILED, FAILED -> RUNNING (retry) ---

    public void startAnalysis() {
        if (analysisStatus != AnalysisStatus.PENDING && analysisStatus != AnalysisStatus.FAILED) {
            throw new IllegalStateException("Cannot start analysis from status " + analysisStatus);
        }
        analysisStatus = AnalysisStatus.RUNNING;
    }

    public void completeAnalysis() {
        requireRunning();
        analysisStatus = AnalysisStatus.DONE;
    }

    public void failAnalysis() {
        requireRunning();
        analysisStatus = AnalysisStatus.FAILED;
    }

    private void requireRunning() {
        if (analysisStatus != AnalysisStatus.RUNNING) {
            throw new IllegalStateException("Analysis is not running (status " + analysisStatus + ")");
        }
    }

    public void describeOpponent(String name, Integer rating) {
        this.opponentName = name;
        this.opponentRating = rating;
    }

    public void describeOpening(String eco, String name) {
        this.openingEco = eco;
        this.openingName = name;
    }

    public void setTimeControl(String timeControl) {
        this.timeControl = timeControl;
    }

    public void setUserRating(Integer userRating) {
        this.userRating = userRating;
    }

    public ChessAccount getAccount() {
        return account;
    }

    public String getExternalId() {
        return externalId;
    }

    public Instant getPlayedAt() {
        return playedAt;
    }

    public Speed getSpeed() {
        return speed;
    }

    public String getTimeControl() {
        return timeControl;
    }

    public boolean isRated() {
        return rated;
    }

    public PieceColor getUserColor() {
        return userColor;
    }

    public GameResult getResult() {
        return result;
    }

    public Integer getUserRating() {
        return userRating;
    }

    public String getOpponentName() {
        return opponentName;
    }

    public Integer getOpponentRating() {
        return opponentRating;
    }

    public String getOpeningEco() {
        return openingEco;
    }

    public String getOpeningName() {
        return openingName;
    }

    public String getPgn() {
        return pgn;
    }

    public AnalysisStatus getAnalysisStatus() {
        return analysisStatus;
    }
}
