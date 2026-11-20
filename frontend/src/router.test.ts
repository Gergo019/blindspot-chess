import { parseRoute } from "./router";

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
  ])("%s", (path, route) => {
    expect(parseRoute(path)).toEqual(route);
  });
});
