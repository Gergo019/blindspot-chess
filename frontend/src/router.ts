import { useCallback, useEffect, useState } from "react";

/**
 * A minimal router (#24): the app has three views, so the History API is enough and no router dependency
 * is needed. nginx (and Vite in development) serve index.html for every path, so deep links and reloads work.
 */
export type Route =
  { view: "findings"; findingId: number | null } | { view: "games" } | { view: "not-found" };

export function parseRoute(pathname: string): Route {
  const path = pathname.replace(/\/+$/, "") || "/";
  if (path === "/" || path === "/findings") return { view: "findings", findingId: null };
  const finding = /^\/findings\/(\d{1,15})$/.exec(path);
  if (finding?.[1]) return { view: "findings", findingId: Number(finding[1]) };
  if (path === "/games") return { view: "games" };
  return { view: "not-found" };
}

export function findingPath(id: number): string {
  return `/findings/${String(id)}`;
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
