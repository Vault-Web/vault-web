import { formatDate } from '@angular/common';

function parseTimestamp(timestamp: string): Date | null {
  const ms = Date.parse(timestamp);
  return Number.isNaN(ms) ? null : new Date(ms);
}

/** Local midnight of `date`, shifted by `offsetDays` calendar days. */
function localMidnight(date: Date, offsetDays = 0): number {
  return new Date(
    date.getFullYear(),
    date.getMonth(),
    date.getDate() + offsetDays,
  ).getTime();
}

/**
 * Whether two timestamps fall on the same calendar day in local time.
 * Returns false when either timestamp cannot be parsed.
 */
export function isSameLocalDay(a: string, b: string): boolean {
  const first = parseTimestamp(a);
  const second = parseTimestamp(b);

  if (!first || !second) {
    return false;
  }

  return localMidnight(first) === localMidnight(second);
}

/**
 * Label for the day separator above a message: "Today", "Yesterday", the
 * weekday name within the last 6 days, "Month d" earlier in the same year,
 * otherwise "Month d, y". Returns an empty string for an unparseable timestamp.
 */
export function dayLabel(timestamp: string, now: Date, locale: string): string {
  const date = parseTimestamp(timestamp);

  if (!date) {
    return '';
  }

  const day = localMidnight(date);
  const today = localMidnight(now);

  if (day === today) {
    return 'Today';
  }

  if (day === localMidnight(now, -1)) {
    return 'Yesterday';
  }

  if (day >= localMidnight(now, -6) && day < today) {
    return formatDate(date, 'EEEE', locale);
  }

  if (date.getFullYear() === now.getFullYear()) {
    return formatDate(date, 'MMMM d', locale);
  }

  return formatDate(date, 'MMMM d, y', locale);
}
