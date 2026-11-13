package io.github.gergo019.blindspot.analysis;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import io.github.gergo019.blindspot.analysis.Explanation.Mode;
import io.github.gergo019.blindspot.analysis.Explanation.VerificationStatus;

public interface ExplanationRepository extends JpaRepository<Explanation, Long> {

    /** Use this for anything player-facing: it can only return verified explanations. */
    default List<Explanation> findShowable(Long findingId) {
        return findByFindingIdAndVerificationStatus(findingId, VerificationStatus.PASSED);
    }

    List<Explanation> findByFindingIdAndVerificationStatus(Long findingId, VerificationStatus status);

    /** For the evaluation report: how often each mode passes verification. */
    long countByModeAndVerificationStatus(Mode mode, VerificationStatus status);
}
