import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { App } from "./App";
import { mockApi, page, patterns } from "./test/mockApi";
import { THEME_STORAGE_KEY } from "./theme/theme";

describe("App shell", () => {
  beforeEach(() => {
    localStorage.clear();
    mockApi({ "/api/patterns": patterns(0, 0), "/api/findings": page([]) });
  });

  it("shows the header with Findings, Games and Journey, and no Drills any more", () => {
    render(<App />);
    expect(screen.getByText("BlindSpot")).toBeInTheDocument();
    const nav = within(screen.getByRole("navigation", { name: "Main" }));
    expect(nav.getAllByRole("button").map((b) => b.textContent)).toEqual(["Findings", "Games", "Journey"]);
    expect(nav.getByRole("button", { name: "Findings" })).toHaveAttribute("aria-current", "page");
    expect(nav.getByRole("button", { name: "Games" })).toBeEnabled();
    expect(nav.getByRole("button", { name: "Journey" })).toBeEnabled();
    expect(nav.queryByRole("button", { name: "Drills" })).not.toBeInTheDocument();
  });

  it("shows an empty state instead of data", async () => {
    render(<App />);
    expect(await screen.findByRole("heading", { name: "No findings yet" })).toBeInTheDocument();
  });

  it("navigates to games and back, with the URL", async () => {
    render(<App />);
    await userEvent.click(screen.getByRole("button", { name: "Games" }));
    expect(screen.getByRole("heading", { name: "No games yet" })).toBeInTheDocument();
    expect(window.location.pathname).toBe("/games");
    await userEvent.click(screen.getByRole("button", { name: "Findings" }));
    expect(window.location.pathname).toBe("/findings");
  });

  it("answers an unknown path with a page-not-found message", () => {
    window.history.replaceState(null, "", "/nowhere");
    render(<App />);
    expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
  });

  it("renders the board in the starting position with the bundled pieces", () => {
    render(<App />);
    const board = screen.getByTestId("board");
    expect(board.querySelectorAll("[data-square]")).toHaveLength(64);
    const pieces = [...board.querySelectorAll("[data-piece]")].map((p) => p.getAttribute("data-piece"));
    expect(pieces).toHaveLength(32);
    expect(pieces.filter((p) => p === "bP")).toHaveLength(8);
    expect(board.querySelector('[data-square="e1"] [data-piece]')).toHaveAttribute("data-piece", "wK");
    expect(board.querySelector('[data-square="e8"] [data-piece]')).toHaveAttribute("data-piece", "bK");
  });

  it("starts dark and toggles to light and back, remembering the choice", async () => {
    render(<App />);
    expect(document.documentElement.dataset["theme"]).toBe("dark");

    await userEvent.click(screen.getByRole("button", { name: "Switch to light theme" }));
    expect(document.documentElement.dataset["theme"]).toBe("light");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("light");

    await userEvent.click(screen.getByRole("button", { name: "Switch to dark theme" }));
    expect(document.documentElement.dataset["theme"]).toBe("dark");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
  });

  it("shows the legend and the keyboard hint", () => {
    render(<App />);
    expect(screen.getByText("Your move")).toBeInTheDocument();
    expect(screen.getByText("Evidence line")).toBeInTheDocument();
    expect(screen.getByText(/step through moves/)).toBeInTheDocument();
  });
});
