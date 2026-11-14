export function normalizeZero(val: number): number {
  return Object.is(val, -0) || Math.abs(val) < 1e-9 ? 0 : val;
}

export function roundOneDecimal(val: number): number {
  return Math.round(val * 10) / 10;
}

export function sanitizeNearZero(val: number, threshold = 0.005): number {
  return Math.abs(val) < threshold ? 0 : val;
}

export function formatNumberWithSpaces(val: number): string {
  const rounded = normalizeZero(Math.round(val));
  return rounded.toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
}

export function formatSignedNumberWithSpaces(val: number): string {
  const rounded = normalizeZero(Math.round(val));
  const sign = rounded > 0 ? '+' : rounded < 0 ? '-' : '';
  const abs = Math.abs(rounded).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return `${sign}${abs}`;
}

export function formatSignedFixedPercent(
  pct: number,
  decimals = 1,
  plusForZero = true
): string {
  const factor = Math.pow(10, decimals);
  const rounded = normalizeZero(Math.round(pct * factor) / factor);
  const sign = rounded > 0 || (rounded === 0 && plusForZero) ? '+' : rounded < 0 ? '-' : '';
  return `${sign}${Math.abs(rounded).toFixed(decimals)}%`;
}

export function formatMoney(amount: number, currency: string, showSign = false): string {
  return `${showSign ? formatSignedNumberWithSpaces(amount) : formatNumberWithSpaces(amount)} ${currency}`;
}

export function formatCurrency(
  amount: number,
  forceSign = false,
  currency = 'PLN',
  locale = 'pl-PL'
): string {
  const clean = sanitizeNearZero(amount);
  const fmt = new Intl.NumberFormat(locale, {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const formatted = fmt.format(Math.abs(clean));
  if (clean < 0) return `-${formatted}`;
  if (forceSign && clean > 0) return `+${formatted}`;
  return formatted;
}

export function formatPercent(
  pct: number,
  forceSign = false,
  decimals = 2,
  locale = 'pl-PL'
): string {
  const clean = sanitizeNearZero(pct, 0.5 / Math.pow(10, decimals));
  const fmt = new Intl.NumberFormat(locale, {
    minimumFractionDigits: decimals,
    maximumFractionDigits: decimals,
  });
  const formatted = `${fmt.format(Math.abs(clean))}%`;
  if (clean < 0) return `-${formatted}`;
  if (forceSign && clean > 0) return `+${formatted}`;
  return formatted;
}

export function formatSignedPercentPoints(val: number, decimals = 2): string {
  return formatSignedFixedPercent(val, decimals).replace('%', ' pp');
}

export function getValueColorClass(val: number, threshold = 0.005): string {
  const clean = sanitizeNearZero(val, threshold);
  if (clean > 0) return 'text-emerald-400';
  if (clean < 0) return 'text-rose-400';
  return 'text-zinc-400';
}
