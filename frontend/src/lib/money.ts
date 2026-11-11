const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });

/** Highest amount the backend accepts for one request: 1,000,000,000.00 rupees. */
export const MAX_AMOUNT_PAISE = 100_000_000_000;

const RUPEES_PATTERN = /^\d+(\.\d{1,2})?$/;

/** Display only. The paise value itself is never rounded or stored from this. */
export function formatPaise(paise: number): string {
  return inr.format(paise / 100);
}

/**
 * "250" -> 25000, "99.5" -> 9950, "0.29" -> 29. Returns null for anything else (signs, commas, 3+ decimals).
 * Built from the integer and fraction digits with BigInt, never parseFloat: in floating point 0.29 * 100 is
 * 28.999999999999996 and 1.005 cannot be represented at all.
 */
export function parseRupeesToPaise(input: string): number | null {
  const text = input.trim();
  if (!RUPEES_PATTERN.test(text)) return null;
  const [rupees, fraction = ''] = text.split('.');
  const paise = BigInt(rupees) * 100n + BigInt(fraction.padEnd(2, '0'));
  if (paise > BigInt(Number.MAX_SAFE_INTEGER)) return null;
  return Number(paise);
}
