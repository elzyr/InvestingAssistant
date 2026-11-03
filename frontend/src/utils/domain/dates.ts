export const MS_PER_DAY = 86400000;

export function parseDateMs(date: string | Date | number): number {
  if (typeof date === 'number') {
    return Number.isFinite(date) ? date : NaN;
  }
  if (date instanceof Date) {
    return date.getTime();
  }
  if (typeof date === 'string') {
    return Date.parse(date);
  }
  return NaN;
}

export function standardizeDate(dateInput: string | Date | number): string {
  if (typeof dateInput === 'string') {
    if (/^\d{4}-\d{2}-\d{2}/.test(dateInput)) {
      return dateInput.slice(0, 10);
    }
    const dt = new Date(dateInput);
    if (!isNaN(dt.getTime())) {
      const y = dt.getFullYear();
      const m = String(dt.getMonth() + 1).padStart(2, '0');
      const d = String(dt.getDate()).padStart(2, '0');
      return `${y}-${m}-${d}`;
    }
    return dateInput;
  }
  const dt = typeof dateInput === 'number' ? new Date(dateInput) : dateInput;
  if (dt instanceof Date && !isNaN(dt.getTime())) {
    const y = dt.getFullYear();
    const m = String(dt.getMonth() + 1).padStart(2, '0');
    const d = String(dt.getDate()).padStart(2, '0');
    return `${y}-${m}-${d}`;
  }
  return String(dateInput);
}

export function getShortMonthName(monthIndex: number, locale = 'pl'): string {
  const dt = new Date(2026, monthIndex, 1);
  return new Intl.DateTimeFormat(locale, { month: 'short' }).format(dt);
}
