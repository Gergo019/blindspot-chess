package io.github.gergo019.blindspot.training;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LessonAssignmentRepository extends JpaRepository<LessonAssignment, Long> {

    Optional<LessonAssignment> findByUserIdAndLessonKey(Long userId, String lessonKey);

    List<LessonAssignment> findByUserId(Long userId);

    /** The drills that are an item of any of the user's lessons; every other drill is an unused position. */
    @Query("select i.drill.id from LessonItem i where i.assignment.user.id = :userId")
    List<Long> findUsedDrillIds(@Param("userId") Long userId);
}
