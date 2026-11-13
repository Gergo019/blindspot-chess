package io.github.gergo019.blindspot.analysis;

import io.github.gergo019.blindspot.common.BaseEntity;
import io.github.gergo019.blindspot.game.Game;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * The engine's verdict on one half-move (ply) of a game.
 */
@Entity
@Table(name = "move_evaluations")
public class MoveEvaluation extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "game_id", nullable = false)
    private Game game;

    @Column(nullable = false)
    private int ply;

    @Column(name = "by_user", nullable = false)
    private boolean byUser;

    @Column(name = "fen_before", nullable = false, length = 100)
    private String fenBefore;

    @Column(name = "move_uci", nullable = false, length = 5)
    private String moveUci;

    @Column(name = "move_san", nullable = false, length = 10)
    private String moveSan;

    @Column(name = "best_move_uci", nullable = false, length = 5)
    private String bestMoveUci;

    @Column(name = "eval_before_cp")
    private Integer evalBeforeCp;

    @Column(name = "mate_before")
    private Integer mateBefore;

    @Column(name = "eval_after_cp")
    private Integer evalAfterCp;

    @Column(name = "mate_after")
    private Integer mateAfter;

    @Column(name = "engine_version", nullable = false, length = 30)
    private String engineVersion;

    @Column(name = "engine_depth", nullable = false)
    private int engineDepth;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MoveClassification classification;

    protected MoveEvaluation() {
        // for JPA
    }

    public MoveEvaluation(Game game, int ply, boolean byUser, String fenBefore, String moveUci, String moveSan,
            String bestMoveUci, Score before, Score after, EngineRun engine, MoveClassification classification) {
        this.game = game;
        this.ply = ply;
        this.byUser = byUser;
        this.fenBefore = fenBefore;
        this.moveUci = moveUci;
        this.moveSan = moveSan;
        this.bestMoveUci = bestMoveUci;
        this.evalBeforeCp = before.centipawns();
        this.mateBefore = before.mateIn();
        this.evalAfterCp = after.centipawns();
        this.mateAfter = after.mateIn();
        this.engineVersion = engine.version();
        this.engineDepth = engine.depth();
        this.classification = classification;
    }

    /**
     * An engine score from White's point of view: either centipawns or a forced
     * mate in N moves (negative when Black mates), never both.
     */
    public record Score(Integer centipawns, Integer mateIn) {

        public Score {
            if ((centipawns == null) == (mateIn == null)) {
                throw new IllegalArgumentException("Exactly one of centipawns or mateIn must be set");
            }
        }

        public static Score cp(int centipawns) {
            return new Score(centipawns, null);
        }

        public static Score mate(int moves) {
            return new Score(null, moves);
        }
    }

    /** Which engine produced the evaluation and how deep it searched; makes results reproducible. */
    public record EngineRun(String version, int depth) {

        public EngineRun {
            if (version == null || version.isBlank() || depth <= 0) {
                throw new IllegalArgumentException("Engine version and a positive depth are required");
            }
        }
    }

    public Score getScoreBefore() {
        return new Score(evalBeforeCp, mateBefore);
    }

    public Score getScoreAfter() {
        return new Score(evalAfterCp, mateAfter);
    }

    public boolean isTrainableMistake() {
        return byUser && classification.isTrainable();
    }

    public Game getGame() {
        return game;
    }

    public int getPly() {
        return ply;
    }

    public boolean isByUser() {
        return byUser;
    }

    public String getFenBefore() {
        return fenBefore;
    }

    public String getMoveUci() {
        return moveUci;
    }

    public String getMoveSan() {
        return moveSan;
    }

    public String getBestMoveUci() {
        return bestMoveUci;
    }

    public EngineRun getEngineRun() {
        return new EngineRun(engineVersion, engineDepth);
    }

    public MoveClassification getClassification() {
        return classification;
    }
}
