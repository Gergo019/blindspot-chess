package io.github.gergo019.blindspot.analysis;

import io.github.gergo019.blindspot.common.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A motif detected behind one of the user's mistakes, with the line of moves
 * that demonstrates it on the board. Product rule: no claim reaches the player
 * without evidence they can play through.
 */
@Entity
@Table(name = "mistake_findings")
public class MistakeFinding extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "move_evaluation_id", nullable = false)
    private MoveEvaluation moveEvaluation;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Motif motif;

    /** Space-separated UCI moves starting from the position after the mistake. */
    @Column(name = "evidence_line_uci", nullable = false)
    private String evidenceLineUci;

    @Column(name = "detector_version", nullable = false, length = 20)
    private String detectorVersion;

    protected MistakeFinding() {
        // for JPA
    }

    public MistakeFinding(MoveEvaluation moveEvaluation, Motif motif, String evidenceLineUci, String detectorVersion) {
        if (!moveEvaluation.isTrainableMistake()) {
            throw new IllegalArgumentException("Findings are only recorded for the user's mistakes and blunders");
        }
        if (evidenceLineUci == null || evidenceLineUci.isBlank()) {
            throw new IllegalArgumentException("A finding needs an evidence line");
        }
        this.moveEvaluation = moveEvaluation;
        this.motif = motif;
        this.evidenceLineUci = evidenceLineUci.strip();
        this.detectorVersion = detectorVersion;
    }

    public MoveEvaluation getMoveEvaluation() {
        return moveEvaluation;
    }

    public Motif getMotif() {
        return motif;
    }

    public String getEvidenceLineUci() {
        return evidenceLineUci;
    }

    public String getDetectorVersion() {
        return detectorVersion;
    }
}
