const formatters: Record<string, Intl.NumberFormat> = {
  PLN: new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'PLN', minimumFractionDigits: 2, maximumFractionDigits: 2 }),
  USD: new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 }),
  EUR: new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'EUR', minimumFractionDigits: 2, maximumFractionDigits: 2 }),
};

const compactFormatters: Record<string, Intl.NumberFormat> = {
  PLN: new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'PLN', maximumFractionDigits: 0 }),
  USD: new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'USD', maximumFractionDigits: 0 }),
  EUR: new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'EUR', maximumFractionDigits: 0 }),
};

const pctFormatter = new Intl.NumberFormat('pl-PL', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatPLN(amount: number, forceSign = false, currency = 'PLN'): string {
  const fmt = formatters[currency] || formatters['PLN'];
  const formatted = fmt.format(Math.abs(amount));
  if (amount < 0) return `-${formatted}`;
  if (forceSign && amount > 0) return `+${formatted}`;
  return formatted;
}

export function formatCompactPLN(amount: number, currency = 'PLN'): string {
  const fmt = compactFormatters[currency] || compactFormatters['PLN'];
  const formatted = fmt.format(Math.abs(amount));
  return amount < 0 ? `-${formatted}` : formatted;
}

export function formatPercent(pct: number, forceSign = false): string {
  const formatted = `${pctFormatter.format(Math.abs(pct))}%`;
  if (pct < 0) return `-${formatted}`;
  if (forceSign && pct > 0) return `+${formatted}`;
  return formatted;
}

export function getValueColorClass(val: number): string {
  if (val > 0) return 'text-emerald-400';
  if (val < 0) return 'text-rose-400';
  return 'text-zinc-400';
}
