package io.github.gergo019.blindspot.analysis.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.github.gergo019.blindspot.analysis.FindingReadService;
import io.github.gergo019.blindspot.analysis.Motif;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.FindingDetail;
import io.github.gergo019.blindspot.analysis.api.FindingResponses.FindingSummary;
import io.github.gergo019.blindspot.common.api.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** Read-only findings API (#51): GET only, no logic beyond parameter validation. */
@RestController
@RequestMapping("/api/findings")
public class FindingController {

    static final int MAX_PAGE_SIZE = 50;

    private final FindingReadService findings;

    public FindingController(FindingReadService findings) {
        this.findings = findings;
    }

    /** The player's findings, newest game first; {@code motif} filters, paging is bounded. */
    @GetMapping
    public PageResponse<FindingSummary> list(@RequestParam(required = false) Motif motif,
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        return findings.page(motif, page, size);
    }

    @GetMapping("/{id}")
    public FindingDetail detail(@PathVariable long id) {
        return findings.detail(id);
    }
}
