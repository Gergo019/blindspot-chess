package io.github.gergo019.blindspot.analysis.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.gergo019.blindspot.analysis.PatternReportService;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.PatternReport;

/** The player's blind-spot report (#51). */
@RestController
public class PatternController {

    private final PatternReportService reports;

    public PatternController(PatternReportService reports) {
        this.reports = reports;
    }

    @GetMapping("/api/patterns")
    public PatternReport patterns() {
        return reports.report();
    }
}
