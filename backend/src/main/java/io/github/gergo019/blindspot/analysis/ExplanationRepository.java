package io.github.gergo019.blindspot.analysis;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.gergo019.blindspot.analysis.Explanation.Mode;
import io.github.gergo019.blindspot.analysis.Explanation.VerificationStatus;

public interface ExplanationRepository extends JpaRepository<Explanation, Long> {

    /**
     * Use this for anything player-facing: it returns only what
     * {@link Explanation#isShowable()} allows, TEMPLATE explanations that
     * PASSED verification.
     */
    default List<Explanation> findShowable(Long findingId) {
        return findByFindingIdAndModeAndVerificationStatus(findingId, Mode.TEMPLATE, VerificationStatus.PASSED);
    }

    List<Explanation> findByFindingIdAndModeAndVerificationStatus(Long findingId, Mode mode,
            VerificationStatus status);

    /** For the evaluation report: how often each mode passes verification. */
    long countByModeAndVerificationStatus(Mode mode, VerificationStatus status);
}
