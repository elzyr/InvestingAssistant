const plnFormatter = new Intl.NumberFormat('pl-PL', {
  style: 'currency',
  currency: 'PLN',
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

const compactPlnFormatter = new Intl.NumberFormat('pl-PL', {
  style: 'currency',
  currency: 'PLN',
  maximumFractionDigits: 0,
});

const pctFormatter = new Intl.NumberFormat('pl-PL', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatPLN(amount: number, forceSign = false): string {
  const formatted = plnFormatter.format(Math.abs(amount));
  if (amount < 0) return `-${formatted}`;
  if (forceSign && amount > 0) return `+${formatted}`;
  return formatted;
}

export function formatCompactPLN(amount: number): string {
  const formatted = compactPlnFormatter.format(Math.abs(amount));
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
