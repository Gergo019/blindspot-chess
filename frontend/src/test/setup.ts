import "@testing-library/jest-dom/vitest";
import { cleanup } from "@testing-library/react";
import { afterEach } from "vitest";

afterEach(() => {
  cleanup();
  document.documentElement.dataset["theme"] = "dark";
});

// jsdom has no ResizeObserver; react-chessboard measures its container with it.
class ResizeObserverStub {
  observe(): void {}
  unobserve(): void {}
  disconnect(): void {}
}
if (!("ResizeObserver" in globalThis)) {
  Object.defineProperty(globalThis, "ResizeObserver", { value: ResizeObserverStub, writable: true });
}
