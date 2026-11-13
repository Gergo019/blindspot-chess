# Recorded Lichess responses

Tests never call the live Lichess API (CONTRIBUTING: external APIs are tested
against recorded responses). These files are real responses, recorded on
2026-11-13 and stored unchanged apart from how the lines are combined.

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
  Lichess TV's rapid channel. It is another player's game; the importer checks
  the status before anything else, so it is skipped as unfinished.

The real export, with the filters above, would not send the blitz game or the
game in progress at all. They are mixed in on purpose, because the importer
must not trust the server-side filters alone (product rule 1).

Lichess game data is released under CC0 (https://database.lichess.org).
