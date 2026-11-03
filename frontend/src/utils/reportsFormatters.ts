import i18n from '../i18n/config.ts';

function getLocale(): string {
  return i18n?.language?.startsWith('en') ? 'en-US' : 'pl-PL';
}

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
  const fmt = new Intl.NumberFormat(getLocale(), {
    style: 'currency',
    currency,
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const formatted = fmt.format(Math.abs(amount));
  if (amount < 0) return `-${formatted}`;
  if (forceSign && amount > 0) return `+${formatted}`;
  return formatted;
}

export function formatPercent(pct: number, forceSign = false): string {
  const fmt = new Intl.NumberFormat(getLocale(), {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
  const formatted = `${fmt.format(Math.abs(pct))}%`;
  if (pct < 0) return `-${formatted}`;
  if (forceSign && pct > 0) return `+${formatted}`;
  return formatted;
}

export function getValueColorClass(val: number): string {
  if (val > 0) return 'text-emerald-400';
  if (val < 0) return 'text-rose-400';
  return 'text-zinc-400';
}
