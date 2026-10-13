import { dayLabel, isSameLocalDay } from './chat-day-separator';

/** ISO string for a local-time date, so tests pass in any timezone. */
function local(
  year: number,
  month: number,
  day: number,
  hours = 12,
  minutes = 0,
): string {
  return new Date(year, month, day, hours, minutes).toISOString();
}

describe('isSameLocalDay', () => {
  it('returns true for two times on the same local day', () => {
    expect(
      isSameLocalDay(local(2026, 9, 12, 8, 0), local(2026, 9, 12, 21, 45)),
    ).toBeTrue();
  });

  it('returns false across local midnight', () => {
    expect(
      isSameLocalDay(local(2026, 9, 12, 23, 30), local(2026, 9, 13, 0, 10)),
    ).toBeFalse();
  });

  it('returns false when either timestamp is invalid', () => {
    expect(isSameLocalDay('not-a-date', local(2026, 9, 12))).toBeFalse();
    expect(isSameLocalDay(local(2026, 9, 12), '')).toBeFalse();
  });
});

describe('dayLabel', () => {
  // Monday, October 12, 2026, 14:00 local time
  const now = new Date(2026, 9, 12, 14, 0);

  it('returns "Today" for the same local day', () => {
    expect(dayLabel(local(2026, 9, 12, 9, 30), now, 'en-US')).toBe('Today');
  });

  it('returns "Yesterday" for the previous local day', () => {
    expect(dayLabel(local(2026, 9, 11, 18, 0), now, 'en-US')).toBe('Yesterday');
  });

  it('returns "Yesterday" just after midnight', () => {
    const justAfterMidnight = new Date(2026, 9, 12, 0, 5);

    expect(
      dayLabel(local(2026, 9, 11, 23, 55), justAfterMidnight, 'en-US'),
    ).toBe('Yesterday');
  });

  it('returns the weekday name within the last 6 days', () => {
    expect(dayLabel(local(2026, 9, 9), now, 'en-US')).toBe('Friday');
  });

  it('returns "Month d" for older dates in the same year', () => {
    expect(dayLabel(local(2026, 9, 2), now, 'en-US')).toBe('October 2');
  });

  it('returns "Month d, y" for dates in a previous year', () => {
    expect(dayLabel(local(2025, 11, 28), now, 'en-US')).toBe(
      'December 28, 2025',
    );
  });

  it('does not throw for an invalid timestamp', () => {
    expect(() => dayLabel('not-a-date', now, 'en-US')).not.toThrow();
    expect(dayLabel('not-a-date', now, 'en-US')).toBe('');
  });
});
