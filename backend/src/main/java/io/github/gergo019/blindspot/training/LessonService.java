package io.github.gergo019.blindspot.training;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.gergo019.blindspot.account.CurrentPlayer;
import io.github.gergo019.blindspot.account.UserRepository;
import io.github.gergo019.blindspot.analysis.FindingReadService;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.Facts;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.FindingDetail;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.GameSummary;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.Move;
import io.github.gergo019.blindspot.common.api.NotFoundException;
import io.github.gergo019.blindspot.game.GameEnums.PieceColor;

/**
 * The learning journey for the current player (ADR 0008, #89): the lessons with their derived states,
 * starting a lesson or a retry of its check, and answering its puzzles. The rules live in
 * {@link LessonAssignment} and {@link LessonItem}; this service finds the positions and stores the result.
 * Answers reuse the verdict lookup and SM-2 of {@link Drill#answer}, so nothing asks the engine while the
 * player answers. Findings are read through {@link FindingReadService}, the analysis context's read side.
 *
 * <p>No state is stored besides the assignment, its items and the attempts: {@link State}, "recommended
 * next", "positions still needed" and "retry available" are derived on every call.
 */
@Service
public class LessonService {

    /** Slice 1 has one unit (ADR 0008). */
    private static final Curriculum.Unit UNIT = Curriculum.Unit.HANGING_PIECE;
    private static final Facts NO_FACTS = new Facts(null, null, null, null, null, null, null);
    private static final String ONE_ATTEMPT_PER_CHECK_ITEM = "drill_attempts_one_per_check_item_uq";
    /** The foreign keys an answer fails on when a re-analysis deleted its item (and drill) meanwhile. */
    private static final List<String> ITEM_DELETED_MEANWHILE = List.of("drill_attempts_lesson_item_fk",
            "drill_attempts_drill_id_fkey");
    /** The unique constraints a start fails on when a concurrent start of the same lesson got there first. */
    private static final List<String> STARTED_MEANWHILE = List.of("lesson_assignments_user_lesson_uq",
            "lesson_items_drill_uq", "lesson_items_assignment_position_uq", "lesson_items_assignment_drill_uq");

    /** A lesson's state on the map. None is stored. */
    public enum State {
        /** Not assigned, and too few unused positions to fill it. */
        NEEDS_POSITIONS,
        /** Not assigned, and it can be filled: starting it assigns it. */
        AVAILABLE,
        /** Assigned and not mastered, also with a failed or void check. */
        IN_PROGRESS,
        /** Its check was passed. Stays so, whatever a re-analysis deletes. */
        MASTERED
    }

    /**
     * One lesson on the map. {@code size} is the number of items (for an available lesson: the positions it
     * would take). {@code positionsNeeded} is what a lesson that cannot be assigned yet still needs, or what
     * a retry of a failed or void check still needs; 0 otherwise.
     */
    public record LessonSummary(String key, int number, String title, State state, boolean recommended,
            int solvedFirstTry, int size, int positionsNeeded, boolean retryAvailable) {
    }

    /**
     * One puzzle of a lesson. {@code currentCheck} marks the items of the check that can still master it.
     * {@code orientation} is the side the board is shown from: the user's. {@code solution} is null until
     * the item is answered: before the answer, a puzzle never shows the game or the solution (ADR 0008).
     */
    public record Item(int position, LessonItem.Role role, boolean currentCheck, long drillId, Drill.Task task,
            String fen, PieceColor orientation, boolean answered, boolean solvedFirstTry, Solution solution) {
    }

    /**
     * What an answered item reveals: the finding the position comes from (for the link to it, #100), the
     * game, the move played there with its evidence line, the engine's move and every accepted move.
     */
    public record Solution(long findingId, GameSummary game, Move gameMove, List<String> evidenceLineUci,
            String bestMove, List<String> acceptedMoves) {
    }

    /** A lesson with its explanation and items in order (no items before it is assigned). */
    public record LessonDetail(LessonSummary summary, String explanation, Instant masteredAt, List<Item> items) {
    }

    /**
     * The result of one answer: whether it was the item's first attempt in the lesson, the drill's answer
     * with its evidence (the move's verdict and refutation, the engine's move, the accepted moves), the move
     * from the game with its evidence line, and the lesson afterwards.
     */
    public record LessonAnswer(int position, boolean firstTry, DrillService.Answer answer, Move gameMove,
            List<String> evidenceLineUci, LessonSummary lesson) {
    }

    /** What the map is derived from: the assignments, the unused positions and what they can fill. */
    private record Journey(Map<String, LessonAssignment> assigned, List<LessonFilling.Position> unused,
            Map<String, LessonFilling.Outcome> plan) {
    }

    private final CurrentPlayer player;
    private final UserRepository users;
    private final LessonAssignmentRepository assignments;
    private final DrillRepository drills;
    private final DrillAttemptRepository attempts;
    private final FindingReadService findings;
    private final TransactionTemplate tx;
    private final Clock clock;

    public LessonService(CurrentPlayer player, UserRepository users, LessonAssignmentRepository assignments,
            DrillRepository drills, DrillAttemptRepository attempts, FindingReadService findings,
            TransactionTemplate tx, Clock clock) {
        this.player = player;
        this.users = users;
        this.assignments = assignments;
        this.drills = drills;
        this.attempts = attempts;
        this.findings = findings;
        this.tx = tx;
        this.clock = clock;
    }

    /** Every lesson of the unit, in the recommended order. */
    @Transactional(readOnly = true)
    public List<LessonSummary> lessons() {
        return List.copyOf(summaries(journey(player.userId())).values());
    }

    /** @throws NotFoundException if the curriculum has no such lesson */
    @Transactional(readOnly = true)
    public LessonDetail lesson(String key) {
        Curriculum.Lesson lesson = curriculumLesson(key);
        return detail(lesson, journey(player.userId()));
    }

    /**
     * Starts a lesson, and is safe to repeat:
     * <ul>
     *   <li>not assigned and fillable: assigns it with its positions (ADR 0008, 3+ practice and 2 check);</li>
     *   <li>not assigned and not fillable: changes nothing, the lesson reports the positions it needs;</li>
     *   <li>a failed or void check, and 2 unused positions: starts a retry on them;</li>
     *   <li>otherwise (in progress, mastered, or a retry without enough positions): changes nothing.</li>
     * </ul>
     * When a concurrent start of the same lesson (or of a retry) commits first, this one fails on a unique
     * constraint; it then changes nothing and returns the lesson as the other start left it (#90).
     *
     * @throws NotFoundException if the curriculum has no such lesson
     */
    public LessonDetail start(String key) {
        Curriculum.Lesson lesson = curriculumLesson(key);
        Optional<Long> userId = player.userId();
        try {
            return tx.execute(s -> startInTransaction(lesson, userId));
        } catch (DataIntegrityViolationException e) {
            if (!violates(e, STARTED_MEANWHILE)) {
                throw e;
            }
            // The failed transaction is rolled back; read the lesson the concurrent start stored.
            return tx.execute(s -> detail(lesson, journey(userId)));
        }
    }

    private LessonDetail startInTransaction(Curriculum.Lesson lesson, Optional<Long> userId) {
        Journey journey = journey(userId);
        LessonAssignment assignment = journey.assigned().get(lesson.key());
        if (assignment == null) {
            if (journey.plan().get(lesson.key()) instanceof LessonFilling.Fillable fillable) {
                assignments.save(LessonAssignment.assign(users.getReferenceById(userId.orElseThrow()), lesson,
                        drillsInOrder(fillable.drillIds())));
            }
        } else if (assignment.isRetryAvailable(journey.unused().size())) {
            assignment.retry(drillsInOrder(LessonFilling.retryPositions(journey.unused())));
        }
        assignments.flush();   // a unique violation surfaces here, where start() can recognise it
        return detail(lesson, journey(userId));
    }

    /**
     * Answers the puzzle at a position of a started lesson. Every answer is stored as an attempt of the item
     * and reschedules the drill (SM-2).
     *
     * @throws NotFoundException if there is no such lesson, it is not started, or it has no such position
     * @throws IllegalMoveException if the move is not legal in the position; nothing is recorded
     * @throws CheckAlreadyAnsweredException if it is a check item that has been answered, also when a
     *         concurrent answer got there first and the database refused this one
     * @throws LessonChangedException if a re-analysis deleted the item while the answer was being stored;
     *         nothing is recorded
     */
    @Transactional
    public LessonAnswer answer(String key, int position, String moveUci) {
        Curriculum.Lesson lesson = curriculumLesson(key);
        Optional<Long> userId = player.userId();
        LessonAssignment assignment = userId.flatMap(id -> assignments.findByUserIdAndLessonKey(id, key))
                .orElseThrow(() -> new NotFoundException("No such lesson item"));
        LessonItem item = assignment.item(position).orElseThrow(() -> new NotFoundException("No such lesson item"));

        DrillAttempt attempt = assignment.answer(position, moveUci, clock.instant());
        try {
            attempts.saveAndFlush(attempt);
        } catch (DataIntegrityViolationException e) {
            if (violates(e, List.of(ONE_ATTEMPT_PER_CHECK_ITEM))) {
                throw new CheckAlreadyAnsweredException(position);
            }
            if (violates(e, ITEM_DELETED_MEANWHILE)) {
                throw new LessonChangedException(e);
            }
            throw e;
        } catch (OptimisticLockingFailureException e) {
            // The drill's schedule could not be updated: its row is gone (deleted with its finding).
            throw new LessonChangedException(e);
        }

        FindingDetail finding = findings.detail(item.getDrill().getFinding().getId());
        return new LessonAnswer(position, item.getAttempts().size() == 1, DrillService.answerOf(attempt),
                finding.played(), finding.evidenceLineUci(), summaries(journey(userId)).get(lesson.key()));
    }

    private static Curriculum.Lesson curriculumLesson(String key) {
        return Curriculum.lesson(key).orElseThrow(() -> new NotFoundException("No such lesson"));
    }

    private static boolean violates(DataIntegrityViolationException e, List<String> constraints) {
        String message = String.valueOf(e.getMostSpecificCause().getMessage());
        return constraints.stream().anyMatch(name -> message.contains("\"" + name + "\""));
    }

    private Journey journey(Optional<Long> userId) {
        Map<String, LessonAssignment> assigned = new LinkedHashMap<>();
        List<LessonFilling.Position> unused = new ArrayList<>();
        userId.ifPresent(id -> {
            assignments.findByUserId(id).forEach(a -> assigned.put(a.getLessonKey(), a));
            Set<Long> used = new HashSet<>(assignments.findUsedDrillIds(id));
            Map<Long, Facts> facts = findings.factsByFinding(UNIT.motif());
            for (Drill drill : drills.findByUserIdAndMotifAndKindOrderByIdAsc(id, UNIT.motif(), Drill.Kind.OWN_GAME)) {
                if (!used.contains(drill.getId())) {
                    unused.add(LessonFilling.Position.of(drill.getId(),
                            facts.getOrDefault(drill.getFinding().getId(), NO_FACTS)));
                }
            }
        });
        Map<String, LessonFilling.Outcome> plan = LessonFilling.plan(UNIT, unused, assigned.keySet()).stream()
                .collect(Collectors.toMap(o -> o.lesson().key(), Function.identity()));
        return new Journey(assigned, unused, plan);
    }

    /** Every lesson's summary in the recommended order; the first available or in-progress one is recommended. */
    private Map<String, LessonSummary> summaries(Journey journey) {
        Map<String, LessonSummary> summaries = new LinkedHashMap<>();
        boolean recommendedGiven = false;
        for (Curriculum.Lesson lesson : Curriculum.lessons(UNIT)) {
            LessonSummary summary = summary(lesson, journey, false);
            if (!recommendedGiven && (summary.state() == State.AVAILABLE || summary.state() == State.IN_PROGRESS)) {
                summary = summary(lesson, journey, true);
                recommendedGiven = true;
            }
            summaries.put(lesson.key(), summary);
        }
        return summaries;
    }

    private static LessonSummary summary(Curriculum.Lesson lesson, Journey journey, boolean recommended) {
        LessonAssignment assignment = journey.assigned().get(lesson.key());
        if (assignment == null) {
            return switch (journey.plan().get(lesson.key())) {
                case LessonFilling.Fillable fillable -> new LessonSummary(lesson.key(), lesson.number(), lesson.title(),
                        State.AVAILABLE, recommended, 0, fillable.drillIds().size(), 0, false);
                case LessonFilling.NeedsPositions needs -> new LessonSummary(lesson.key(), lesson.number(),
                        lesson.title(), State.NEEDS_POSITIONS, recommended, 0, 0, needs.positionsNeeded(), false);
            };
        }
        int unused = journey.unused().size();
        int solved = (int) assignment.getItems().stream().filter(LessonItem::isSolvedFirstTry).count();
        return new LessonSummary(lesson.key(), lesson.number(), lesson.title(),
                assignment.isMastered() ? State.MASTERED : State.IN_PROGRESS, recommended, solved,
                assignment.getItems().size(), assignment.positionsNeededForRetry(unused),
                assignment.isRetryAvailable(unused));
    }

    private LessonDetail detail(Curriculum.Lesson lesson, Journey journey) {
        LessonSummary summary = summaries(journey).get(lesson.key());
        LessonAssignment assignment = journey.assigned().get(lesson.key());
        if (assignment == null) {
            return new LessonDetail(summary, lesson.explanation(), null, List.of());
        }
        Set<LessonItem> check = Set.copyOf(assignment.currentCheck());
        List<Item> items = assignment.getItems().stream()
                .map(i -> new Item(i.getPosition(), i.getRole(), check.contains(i), i.getDrill().getId(),
                        i.getDrill().getTask(), i.getDrill().getFen(), orientation(i.getDrill()), i.isAnswered(),
                        i.isSolvedFirstTry(), i.isAnswered() ? solution(i.getDrill()) : null))
                .toList();
        return new LessonDetail(summary, lesson.explanation(), assignment.getMasteredAt(), items);
    }

    private Solution solution(Drill drill) {
        FindingDetail finding = findings.detail(drill.getFinding().getId());
        return new Solution(finding.id(), finding.game(), finding.played(), finding.evidenceLineUci(),
                drill.getSolutionUci(), drill.acceptedMoves());
    }

    /**
     * The user's side (ADR 0008, puzzle tasks): "Find a safe move" has the user to move; "See the capture"
     * has the opponent to move, the board still from the user's side.
     */
    static PieceColor orientation(Drill drill) {
        PieceColor toMove = drill.getFen().split(" ")[1].equals("w") ? PieceColor.WHITE : PieceColor.BLACK;
        boolean userToMove = drill.getTask() == Drill.Task.FIND_SAFE_MOVE;
        return userToMove ? toMove : (toMove == PieceColor.WHITE ? PieceColor.BLACK : PieceColor.WHITE);
    }

    /** The drills with these ids, in this order. */
    private List<Drill> drillsInOrder(List<Long> ids) {
        Map<Long, Drill> byId = drills.findAllById(ids).stream()
                .collect(Collectors.toMap(Drill::getId, Function.identity()));
        return ids.stream().map(byId::get).toList();
    }
}
