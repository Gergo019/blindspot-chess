import { api, ApiError } from "./client";

/** The journey's requests (#95): paths, methods and the JSON-only POSTs the API requires (#90). */
function mockFetch(status = 200, body: unknown = {}) {
  const fetchMock = vi.fn<typeof fetch>(() =>
    Promise.resolve(
      new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }),
    ),
  );
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function lastCall(fetchMock: ReturnType<typeof mockFetch>): { path: string; init: RequestInit } {
  const call = fetchMock.mock.calls.at(-1);
  if (!call) throw new Error("fetch was not called");
  const [input, init] = call;
  const path = typeof input === "string" ? input : input instanceof URL ? input.pathname : input.url;
  return { path, init: init ?? {} };
}

describe("the journey API client", () => {
  it("reads the journey and a lesson with GETs", async () => {
    const fetchMock = mockFetch();

    await api.journey();
    expect(lastCall(fetchMock).path).toBe("/api/journey");
    expect(lastCall(fetchMock).init.method).toBeUndefined();

    await api.lesson("hanging-piece", 2);
    expect(lastCall(fetchMock).path).toBe("/api/lessons/hanging-piece/2");
    expect(lastCall(fetchMock).init.method).toBeUndefined();
  });

  it("starts a lesson with a JSON POST without a body, as the API requires (else 415)", async () => {
    const fetchMock = mockFetch();

    await api.startLesson("hanging-piece", 1);

    const { path, init } = lastCall(fetchMock);
    expect(path).toBe("/api/lessons/hanging-piece/1");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({ "Content-Type": "application/json", Accept: "application/json" });
    expect(init.body).toBeUndefined();
  });

  it("answers with a JSON POST of the move", async () => {
    const fetchMock = mockFetch();

    await api.answer("hanging-piece", 1, 7, "f3d4");

    const { path, init } = lastCall(fetchMock);
    expect(path).toBe("/api/lessons/hanging-piece/1/items/7/answer");
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({ "Content-Type": "application/json" });
    expect(init.body).toBe('{"moveUci":"f3d4"}');
  });

  it("turns an error into its status only, never the server's text", async () => {
    mockFetch(409, { detail: "This check puzzle has already been answered." });

    const error = await api.answer("hanging-piece", 1, 4, "f3d4").catch((e: unknown) => e);

    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(409);
    expect((error as ApiError).message).not.toContain("already");
  });
});
