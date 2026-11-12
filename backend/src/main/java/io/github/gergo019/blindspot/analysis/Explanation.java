package io.github.gergo019.blindspot.analysis;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

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
 * A plain-language explanation of a finding. Code extracts the checked facts
 * first (pieces, squares, the evidence line); the text is then either a
 * template filled from those facts or an LLM explanation of them.
 * Product rule: only PASSED explanations are ever shown to a player.
 */
@Entity
@Table(name = "explanations")
public class Explanation extends BaseEntity {

    public enum Mode {
        /** Deterministic text built from the facts. Always available as the fallback. */
        TEMPLATE,
        /** LLM with only the position and the move: the baseline that shows why constraints matter. */
        LLM_FREE,
        /** LLM restricted to the checked facts. */
        LLM_FACTS
    }

    public enum VerificationStatus {
        PENDING,
        PASSED,
        FAILED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "finding_id", nullable = false)
    private MistakeFinding finding;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Mode mode;

    @Column(length = 100)
    private String model;

    @Column(name = "prompt_version", length = 20)
    private String promptVersion;

    /** JSON object with the facts the text is allowed to use. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String facts;

    @Column(nullable = false)
    private String content;

    @Enumerated(EnumType.STRING)
    @Column(name = "verification_status", nullable = false, length = 20)
    private VerificationStatus verificationStatus = VerificationStatus.PENDING;

    @Column(name = "unsupported_claims")
    private Integer unsupportedClaims;

    @Column(name = "verification_notes")
    private String verificationNotes;

    protected Explanation() {
        // for JPA
    }

    private Explanation(MistakeFinding finding, Mode mode, String model, String promptVersion, String facts,
            String content) {
        this.finding = finding;
        this.mode = mode;
        this.model = model;
        this.promptVersion = promptVersion;
        this.facts = facts;
        this.content = content;
    }

    public static Explanation template(MistakeFinding finding, String facts, String content) {
        return new Explanation(finding, Mode.TEMPLATE, null, null, facts, content);
    }

    public static Explanation llm(MistakeFinding finding, Mode mode, String model, String promptVersion, String facts,
            String content) {
        if (mode == Mode.TEMPLATE) {
            throw new IllegalArgumentException("Use template() for template explanations");
        }
        if (model == null || promptVersion == null) {
            throw new IllegalArgumentException("LLM explanations must record model and prompt version");
        }
        return new Explanation(finding, mode, model, promptVersion, facts, content);
    }

    /** Records the verifier's result: any unsupported claim fails the explanation. */
    public void recordVerification(int unsupportedClaims, String notes) {
        if (verificationStatus != VerificationStatus.PENDING) {
            throw new IllegalStateException("Explanation already verified: " + verificationStatus);
        }
        if (unsupportedClaims < 0) {
            throw new IllegalArgumentException("unsupportedClaims must not be negative");
        }
        this.unsupportedClaims = unsupportedClaims;
        this.verificationNotes = notes;
        this.verificationStatus = unsupportedClaims == 0 ? VerificationStatus.PASSED : VerificationStatus.FAILED;
    }

    public boolean isShowable() {
        return verificationStatus == VerificationStatus.PASSED;
    }

    public MistakeFinding getFinding() {
        return finding;
    }

    public Mode getMode() {
        return mode;
    }

    public String getModel() {
        return model;
    }

    public String getPromptVersion() {
        return promptVersion;
    }

    public String getFacts() {
        return facts;
    }

    public String getContent() {
        return content;
    }

    public VerificationStatus getVerificationStatus() {
        return verificationStatus;
    }

    public Integer getUnsupportedClaims() {
        return unsupportedClaims;
    }

    public String getVerificationNotes() {
        return verificationNotes;
    }
}
