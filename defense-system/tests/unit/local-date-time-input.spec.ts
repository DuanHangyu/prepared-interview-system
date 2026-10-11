import { describe, expect, it } from 'vitest';
import { localDateTimeInput } from '../helpers/local-date-time-input';

describe('native deadline test input', () => {
  it.each([0, 1, 31, 59])('preserves the exact chosen instant and native normalized value at second %i', second => {
    const epochMs = Date.parse('2026-10-06T01:44:00Z') + second * 1000;
    const chosen = localDateTimeInput(epochMs);
    const input = document.createElement('input');
    input.type = 'datetime-local';
    input.step = '1';
    input.value = chosen;
    expect(input.value).toBe(chosen);
    expect(new Date(input.value).getTime()).toBe(epochMs);
    expect(chosen.length).toBe(second === 0 ? 16 : 19);
  });

  it.each([NaN, Infinity, 0.5, Date.parse('2026-10-06T01:44:00.001Z'), 8640000000000001])('rejects an invalid or sub-second deadline %s', value => {
    expect(() => localDateTimeInput(value)).toThrow();
  });
});
