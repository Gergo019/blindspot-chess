# Recorded Lichess responses

Tests never call the live Lichess API (CONTRIBUTING: external APIs are tested
against recorded responses). These files are real responses, recorded on
2026-11-13 and stored unchanged apart from how the lines are combined and the
placeholder names noted below.

## `thibault-export.ndjson`

Stands in for `GET /api/games/user/thibault` with
`Accept: application/x-ndjson`, `pgnInJson=true`, `opening=true`,
`clocks=false`, `evals=false`. Five lines, each a real game record:

| Game id    | Speed | Status    | Why it is here |
|------------|-------|-----------|----------------|
| `ZlkPN7gH` | rapid | `mate`    | importable; thibault plays Black and wins |
| `v31fmkvX` | blitz | `mate`    | must be skipped: not rapid |
| `k8OVq8Y5` | rapid | `mate`    | importable; thibault plays White and wins |
| `IVGlNazQ` | rapid | `started` | must be skipped: still in progress |
| `aDN8u0zd` | rapid | `timeout` | importable; thibault plays Black and wins |

How they were recorded:

- The three finished rapid games: one export request with `max=3&perfType=rapid&finished=true&ongoing=false`.
- The blitz game: the same export with `max=1&perfType=blitz`.
- The game in progress: `GET /game/export/IVGlNazQ` (JSON) while it was on
  Lichess TV's rapid channel. It is a game between two other players; the
  importer checks the status before anything else, so it is skipped as
  unfinished. Their user names are replaced with the placeholders
  `tv_white_player` and `tv_black_player` (in `players` and in the PGN tags),
  and their profile flair is removed; nothing else in the record is changed.
  The game id still points to the real game on lichess.org.

The real export, with the filters above, would not send the blitz game or the
game in progress at all. They are mixed in on purpose, because the importer
must not trust the server-side filters alone (product rule 1).

Lichess game data is released under CC0 (https://database.lichess.org).

## `knight-game.ndjson`

**Not a recorded game.** One record in the export format above, written by
hand for the end-to-end test of background analysis: the knight game
`1. e4 e5 2. Nf3 d6 3. Nd4 exd4 0-1` (White, the user `fixture_white`,
resigns after losing the knight). The id `knightE2` and both user names are
made up. Its fields follow the recorded records, so it goes through the real
import code.
