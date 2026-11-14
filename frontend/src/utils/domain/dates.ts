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

export function parseUtcDate(dateStr: string): Date {
  const clean = dateStr.slice(0, 10);
  const [y, m, d] = clean.split('-').map(Number);
  if (!Number.isFinite(y) || !Number.isFinite(m) || !Number.isFinite(d)) {
    return new Date(NaN);
  }
  return new Date(Date.UTC(y, m - 1, d));
}

export function parseUtcUnixSeconds(dateStr: string): number {
  const ms = parseUtcDate(dateStr).getTime();
  return Number.isFinite(ms) ? Math.floor(ms / 1000) : 0;
}

export function parseUtcNoonMs(dateStr: string): number {
  const clean = dateStr.slice(0, 10);
  const [y, m, d] = clean.split('-').map(Number);
  return Date.UTC(y, (m || 1) - 1, d || 1, 12, 0, 0);
}

export function normalizeChartTime(time: unknown): number {
  if (!time) return 0;
  if (typeof time === 'number') return time;
  if (typeof time === 'string') return parseUtcUnixSeconds(time);
  if (
    typeof time === 'object' &&
    'year' in (time as Record<string, unknown>) &&
    'month' in (time as Record<string, unknown>) &&
    'day' in (time as Record<string, unknown>)
  ) {
    const t = time as { year: number; month: number; day: number };
    return Math.floor(Date.UTC(t.year, t.month - 1, t.day) / 1000);
  }
  return 0;
}

export function getShortMonthName(monthIndex: number, locale = 'pl'): string {
  const dt = new Date(Date.UTC(2026, monthIndex, 1));
  return new Intl.DateTimeFormat(locale, { month: 'short', timeZone: 'UTC' }).format(dt);
}

export function formatUtcDate(
  dateInput: string | Date | number,
  locale: string,
  options?: Intl.DateTimeFormatOptions
): string {
  const dateStr = standardizeDate(dateInput);
  const dt = parseUtcDate(dateStr);
  if (Number.isNaN(dt.getTime())) return String(dateInput);
  return dt.toLocaleDateString(locale, { ...options, timeZone: 'UTC' });
}

export function normalizeChartDateStr(time: unknown): string {
  if (!time) return '';
  if (typeof time === 'string') return time;
  if (typeof time === 'object' && 'year' in time && 'month' in time) {
    const obj = time as { year: number; month: number; day?: number };
    const dd = String(obj.day ?? 1).padStart(2, '0');
    return `${obj.year}-${String(obj.month).padStart(2, '0')}-${dd}`;
  }
  if (typeof time === 'number') {
    const d = new Date(time * 1000);
    return `${d.getUTCFullYear()}-${String(d.getUTCMonth() + 1).padStart(2, '0')}-${String(d.getUTCDate()).padStart(2, '0')}`;
  }
  return '';
}
