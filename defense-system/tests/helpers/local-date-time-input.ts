/** Whole-second local time for a genuine native datetime-local input. */
export function localDateTimeInput(epochMs: number): string {
  if (!Number.isSafeInteger(epochMs) || epochMs % 1000 !== 0) {
    throw new Error('A valid whole-second deadline is required');
  }
  const d = new Date(epochMs);
  if (!Number.isFinite(d.getTime()) || d.getFullYear() < 1000 || d.getFullYear() > 9999) {
    throw new Error('A valid four-digit deadline is required');
  }
  const pad = (n: number) => String(n).padStart(2, '0');
  const minute = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
  // HTML normalizes zero seconds away; Playwright.fill requires that exact value.
  return d.getSeconds() === 0 ? minute : `${minute}:${pad(d.getSeconds())}`;
}
