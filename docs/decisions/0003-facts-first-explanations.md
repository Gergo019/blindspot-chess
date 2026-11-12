# 0003: Explanations from validated typed claims; free-form AI only as an experiment

- Status: accepted
- Date: 2026-11-12

## Context

AI-written explanations of chess mistakes can invent moves, pieces or tactics. A naive checker that only confirms the mentioned moves, squares and motif appear in the facts is not enough: a sentence can use all the right terms and still reverse who threatens whom ("your knight forks their king and rook" when it is the opponent's knight). An engine score cannot confirm a strategic sentence either.

## Decision

1. **Typed claims.** For each finding, code produces claims with a fixed structure, for example `ATTACKS(piece=black knight, from=d4, targets=[c2, e2], after=Nxe5)` or `UNDEFENDED(piece=white knight, square=e5, after=Qg5)`. Each claim is validated by replaying the evidence line with python-chess. A claim that fails validation is dropped, and a finding left with no valid claims is not shown.
2. **v1 renders text from the validated claims** with templates (`TEMPLATE` mode). Who attacks whom is part of the claim, so the text cannot reverse it.
3. **Free-form AI is an experiment, not a feature.** Two experimental modes run on a held-out set from the Lichess open database: `LLM_FREE` (position and move only) and `LLM_FACTS` (given the validated claims). Their output is checked for unsupported claims, and the reviewed set deliberately includes failures that reuse all the right terms with the roles reversed.
4. The evaluation reports, per mode: unsupported claims (including role reversals), how often the template fallback would have been needed, and which explanation players found clearer.

## Consequences

- What players see in v1 is correct by construction; every sentence maps to a validated claim and a playable line.
- AI text only reaches players if the experiment shows a low, measured rate of unsupported claims, and even then the player falls back to the template whenever a check fails.
- Templates read more mechanically. That is an acceptable price, and the evaluation will show whether players mind.
- The claim types limit what can be explained. New motifs need new claim types and validators first.
