# 0004: Hanging-piece detector v0.1

- Status: accepted
- Date: 2026-11-13

## Context

The coaching loop needs findings: a named motif behind a user's mistake, with a line the player can replay on the board (product rule 2). The first motif is the hanging piece: a move that lets the opponent win one of the user's pieces. A naive check ("the moved piece is attacked and undefended") misses discovered attacks, fires on sound sacrifices and on even trades, and has no evidence line.

## Decision

A `HANGING_PIECE` finding is reported for a user move M when **all** of these hold:

1. **M is the user's move and is classified `MISTAKE` or `BLUNDER`.** This excludes sacrifices the engine approves of (they are `BEST`, `GOOD` or an `INACCURACY`).
2. **The engine's principal variation (PV) from the position after M starts with the opponent capturing one of the user's pieces:** knight, bishop, rook or queen. Pawns are out of scope in v0.1. If the engine's reply is something else, for example because taking would lose to a tactic, there is no finding.
3. **Net material loss of at least 2.** Material balance from the user's side (P=1, N=3, B=3, R=5, Q=9) before M is compared with the balance after M plus the first up to 4 PV plies. Even trades (BxN, PxB) do not count; losing the exchange (rook for bishop, 2) does.
4. **No checkmate within that window.** A line that mates is a different motif, to be added later.

**Evidence line:** the PV plies used for the material check (M's follow-up, not M itself), in UCI, space-separated.

**Facts** (the start of the typed claims of [ADR 0003](0003-facts-first-explanations.md)): `piece` (the user's piece that is captured), `square` (where it is captured), `capturedBy` (the opponent's capturing piece), `materialLost` (the net loss in points).

Every finding records `detectorVersion` `"hanging-0.1"`, so findings can be recomputed when the rules change.

The detector is a pure function (`analysis-service/app/detectors/hanging_piece.py`): it receives the position before M, M, M's classification and the PV; it never calls the engine. The engine keeps the first 4 PV plies of every position it analyses, with unchanged search settings.

## Consequences

- Findings rest on the engine's own judgement twice: the classification (rule 1) and the PV (rule 2). A finding is therefore as reproducible as the analysis itself, and pinned by the same engine version and depth.
- Discovered losses are found, because rule 2 looks at what the opponent captures, not at the piece that moved. The facts name the captured piece, which can differ from the piece that moved.
- The net loss counts material the user wins back inside the window. A knight lost for a pawn is a 2-point loss and fires; a knight lost for two pawns (1 point) does not.
- Moves in already-lost or already-won positions are rarely `MISTAKE` or `BLUNDER` (classification uses winning chances), so a piece hung in a hopeless position is usually not reported.
- The 4-ply window is short. A mate that comes later than ply 4, or an exchange sequence longer than 4 plies, is judged only on what happens inside the window.
- Pawn losses and mating attacks need their own motifs.
