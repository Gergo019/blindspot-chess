# Third-party notices

BlindSpot's own code is under the [MIT licence](LICENSE), except `analysis-service/`, which is under the [GPL-3.0-or-later](analysis-service/LICENSE) because it imports python-chess ([ADR 0002, amendment of 2026-11-20](docs/decisions/0002-python-analysis-service.md#amendment-2026-11-20-licence-of-the-analysis-service)). It ships or runs the following third-party software and assets under their own licences. Every bundled asset is listed here ([ADR 0007](docs/decisions/0007-frontend-board-and-visual-identity.md)); adding one means adding it here in the same pull request.

## Frontend (bundled into the web app)

| Component | Version | Licence | Notes |
|---|---|---|---|
| [react-chessboard](https://github.com/Clariity/react-chessboard) | 5.12.1 | MIT, Copyright (c) 2022 Ryan Gregory | The board component. |
| Chess pieces, "Cburnett" set | as bundled in react-chessboard 5.12.1 | BSD licence (one of the licences the author offers) | Chess piece SVGs by **Colin M. L. Burnett** ([User:Cburnett](https://commons.wikimedia.org/wiki/User:Cburnett)), from [Wikimedia Commons](https://commons.wikimedia.org/wiki/File:Chess_plt45.svg), where they are offered under GFDL, BSD or GPL. BlindSpot uses them under the BSD licence. react-chessboard bundles them as its default pieces without naming them; the match was checked against the Commons files (ADR 0007). |
| [Inter](https://rsms.me/inter/) via [@fontsource-variable/inter](https://fontsource.org/fonts/inter) | 5.3.0 | SIL Open Font License 1.1, Copyright (c) 2016 The Inter Project Authors | Self-hosted font files, no requests to font CDNs. |
| [React](https://react.dev/) and React DOM | 19.3.1 | MIT, Copyright (c) Meta Platforms, Inc. and affiliates | |
| [@dnd-kit](https://dndkit.com/) (dependency of react-chessboard) | see `frontend/package-lock.json` | MIT, Copyright (c) 2021 Claudéric Demers | |

Development tools (Vite, TypeScript, ESLint, Prettier, Vitest, Testing Library, jsdom) are used to build and test the app and are not shipped in it. Their versions and licences are recorded in `frontend/package-lock.json`.

## Analysis service

| Component | Version | Licence | Notes |
|---|---|---|---|
| [Stockfish](https://stockfishchess.org/) | 19 (official release binary, checksum-pinned) | GPL-3.0 | Runs as a **separate program** that the analysis service talks to over UCI; it is not linked into BlindSpot's code. The image ships Stockfish's licence at `/usr/local/share/doc/stockfish/COPYING.txt` ([ADR 0002](docs/decisions/0002-python-analysis-service.md)). |
| [python-chess](https://python-chess.readthedocs.io/) | 1.11.2 (`analysis-service/requirements.txt`) | GPL-3.0-or-later | A library imported by the analysis service's Python code, unlike Stockfish, which is a separate program. **This is why `analysis-service/` is GPL-3.0-or-later.** The backend and the frontend only call the service over HTTP and stay MIT. |

## Data

| Data | Licence | Notes |
|---|---|---|
| [Lichess open database](https://database.lichess.org/), January 2013 | CC0 | The 20 demo games ([tools/demo-data](tools/demo-data/README.md)). |
