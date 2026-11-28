import type { LessonItem } from "../api/types";

/**
 * Where a player is in a lesson (#96). Items come as a list with their own positions, which can have gaps
 * after a retry or a re-analysis (1 to 5, then 7 and 8): nothing here counts 1 to n. "Position 3 of 7"
 * is the item's place in the list, not its position number.
 */

/** The first item that is neither answered on the server nor done in this visit, in list order. */
export function nextItem(items: readonly LessonItem[], done: ReadonlySet<number>): LessonItem | undefined {
  return items.find((item) => !item.answered && !done.has(item.position));
}

/** "Position 3 of 7": the item's place among the items shown. */
export function placeOf(items: readonly LessonItem[], item: LessonItem): { index: number; count: number } {
  return { index: items.indexOf(item) + 1, count: items.length };
}

/** Only "Find a safe move" exists in slice 1 (#96); another task is shown as a plain message. */
export function canShow(item: LessonItem): boolean {
  return item.task === "FIND_SAFE_MOVE";
}
