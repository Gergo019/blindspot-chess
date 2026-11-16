# 0004: Hanging-piece detector

- Status: accepted
- Date: 2026-11-13 (v0.1, then v0.2 the same day); v0.3 on 2026-11-16 (#31)
- Current version: `hanging-0.3`

## Context

The coaching loop needs findings: a named motif behind a user's mistake, with a line the player can replay on the board (product rule 2). The first motif is the hanging piece: a move that lets the opponent win one of the user's pieces. A naive check ("the moved piece is attacked and undefended") misses discovered attacks, fires on sound sacrifices and on even trades, and has no evidence line.

## Decision

A `HANGING_PIECE` finding is reported for a user move M when **all** of these hold:

1. **M is the user's move and is classified `MISTAKE` or `BLUNDER`.** This excludes sacrifices the engine approves of (they are `BEST`, `GOOD` or an `INACCURACY`).
2. **The engine's principal variation (PV) from the position after M starts with the opponent capturing one of the user's pieces:** knight, bishop, rook or queen. Pawns are out of scope. If the engine's reply is something else, for example because taking would lose to a tactic, there is no finding. **Exchange on the square:** if M is a capture and that first PV move recaptures on M's square, only that exchange is judged. The finding fires if the value of the user's piece on the square minus the value of the piece M captured is at least 2 (a knight taking a defended pawn: 3 - 1 = 2), with that piece as `piece`, the difference as `materialLost` and the recapture as the evidence line. Rules 3 and 4 do not apply to this case, and material lost later elsewhere in the line is not attributed to M.
3. **Net material loss of at least 2, measured against the engine's best line.** Two lines start from the position **before** M:
   - the **best line**: the engine's best move and its PV;
   - the **played line**: M and the engine's PV after M.

   Both are cut to the same **window**: at least 4 plies, extended one ply at a time while the last ply of either line was **forcing** (a capture, a check, or the reply to a check), up to 8 plies. The loss is the user's material balance (P=1, N=3, B=3, R=5, Q=9) at the end of the best line minus the balance at the end of the played line. **If either window still ends on a forcing ply** (the PV ran out, or the cap was reached), the sequence is unfinished and there is no finding: a line is never judged in the middle of an exchange.
4. **The played line itself loses material** (played-line guard): its balance at the end of the window is at least 2 below the balance right after M. Without this, a quiet move followed by an even trade would fire just because the best line wins something. Missing a win is not hanging a piece. The reported `materialLost` stays the loss against the best line.
5. **No forced mate against the user:** neither a checkmate inside the played window, nor an engine score after M that is a mate against the user (however far away). A mating line will be its own motif later.

**Why the best line is the baseline (v0.3).** v0.2 compared the end of the window with the balance before M. That misses a piece lost inside an exchange: in `3q2k1/ppp2ppp/8/8/3B4/4Qb2/PPP2PPP/6K1 w`, Qxf3 (instead of gxf3) recaptures the knight but drops the guard of d4, and the balance before M and after Qxf3 Qxd4 is the same, so v0.2 saw no loss; the best line gxf3 keeps the bishop. It also blamed a move for a piece that was lost in every line. The best line answers the right question: what did this move cost compared with the engine's choice? The engine already analyses the position before M, so its PV costs no extra search. Two lines of equal length are compared, so neither gets more moves to win material back.

**Evidence line:** the played window without M (the opponent's capture and the plies after it), in UCI, space-separated.

**Facts** (the start of the typed claims of [ADR 0003](0003-facts-first-explanations.md)): `piece` (the user's piece that is captured), `square` (where it is captured), `capturedBy` (the opponent's capturing piece), `materialLost` (the net loss in points), and `movedPiece`, `movedFrom`, `movedTo` (the user's move M). In a discovered loss, `movedPiece` differs from `piece`.

Every finding records its `detectorVersion`, so findings can be recomputed when the rules change.

The detector is a pure function (`analysis-service/app/detectors/hanging_piece.py`): it receives the position before M, M, M's classification, the engine's PV and score for the position after M, and the engine's PV for the position before M (the best line); it never calls the engine. The engine keeps the first 8 PV plies of every position it analyses, with unchanged search settings.

### Version history

| Version | Change |
|---|---|
| `hanging-0.1` | Rules 1 to 4 with a fixed 4-ply window; mate checked inside the window only; facts `piece`, `square`, `capturedBy`, `materialLost`. |
| `hanging-0.2` | The window extends while captures continue, up to 8 plies. A mate score against the user after M blocks the finding, so mates beyond the window count. Facts gain `movedPiece`, `movedFrom`, `movedTo`. |
| `hanging-0.3` | The loss is measured against the engine's best line, not the balance before M; both lines start before M and have equal windows. The window extends through captures, checks and replies to checks (cap 8). A window that ends on a forcing ply gives no finding. Exchange on the square: when the opponent recaptures on the square M captured on, only that exchange is judged (moved piece minus captured piece, at least 2). Guard: the played line itself must lose at least 2. Evidence: the PV plies of the played window (one fewer than before, because M counts as the first window ply). Code review of 2026-11-14 (A2, A7, A4 window part), #31. |

## Consequences

- Findings rest on the engine's own judgement twice: the classification (rule 1) and the PV (rule 2). A finding is therefore as reproducible as the analysis itself, and pinned by the same engine version and depth.
- Discovered losses are found, because rule 2 looks at what the opponent captures, not at the piece that moved; the facts name both.
- The net loss counts material the user wins back inside the window. A knight lost for a pawn is a 2-point loss and fires; a knight lost for two pawns (1 point) does not.
- Moves in already-lost or already-won positions are rarely `MISTAKE` or `BLUNDER` (classification uses winning chances), so a piece hung in a hopeless position is usually not reported.
- A sequence still forcing at ply 8 is not judged at all (v0.3), instead of being judged mid-exchange.
- **Known miss (v0.3): forcing sequences longer than the window.** `5rk1/pb4pp/8/3n4/8/8/PP4PP/3Q1RK1 w`, Qc2 loses the queen after Rxf1+ Kxf1 Ne3+ Ke2 Nxc2. The first PV move captures a rook, so this is a HANGING_PIECE case (and the knight-fork rules of #26 leave it to this motif). But both engine lines are still forcing at the 8-ply cap: the played line ends on the check Ne1+, the best line on the reply Bc8 to Qb8+. Covering it needs a longer window and more PV plies, to be decided on real-game data in #41.
- **Known gap: material lost later in a line the user started with a capture that is recaptured.** Only the exchange on the square is judged, so an even trade (for example Nxd5 exd5, knight for bishop) gives no finding even if the line then wins an unguarded bishop elsewhere (Nxc5 on ply 4). That piece is not the one rule 2 describes, so reporting it here would name the wrong piece. It needs its own rule.
- **The exchange on the square stops at the recapture.** If the user can take back on the square again (Nxd5 exd5 Qxd5), the knight cost two pawns, a net 1, but the finding still says 2. Rare in practice: the engine's line usually shows it, and such a move is seldom a MISTAKE or BLUNDER.
- The best-line baseline can make the loss larger than the hung piece: when the best move would also have won material, `materialLost` includes it (for example, rook for knight while the best line wins a pawn: 4).
- Pawn losses and mating attacks need their own motifs.

## Open questions

- **(a) Net loss or captured-piece value?** Closed in v0.3 (#31): neither. The loss is the difference between the best line and the played line over the same window, which counts material won back in both lines and does not depend on which piece is captured first.
- **(b) Hung pieces in already-decided positions.** They are rarely `MISTAKE` or `BLUNDER`, so they produce no finding and are not drilled. Should they still count toward the pattern report ("you hang pieces when the game is already decided" can still be a habit worth showing)? Open; decide together with the pattern-report threshold in Phase 2.
