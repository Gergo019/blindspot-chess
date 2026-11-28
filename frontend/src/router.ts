import { useCallback, useEffect, useState } from "react";

/**
 * A minimal router (#24): the app has a handful of views, so the History API is enough and no router
 * dependency is needed. nginx (and Vite in development) serve index.html for every path, so deep links and
 * reloads work.
 *
 * A lesson has two segments, `/journey/lessons/:unit/:number`, as in the API (ADR 0008, amendment of #90):
 * its key (`hanging-piece/1`) has a slash. Both segments are checked strictly here, so the screen only ever
 * asks the API for a well-formed lesson; whether it exists is the API's answer (404 "No such lesson").
 */
export type Route =
  | { view: "findings"; findingId: number | null }
  | { view: "games" }
  | { view: "journey" }
  | { view: "lesson"; unit: string; number: number }
  | { view: "not-found" };

/** A unit's key: lower-case words joined by single hyphens, as the curriculum's ("hanging-piece"). */
const UNIT = "[a-z]{1,20}(?:-[a-z]{1,20}){0,3}";
/** A lesson's number: a positive integer without a leading zero, as the API accepts. */
const NUMBER = "[1-9][0-9]{0,2}";

export function parseRoute(pathname: string): Route {
  const path = pathname.replace(/\/+$/, "") || "/";
  if (path === "/" || path === "/findings") return { view: "findings", findingId: null };
  const finding = /^\/findings\/(\d{1,15})$/.exec(path);
  if (finding?.[1]) return { view: "findings", findingId: Number(finding[1]) };
  if (path === "/games") return { view: "games" };
  if (path === "/journey") return { view: "journey" };
  const lesson = new RegExp(`^/journey/lessons/(${UNIT})/(${NUMBER})$`).exec(path);
  if (lesson?.[1] && lesson[2]) return { view: "lesson", unit: lesson[1], number: Number(lesson[2]) };
  return { view: "not-found" };
}

export function findingPath(id: number): string {
  return `/findings/${String(id)}`;
}

export function lessonRoutePath(unit: string, number: number): string {
  return `/journey/lessons/${unit}/${String(number)}`;
}

export function useRoute(): {
  route: Route;
  navigate: (path: string, options?: { replace?: boolean }) => void;
} {
  const [pathname, setPathname] = useState(() => window.location.pathname);

  useEffect(() => {
    const onPopState = () => {
      setPathname(window.location.pathname);
    };
    window.addEventListener("popstate", onPopState);
    return () => {
      window.removeEventListener("popstate", onPopState);
    };
  }, []);

  const navigate = useCallback((path: string, options?: { replace?: boolean }) => {
    if (path === window.location.pathname) return;
    if (options?.replace) window.history.replaceState(null, "", path);
    else window.history.pushState(null, "", path);
    setPathname(path);
  }, []);

  return { route: parseRoute(pathname), navigate };
}
