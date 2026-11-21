package io.github.gergo019.blindspot.training;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The analysis service's verdicts for every legal move of one position ({@code POST /judge}, #78), checked
 * before a drill may be built on them. A judgement that breaks any rule here is a contract mismatch, never
 * silently stored.
 *
 * @param engine how the verdicts were produced (product rule 4)
 * @param fen the judged position, as the service normalised it
 * @param acceptBelow a move is accepted when its drop is below this
 * @param verdicts one per legal move
 */
public record Judgement(EngineSettings engine, String fen, BigDecimal acceptBelow, List<Verdict> verdicts) {

    /** A move in UCI notation: from, to, and an optional promotion piece. */
    static final Pattern UCI = Pattern.compile("[a-h][1-8][a-h][1-8][qrbn]?");
    /** The drop is stored as NUMERIC(5, 4). */
    static final int DROP_SCALE = 4;

    public record EngineSettings(String version, int depth, int threads, int hashMb) {
        public EngineSettings {
            if (version == null || version.isBlank() || version.length() > 30) {
                throw new IllegalArgumentException("Engine version must be 1 to 30 characters, got " + version);
            }
            if (depth < 1 || threads < 1 || hashMb < 1) {
                throw new IllegalArgumentException("Engine depth, threads and hash size must be positive");
            }
        }
    }

    /** One legal move: exactly one of {@code scoreCp} / {@code scoreMate} (after the move, White's view). */
    public record Verdict(String uci, String san, Integer scoreCp, Integer scoreMate, BigDecimal drop,
            boolean accepted, String refutationUci) {
        public Verdict {
            if (uci == null || !UCI.matcher(uci).matches()) {
                throw new IllegalArgumentException("Not a UCI move: " + uci);
            }
            if (san == null || san.isBlank() || san.length() > 10) {
                throw new IllegalArgumentException("SAN of " + uci + " must be 1 to 10 characters, got " + san);
            }
            if ((scoreCp == null) == (scoreMate == null)) {
                throw new IllegalArgumentException("Verdict for " + uci + " needs exactly one of cp / mate");
            }
            if (drop == null || drop.signum() < 0) {
                throw new IllegalArgumentException("Drop of " + uci + " must be zero or more, got " + drop);
            }
            try {
                drop = drop.setScale(DROP_SCALE, RoundingMode.UNNECESSARY);
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("Drop of " + uci + " has more than 4 decimals: " + drop, e);
            }
            refutationUci = refutationUci == null ? "" : refutationUci.strip();
        }
    }

    public Judgement {
        Objects.requireNonNull(engine, "engine");
        if (fen == null || fen.isBlank() || fen.length() > 100) {
            throw new IllegalArgumentException("A judgement needs the judged position (at most 100 characters)");
        }
        if (acceptBelow == null || acceptBelow.signum() <= 0) {
            throw new IllegalArgumentException("The acceptance threshold must be positive, got " + acceptBelow);
        }
        if (verdicts == null || verdicts.isEmpty()) {
            throw new IllegalArgumentException("A judgement needs a verdict for every legal move; got none");
        }
        Set<String> moves = new HashSet<>();
        for (Verdict verdict : verdicts) {
            if (!moves.add(verdict.uci())) {
                throw new IllegalArgumentException("Two verdicts for " + verdict.uci());
            }
            if (verdict.accepted() != (verdict.drop().compareTo(acceptBelow) < 0)) {
                throw new IllegalArgumentException("Verdict for " + verdict.uci() + " says accepted="
                        + verdict.accepted() + " with drop " + verdict.drop() + " and threshold " + acceptBelow);
            }
        }
        if (verdicts.stream().noneMatch(Verdict::accepted)) {
            throw new IllegalArgumentException("A judgement must accept at least one move (the best has drop 0)");
        }
        verdicts = List.copyOf(verdicts);
    }

    /** The engine's first choice: the lowest drop, ties by UCI. */
    public Verdict best() {
        return verdicts.stream()
                .min(Comparator.comparing(Verdict::drop).thenComparing(Verdict::uci))
                .orElseThrow();
    }

    /** The verdict for a move, or null if it is not a legal move of the position. */
    public Verdict verdictFor(String uci) {
        return verdicts.stream().filter(v -> v.uci().equals(uci)).findFirst().orElse(null);
    }
}
