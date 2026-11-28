import { lessonRoutePath, parseRoute } from "./router";

describe("parseRoute", () => {
  it.each([
    ["/", { view: "findings", findingId: null }],
    ["/findings", { view: "findings", findingId: null }],
    ["/findings/", { view: "findings", findingId: null }],
    ["/findings/12", { view: "findings", findingId: 12 }],
    ["/games", { view: "games" }],
    ["/findings/abc", { view: "not-found" }],
    ["/findings/12/extra", { view: "not-found" }],
    ["/elsewhere", { view: "not-found" }],
    ["/journey", { view: "journey" }],
    ["/journey/", { view: "journey" }],
    ["/journey/lessons/hanging-piece/1", { view: "lesson", unit: "hanging-piece", number: 1 }],
    ["/journey/lessons/hanging-piece/12/", { view: "lesson", unit: "hanging-piece", number: 12 }],
    ["/journey/lessons/fork/3", { view: "lesson", unit: "fork", number: 3 }],
  ])("%s", (path, route) => {
    expect(parseRoute(path)).toEqual(route);
  });

  it.each([
    "/journey/lessons",
    "/journey/lessons/hanging-piece",
    "/journey/lessons/hanging-piece/0",
    "/journey/lessons/hanging-piece/01",
    "/journey/lessons/hanging-piece/-1",
    "/journey/lessons/hanging-piece/1000",
    "/journey/lessons/hanging-piece/abc",
    "/journey/lessons/hanging-piece/1/items",
    "/journey/lessons/Hanging-Piece/1",
    "/journey/lessons/hanging--piece/1",
    "/journey/lessons/-hanging/1",
    "/journey/lessons/hanging-piece%2F1/1",
    "/journey/lessons/hanging_piece/1",
    "/journey/lessons/%3Cscript%3E/1",
    "/journey/lessons/hanging-piece/1.5",
  ])("refuses the malformed lesson path %s", (path) => {
    expect(parseRoute(path)).toEqual({ view: "not-found" });
  });
});

describe("lessonRoutePath", () => {
  it("builds the path parseRoute reads back", () => {
    expect(lessonRoutePath("hanging-piece", 3)).toBe("/journey/lessons/hanging-piece/3");
    expect(parseRoute(lessonRoutePath("hanging-piece", 3))).toEqual({
      view: "lesson",
      unit: "hanging-piece",
      number: 3,
    });
  });
});
