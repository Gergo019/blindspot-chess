# Demo data from the Lichess open database

`select_demo_games.py` picks the games behind the backend's `demo` profile
(`backend/src/main/resources/demo/games.ndjson`): about 20 real, finished
rapid games for one fictional player, `demo`, some of them with a
hanging-piece finding and some with none. Run it once, locally, when the
selection rules or the detector change; CI never downloads the database.

## Source and licence

- File: `lichess_db_standard_rated_2013-01.pgn.zst`, from the Lichess open database
  <https://database.lichess.org/standard/lichess_db_standard_rated_2013-01.pgn.zst>
  (17,761,302 bytes compressed; 121,332 rated standard games).
- SHA-256 of the compressed file, as published in
  <https://database.lichess.org/standard/sha256sums.txt>:
  `aa40b3671fa3cf1072eb182892cd90b0e1e003a4a5943492f64b77e7f3fd1635`
- Licence: the Lichess open database is released under
  [CC0 1.0](https://creativecommons.org/publicdomain/zero/1.0/) ("public
  domain"), see <https://database.lichess.org>. The selected games are
  redistributed here under the same terms.

An early month is used on purpose: a few MB instead of the tens of GB of a
recent month.

## Selection rules

1. **Finished rated standard rapid games.** The `Event` header starts with
   `Rated`, there is no `Variant`/`FEN` header other than standard,
   `Result` is `1-0`, `0-1` or `1/2-1/2`, and `Termination` is `Normal` or
   `Time forfeit` (not `Abandoned` or `Rules infraction`). **Rapid by time
   control**, because 2013 headers only say Bullet, Blitz or Classical:
   Lichess's estimated duration, `initial + 40 × increment`, between 480 s
   (inclusive) and 1500 s (exclusive). **20 to 300 half-moves** (shorter games make
   poor demo material; the analysis service rejects longer ones).
2. **Sorted by Lichess game id**, the side treated as the user alternates
   along that order: even position White, odd position Black.
3. **Analysed in that order** by our own analysis service (its Docker image:
   Stockfish 19, depth 16, `Threads=1`, `Hash=64`), with that user colour.
4. **Quota:** the first 7 games where the hanging-piece detector fires on
   the user's side, and the first 13 games with no finding at all. Games
   with a finding of another motif would be skipped (there is only one
   motif today). The run stops when both quotas are full.
5. **Output** sorted by game id, in the Lichess game-export NDJSON format
   that `LichessGame` reads. Player names are replaced: the user is `demo`,
   each opponent `opponent_01` ... `opponent_20`. The original Lichess game
   id is kept as `id` (and in the `Site` and `GameId` PGN tags), and
   `source` names the database month, so every game can be traced back. The
   PGN keeps the moves and result only (no comments), with the original
   date, time, ratings, time control, opening and termination.

No randomness anywhere: the same file and the same analysis service give
the same selection. `selection-2013-01.json` is the report of the run that
produced the committed file: every analysed game, its user colour, length,
analysis time and finding plies, and the counts.

## Steps

From the repository root, with Docker running and
[uv](https://docs.astral.sh/uv/) installed:

```bash
# 1. Download once and verify (the cache folder is git-ignored)
mkdir -p tools/demo-data/cache && cd tools/demo-data/cache
curl -fLO https://database.lichess.org/standard/lichess_db_standard_rated_2013-01.pgn.zst
echo "aa40b3671fa3cf1072eb182892cd90b0e1e003a4a5943492f64b77e7f3fd1635  lichess_db_standard_rated_2013-01.pgn.zst" | shasum -a 256 -c
zstd -d lichess_db_standard_rated_2013-01.pgn.zst
cd ../../..

# 2. Start the analysis service from its Dockerfile (the runtime image)
docker build --target runtime -t blindspot-analysis-demo analysis-service
docker run -d --rm --name blindspot-analysis-demo -p 58000:8000 blindspot-analysis-demo

# 3. Select and write the demo file and the report
uv run --no-project --with-requirements tools/demo-data/requirements.txt python -I \
  tools/demo-data/select_demo_games.py \
  --pgn tools/demo-data/cache/lichess_db_standard_rated_2013-01.pgn \
  --month 2013-01 \
  --service-url http://localhost:58000 \
  --output backend/src/main/resources/demo/games.ndjson \
  --report tools/demo-data/selection-2013-01.json

docker stop blindspot-analysis-demo
```

If the selection changes, update `DemoDataIT.DEMO_GAMES` and the numbers
below.

## The committed selection

Run on 2026-11-14 with Stockfish 19 at depth 16 on an Apple M4 (Docker, arm64):

- 121,332 games in the file; **39,080** finished rated standard rapid games by the header rules.
- **21** analysed, in game-id order, before both quotas were full: **7** with a hanging-piece finding, 14 without any finding. None was skipped for length.
- **20** selected: 7 games with a finding (5 with `demo` as Black, 2 as White), 13 without. `demo` plays White in 10 games and Black in 10.
- The run took **194 s**, of which 191 s was analysis (the header scan of the 93 MB PGN took a few seconds).

| Game | `demo` plays | Half-moves | Analysis (s) | Hanging-piece plies | Selected |
|---|---|---|---|---|---|
| `000g38vv` | White | 87 | 13.8 | – | yes |
| `000itdmn` | Black | 68 | 9.0 | – | yes |
| `001c7wqq` | White | 65 | 8.6 | – | yes |
| `003sqcak` | Black | 70 | 10.7 | – | yes |
| `003zi1ed` | White | 68 | 8.6 | – | yes |
| `004uiuhf` | Black | 37 | 5.1 | – | yes |
| `008j6z3b` | White | 42 | 8.7 | 9 | yes |
| `008pkspi` | Black | 39 | 6.0 | 36 | yes |
| `00a4p9xz` | White | 71 | 9.6 | – | yes |
| `00ckuzm1` | Black | 86 | 11.5 | 66 | yes |
| `00ebzncu` | White | 76 | 10.0 | – | yes |
| `00edbvy9` | Black | 53 | 5.7 | – | yes |
| `00gou273` | White | 96 | 11.7 | – | yes |
| `00h98l4i` | Black | 35 | 4.6 | – | yes |
| `00hex9t4` | White | 89 | 13.6 | – | yes |
| `00hjmrjd` | Black | 26 | 3.7 | 20 | yes |
| `00hueiqw` | White | 37 | 6.4 | – | yes |
| `00jay4x6` | Black | 63 | 9.3 | 22 | yes |
| `00jemqaa` | White | 81 | 11.8 | – | no (quota full) |
| `00lv6mdm` | Black | 63 | 14.6 | 16 | yes |
| `00mq9lvs` | White | 67 | 8.0 | 47 | yes |

When the backend analyses the demo games itself, it finds the same 7 findings on the same plies (verified with the `demo` profile in Docker Compose; see the devlog).

Some PGNs say `Rated Classical game`: in 2013 Lichess labelled 5+5 and similar time controls "Classical". By today's rule they are rapid, and the importer does not read the `Event` tag.
