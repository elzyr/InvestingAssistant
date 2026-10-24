const formatters: Record<string, Intl.NumberFormat> = {
  PLN: new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'PLN', minimumFractionDigits: 2, maximumFractionDigits: 2 }),
  USD: new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'USD', minimumFractionDigits: 2, maximumFractionDigits: 2 }),
  EUR: new Intl.NumberFormat('pl-PL', { style: 'currency', currency: 'EUR', minimumFractionDigits: 2, maximumFractionDigits: 2 }),
};

const pctFormatter = new Intl.NumberFormat('pl-PL', {
  minimumFractionDigits: 2,
  maximumFractionDigits: 2,
});

export function formatNumberWithSpaces(val: number): string {
  return Math.round(val).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
}

export function formatSignedNumberWithSpaces(val: number): string {
  const rounded = Math.round(val);
  const sign = rounded > 0 ? '+' : rounded < 0 ? '-' : '';
  const abs = Math.abs(rounded).toString().replace(/\B(?=(\d{3})+(?!\d))/g, ' ');
  return `${sign}${abs}`;
}

export function formatMoney(amount: number, currency: string, showSign = false): string {
  return `${ showSign ? formatSignedNumberWithSpaces(amount) : formatNumberWithSpaces(amount) } ${currency}`;
}

export function formatPLN(amount: number, forceSign = false, currency = 'PLN'): string {
  const fmt = formatters[currency] || formatters['PLN'];
  const formatted = fmt.format(Math.abs(amount));
  if (amount < 0) return `-${formatted}`;
  if (forceSign && amount > 0) return `+${formatted}`;
  return formatted;
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
