package io.github.gergo019.blindspot.training.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.gergo019.blindspot.analysis.PatternReportService;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.MotifPattern;
import io.github.gergo019.blindspot.common.api.NotFoundException;
import io.github.gergo019.blindspot.common.api.ProblemException;
import io.github.gergo019.blindspot.training.CheckAlreadyAnsweredException;
import io.github.gergo019.blindspot.training.Curriculum;
import io.github.gergo019.blindspot.training.Drill;
import io.github.gergo019.blindspot.training.IllegalMoveException;
import io.github.gergo019.blindspot.training.LessonChangedException;
import io.github.gergo019.blindspot.training.LessonService;
import io.github.gergo019.blindspot.training.api.JourneyResponses.Answer;
import io.github.gergo019.blindspot.training.api.JourneyResponses.AnswerRequest;
import io.github.gergo019.blindspot.training.api.JourneyResponses.Item;
import io.github.gergo019.blindspot.training.api.JourneyResponses.Journey;
import io.github.gergo019.blindspot.training.api.JourneyResponses.Lesson;
import io.github.gergo019.blindspot.training.api.JourneyResponses.LessonSummary;
import io.github.gergo019.blindspot.training.api.JourneyResponses.Solution;
import io.github.gergo019.blindspot.training.api.JourneyResponses.Unit;
import io.github.gergo019.blindspot.training.api.JourneyResponses.Verdict;
import jakarta.validation.Valid;

/**
 * The learning journey over HTTP (#90, ADR 0008, "API"): the map, a lesson, starting it, and answering a
 * puzzle. The first endpoints that change data; the player is {@code CurrentPlayer}'s, as in the read API,
 * and there is no sign-in yet (#60). The rules live in {@link LessonService} and the entities; this class
 * checks the URL, maps records and turns the domain's exceptions into problem details.
 *
 * <p>A lesson key has a slash ({@code hanging-piece/1}), so a lesson's URL has two segments, the unit and
 * the number (ADR 0008, amendment of 2026-11-27, #90). Both are checked against the curriculum before
 * anything else, and an unknown lesson is a fixed "No such lesson": nothing from the URL is echoed.
 *
 * <p>The POSTs accept only {@code application/json}, also the start, which has no body: a cross-site form
 * or {@code text/plain} request gets 415, and a cross-origin JSON request needs a CORS preflight, which the
 * backend does not grant.
 */
@RestController
@RequestMapping("/api")
public class JourneyController {

    private static final Curriculum.Unit UNIT = Curriculum.Unit.HANGING_PIECE;

    private final LessonService lessons;
    private final PatternReportService reports;

    public JourneyController(LessonService lessons, PatternReportService reports) {
        this.lessons = lessons;
        this.reports = reports;
    }

    @GetMapping("/journey")
    public Journey journey() {
        MotifPattern report = reports.report().motifs().stream().filter(m -> m.motif() == UNIT.motif()).findFirst()
                .orElse(null);
        return new Journey(new Unit(UNIT.key(), UNIT.title(), UNIT.motif(), report),
                lessons.lessons().stream().map(JourneyController::summary).toList());
    }

    @GetMapping("/lessons/{unit}/{number}")
    public Lesson lesson(@PathVariable String unit, @PathVariable String number) {
        return lesson(lessons.lesson(key(unit, number)));
    }

    /** Starts the lesson, or a retry of its check; safe to repeat. With too few positions, nothing changes (200). */
    @PostMapping(path = "/lessons/{unit}/{number}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Lesson start(@PathVariable String unit, @PathVariable String number) {
        return lesson(lessons.start(key(unit, number)));
    }

    @PostMapping(path = "/lessons/{unit}/{number}/items/{position}/answer",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public Answer answer(@PathVariable String unit, @PathVariable String number, @PathVariable int position,
            @Valid @RequestBody AnswerRequest body) {
        String key = key(unit, number);
        LessonService.LessonAnswer result;
        try {
            result = lessons.answer(key, position, body.moveUci());
        } catch (IllegalMoveException e) {
            throw new ProblemException(HttpStatus.BAD_REQUEST, "Bad request", "Not a legal move in this position.", e);
        } catch (CheckAlreadyAnsweredException e) {
            throw new ProblemException(HttpStatus.CONFLICT, "Conflict",
                    "This check puzzle has already been answered.", e);
        } catch (LessonChangedException e) {
            throw new ProblemException(HttpStatus.CONFLICT, "Conflict",
                    "The lesson changed while the answer was stored, and the answer was not recorded. "
                            + "Load the lesson again.", e);
        }
        var drill = result.answer();
        return new Answer(result.position(), drill.correct(), result.firstTry(),
                new Verdict(drill.move(), drill.san(), drill.drop(), drill.refutationUci()), drill.bestMove(),
                drill.acceptedMoves(), result.gameMove(), result.evidenceLineUci(), summary(result.lesson()));
    }

    private static String key(String unit, String number) {
        return Curriculum.lesson(unit, number).map(Curriculum.Lesson::key)
                .orElseThrow(() -> new NotFoundException("No such lesson"));
    }

    private static LessonSummary summary(LessonService.LessonSummary s) {
        Curriculum.Lesson lesson = Curriculum.lesson(s.key()).orElseThrow();
        return new LessonSummary(s.key(), lesson.unit().key(), s.number(), s.title(), s.state(), s.recommended(),
                s.solvedFirstTry(), s.size(), s.positionsNeeded(), s.retryAvailable());
    }

    private static Lesson lesson(LessonService.LessonDetail detail) {
        List<Item> items = detail.items().stream().map(JourneyController::item).toList();
        return new Lesson(summary(detail.summary()), detail.explanation(), detail.masteredAt(), items);
    }

    private static Item item(LessonService.Item i) {
        LessonService.Solution s = i.solution();
        Solution solution = s == null ? null
                : new Solution(s.findingId(), s.game(), s.gameMove(), s.evidenceLineUci(), s.bestMove(),
                        s.acceptedMoves());
        return new Item(i.position(), i.role(), i.currentCheck(), i.task(), prompt(i.task()), i.fen(),
                i.orientation(), i.answered(), i.solvedFirstTry(), solution);
    }

    private static String prompt(Drill.Task task) {
        return switch (task) {
            case FIND_SAFE_MOVE -> "Find a safe move";
            case SEE_THE_CAPTURE -> "See the capture";
        };
    }
}
