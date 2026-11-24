import type {
  ExpenseCategory,
  ExpenseInsightDto,
  ExpensePeriodPreset,
  FlowKind,
  MonthlyTrendPoint,
} from '../types/expense.types.ts';
import { formatCurrency, sanitizeNearZero } from './reportsFormatters.ts';

export interface CategoryMeta {
  category: ExpenseCategory;
  labelKey: string;
  color: string;
  flowKind: FlowKind;
  isConsumption: boolean;
}

export const ALL_EXPENSE_CATEGORIES: ExpenseCategory[] = [
  'GROCERIES',
  'DINING',
  'HOUSING',
  'UTILITIES',
  'TELECOM',
  'HOME_GARDEN',
  'FUEL',
  'TRANSPORT',
  'CAR_MAINTENANCE',
  'HEALTH',
  'FITNESS_SPORT',
  'CLOTHING',
  'BEAUTY_CARE',
  'ELECTRONICS',
  'SHOPPING',
  'ENTERTAINMENT',
  'SUBSCRIPTIONS',
  'TRAVEL',
  'EDUCATION',
  'PETS',
  'GIFTS',
  'SERVICES_FEES',
  'OTHER_EXPENSE',
  'INCOME',
  'INVESTMENTS_SAVINGS',
  'INTERNAL_TRANSFER',
  'REFUND',
];

const CATEGORY_META_MAP: Record<ExpenseCategory, CategoryMeta> = {
  GROCERIES: {
    category: 'GROCERIES',
    labelKey: 'expenses.categories.GROCERIES',
    color: '#10b981',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  DINING: {
    category: 'DINING',
    labelKey: 'expenses.categories.DINING',
    color: '#f59e0b',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  HOUSING: {
    category: 'HOUSING',
    labelKey: 'expenses.categories.HOUSING',
    color: '#3b82f6',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  UTILITIES: {
    category: 'UTILITIES',
    labelKey: 'expenses.categories.UTILITIES',
    color: '#0284c7',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  TELECOM: {
    category: 'TELECOM',
    labelKey: 'expenses.categories.TELECOM',
    color: '#8b5cf6',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  HOME_GARDEN: {
    category: 'HOME_GARDEN',
    labelKey: 'expenses.categories.HOME_GARDEN',
    color: '#65a30d',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  FUEL: {
    category: 'FUEL',
    labelKey: 'expenses.categories.FUEL',
    color: '#ea580c',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  TRANSPORT: {
    category: 'TRANSPORT',
    labelKey: 'expenses.categories.TRANSPORT',
    color: '#06b6d4',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  CAR_MAINTENANCE: {
    category: 'CAR_MAINTENANCE',
    labelKey: 'expenses.categories.CAR_MAINTENANCE',
    color: '#0891b2',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  HEALTH: {
    category: 'HEALTH',
    labelKey: 'expenses.categories.HEALTH',
    color: '#f43f5e',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  FITNESS_SPORT: {
    category: 'FITNESS_SPORT',
    labelKey: 'expenses.categories.FITNESS_SPORT',
    color: '#ef4444',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  CLOTHING: {
    category: 'CLOTHING',
    labelKey: 'expenses.categories.CLOTHING',
    color: '#d946ef',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  BEAUTY_CARE: {
    category: 'BEAUTY_CARE',
    labelKey: 'expenses.categories.BEAUTY_CARE',
    color: '#f472b6',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  ELECTRONICS: {
    category: 'ELECTRONICS',
    labelKey: 'expenses.categories.ELECTRONICS',
    color: '#4f46e5',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  SHOPPING: {
    category: 'SHOPPING',
    labelKey: 'expenses.categories.SHOPPING',
    color: '#a855f7',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  ENTERTAINMENT: {
    category: 'ENTERTAINMENT',
    labelKey: 'expenses.categories.ENTERTAINMENT',
    color: '#ec4899',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  SUBSCRIPTIONS: {
    category: 'SUBSCRIPTIONS',
    labelKey: 'expenses.categories.SUBSCRIPTIONS',
    color: '#6366f1',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  TRAVEL: {
    category: 'TRAVEL',
    labelKey: 'expenses.categories.TRAVEL',
    color: '#14b8a6',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  EDUCATION: {
    category: 'EDUCATION',
    labelKey: 'expenses.categories.EDUCATION',
    color: '#84cc16',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  PETS: {
    category: 'PETS',
    labelKey: 'expenses.categories.PETS',
    color: '#d97706',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  GIFTS: {
    category: 'GIFTS',
    labelKey: 'expenses.categories.GIFTS',
    color: '#fb7185',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  SERVICES_FEES: {
    category: 'SERVICES_FEES',
    labelKey: 'expenses.categories.SERVICES_FEES',
    color: '#64748b',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  OTHER_EXPENSE: {
    category: 'OTHER_EXPENSE',
    labelKey: 'expenses.categories.OTHER_EXPENSE',
    color: '#71717a',
    flowKind: 'EXPENSE',
    isConsumption: true,
  },
  INCOME: {
    category: 'INCOME',
    labelKey: 'expenses.categories.INCOME',
    color: '#22c55e',
    flowKind: 'INCOME',
    isConsumption: false,
  },
  INVESTMENTS_SAVINGS: {
    category: 'INVESTMENTS_SAVINGS',
    labelKey: 'expenses.categories.INVESTMENTS_SAVINGS',
    color: '#0ea5e9',
    flowKind: 'INVESTMENT_SAVING',
    isConsumption: false,
  },
  INTERNAL_TRANSFER: {
    category: 'INTERNAL_TRANSFER',
    labelKey: 'expenses.categories.INTERNAL_TRANSFER',
    color: '#52525b',
    flowKind: 'INTERNAL_TRANSFER',
    isConsumption: false,
  },
  REFUND: {
    category: 'REFUND',
    labelKey: 'expenses.categories.REFUND',
    color: '#2dd4bf',
    flowKind: 'REFUND',
    isConsumption: false,
  },
};

export function getCategoryMeta(category: string): CategoryMeta {
  const key = category as ExpenseCategory;
  return CATEGORY_META_MAP[key] || CATEGORY_META_MAP.OTHER_EXPENSE;
}

export function getSelectableCategoriesForAmount(amount: number): ExpenseCategory[] {
  if (amount < 0) {
    return ALL_EXPENSE_CATEGORIES.filter((cat) => cat !== 'INCOME' && cat !== 'REFUND');
  }
  return [...ALL_EXPENSE_CATEGORIES];
}

export function isFlowCompatibleWithAmount(flowKind: FlowKind, amount: number): boolean {
  if (amount > 0 && flowKind === 'EXPENSE') {
    return false;
  }
  if (amount < 0 && (flowKind === 'INCOME' || flowKind === 'REFUND')) {
    return false;
  }
  return true;
}

export function resolveCategoryFlowKind(category: ExpenseCategory, amount: number): FlowKind {
  const meta = getCategoryMeta(category);
  if (amount > 0 && meta.isConsumption) {
    return 'REFUND';
  }
  return meta.flowKind;
}

export function shouldAutoRememberRule(
  nextCategory: ExpenseCategory,
  previousCategory: ExpenseCategory,
  amount: number
): boolean {
  return (
    nextCategory !== previousCategory &&
    resolveCategoryFlowKind(nextCategory, amount) !== 'REFUND'
  );
}

export interface PreviewRowGroupable {
  duplicate?: boolean;
  needsReview?: boolean;
}

export interface GroupedPreviewRows<T> {
  needsReview: T[];
  duplicates: T[];
  ready: T[];
}

export function groupPreviewRows<T extends PreviewRowGroupable>(
  rows: readonly T[]
): GroupedPreviewRows<T> {
  const needsReview: T[] = [];
  const duplicates: T[] = [];
  const ready: T[] = [];

  for (const row of rows) {
    if (row.duplicate) {
      duplicates.push(row);
    } else if (row.needsReview) {
      needsReview.push(row);
    } else {
      ready.push(row);
    }
  }

  return { needsReview, duplicates, ready };
}

function formatLocalIsoDate(date: Date): string {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function computePeriodPresetRange(
  preset: ExpensePeriodPreset,
  referenceDate: Date = new Date()
): { from?: string; to?: string } {
  const year = referenceDate.getFullYear();
  const month = referenceDate.getMonth();

  switch (preset) {
    case 'THIS_MONTH': {
      const start = new Date(year, month, 1);
      const end = new Date(year, month + 1, 0);
      return { from: formatLocalIsoDate(start), to: formatLocalIsoDate(end) };
    }
    case 'LAST_MONTH': {
      const start = new Date(year, month - 1, 1);
      const end = new Date(year, month, 0);
      return { from: formatLocalIsoDate(start), to: formatLocalIsoDate(end) };
    }
    case 'LAST_3_MONTHS': {
      const start = new Date(year, month - 2, 1);
      const end = new Date(year, month + 1, 0);
      return { from: formatLocalIsoDate(start), to: formatLocalIsoDate(end) };
    }
    case 'YTD': {
      const start = new Date(year, 0, 1);
      const end = new Date(year, month + 1, 0);
      return { from: formatLocalIsoDate(start), to: formatLocalIsoDate(end) };
    }
    case 'ALL':
    default:
      return {};
  }
}

export function formatInsightMessage(
  insight: Pick<ExpenseInsightDto, 'code' | 'severity' | 'params'>,
  t: (key: string, vars?: Record<string, unknown>) => string
): string {
  const key = `expenses.insights.${insight.code}`;
  return t(key, insight.params ?? {});
}

export interface FilterableExpenseTx {
  merchantDisplay?: string | null;
  rawDescription?: string | null;
  merchantKey?: string | null;
  userNote?: string | null;
  category?: string | null;
  flowKind?: string | null;
  needsReview?: boolean;
  excluded?: boolean;
}

export interface ExpenseTransactionFilterOptions {
  search?: string;
  category?: string;
  flowKind?: string;
  needsReviewOnly?: boolean;
  hideExcluded?: boolean;
}

export function formatExpenseCurrency(
  amount: number,
  forceSign = false,
  currency = 'PLN',
  locale = 'pl-PL'
): string {
  const code = (currency || 'PLN').trim().toUpperCase();
  try {
    return formatCurrency(amount, forceSign, code, locale);
  } catch {
    const clean = sanitizeNearZero(amount);
    const formatted = new Intl.NumberFormat(locale, {
      minimumFractionDigits: 2,
      maximumFractionDigits: 2,
    }).format(Math.abs(clean));
    const sign = clean < 0 ? '-' : forceSign && clean > 0 ? '+' : '';
    return `${sign}${formatted} ${code}`;
  }
}

export function filterExpenseTransactions<T extends FilterableExpenseTx>(
  transactions: readonly T[],
  filters: ExpenseTransactionFilterOptions
): T[] {
  if (!transactions.length) {
    return [];
  }
  const query = filters.search?.trim().toLowerCase() ?? '';
  const category = filters.category && filters.category !== 'ALL' ? filters.category : '';
  const flowKind = filters.flowKind && filters.flowKind !== 'ALL' ? filters.flowKind : '';

  return transactions.filter((tx) => {
    if (filters.hideExcluded && tx.excluded) {
      return false;
    }
    if (filters.needsReviewOnly && !tx.needsReview) {
      return false;
    }
    if (category && tx.category !== category) {
      return false;
    }
    if (flowKind && tx.flowKind !== flowKind) {
      return false;
    }
    if (query) {
      const haystack = [tx.merchantDisplay, tx.rawDescription, tx.merchantKey, tx.userNote]
        .filter(Boolean)
        .join(' ')
        .toLowerCase();
      if (!haystack.includes(query)) {
        return false;
      }
    }
    return true;
  });
}

export interface ExpenseYearMonthCell {
  monthIndex: number;
  monthKey: string;
  shortName: string;
  monthName: string;
  hasData: boolean;
  point: MonthlyTrendPoint | null;
}

export interface ExpenseYearTotals {
  income: number;
  expenses: number;
  investmentsSavings: number;
  netCashflow: number;
  transactionCount: number;
  savingsRatePct: number | null;
}

export interface ExpenseYearGrid {
  year: number;
  months: ExpenseYearMonthCell[];
  totals: ExpenseYearTotals;
}

export interface PreviewMonthGroup {
  month: string;
  count: number;
  totalOutflow: number;
  totalInflow: number;
}

export interface ExportableExpenseTx {
  txDate: string;
  merchantDisplay: string;
  category: string;
  flowKind: string;
  amount: number;
  currency: string;
  baseAmount: number;
  baseCurrency: string;
  classificationSource: string;
  userNote?: string | null;
  excluded: boolean;
}

const MONTH_KEY_PATTERN = /^(\d{4})-(0[1-9]|1[0-2])$/;

function round2(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100;
}

export function deriveAvailableExpenseYears(
  points: readonly Pick<MonthlyTrendPoint, 'month'>[],
  referenceYear: number = new Date().getFullYear()
): number[] {
  const years = new Set<number>();
  for (const pt of points) {
    if (!pt?.month) continue;
    const match = MONTH_KEY_PATTERN.exec(pt.month.trim());
    if (match) {
      years.add(Number(match[1]));
    }
  }
  if (years.size === 0) {
    return [referenceYear];
  }
  return Array.from(years).sort((a, b) => b - a);
}

export function buildExpenseYearGrid(
  points: readonly MonthlyTrendPoint[],
  year: number,
  locale = 'pl-PL'
): ExpenseYearGrid {
  const byMonth = new Map<string, MonthlyTrendPoint>();
  for (const pt of points) {
    const key = pt?.month?.trim();
    if (key) {
      byMonth.set(key, pt);
    }
  }

  let income = 0;
  let expenses = 0;
  let investmentsSavings = 0;
  let netCashflow = 0;
  let transactionCount = 0;

  const months: ExpenseYearMonthCell[] = [];
  for (let monthIndex = 0; monthIndex < 12; monthIndex++) {
    const monthKey = `${year}-${String(monthIndex + 1).padStart(2, '0')}`;
    const dt = new Date(Date.UTC(year, monthIndex, 1));
    const shortName = new Intl.DateTimeFormat(locale, { month: 'short', timeZone: 'UTC' })
      .format(dt)
      .replace(/^./, (c) => c.toUpperCase())
      .replace(/\.$/, '');
    const monthName = new Intl.DateTimeFormat(locale, { month: 'long', timeZone: 'UTC' })
      .format(dt)
      .replace(/^./, (c) => c.toUpperCase());

    const point = byMonth.get(monthKey) ?? null;
    if (point) {
      income += point.income || 0;
      expenses += point.expenses || 0;
      investmentsSavings += point.investmentsSavings || 0;
      netCashflow += point.netCashflow || 0;
      transactionCount += point.transactionCount || 0;
    }

    months.push({
      monthIndex,
      monthKey,
      shortName,
      monthName,
      hasData: point !== null,
      point,
    });
  }

  const roundedIncome = round2(income);
  const roundedExpenses = round2(expenses);
  const roundedInvestments = round2(investmentsSavings);
  const roundedNet = round2(netCashflow);
  const savingsRatePct =
    roundedIncome > 0
      ? round2(((roundedIncome - roundedExpenses) * 100) / roundedIncome)
      : null;

  return {
    year,
    months,
    totals: {
      income: roundedIncome,
      expenses: roundedExpenses,
      investmentsSavings: roundedInvestments,
      netCashflow: roundedNet,
      transactionCount,
      savingsRatePct,
    },
  };
}

export function groupPreviewRowsByMonth(
  transactions: readonly { txDate?: string | null; amount: number }[]
): PreviewMonthGroup[] {
  const byMonth = new Map<string, { count: number; totalOutflow: number; totalInflow: number }>();
  for (const row of transactions) {
    const month = row.txDate?.slice(0, 7) ?? '';
    if (!MONTH_KEY_PATTERN.test(month)) {
      continue;
    }
    const acc = byMonth.get(month) ?? { count: 0, totalOutflow: 0, totalInflow: 0 };
    acc.count += 1;
    if (row.amount < 0) {
      acc.totalOutflow += Math.abs(row.amount);
    } else if (row.amount > 0) {
      acc.totalInflow += row.amount;
    }
    byMonth.set(month, acc);
  }

  return Array.from(byMonth.entries())
    .sort(([a], [b]) => a.localeCompare(b))
    .map(([month, acc]) => ({
      month,
      count: acc.count,
      totalOutflow: round2(acc.totalOutflow),
      totalInflow: round2(acc.totalInflow),
    }));
}

export function formatMonthDateRange(
  year: number,
  monthIndex: number
): { from: string; to: string } {
  const mm = String(monthIndex + 1).padStart(2, '0');
  const lastDay = new Date(Date.UTC(year, monthIndex + 1, 0)).getUTCDate();
  return {
    from: `${year}-${mm}-01`,
    to: `${year}-${mm}-${String(lastDay).padStart(2, '0')}`,
  };
}

const CSV_FORMULA_PREFIX_PATTERN = /^[=+\-@\t\r]/;

function escapeCsvCell(value: unknown): string {
  if (value === null || value === undefined) {
    return '';
  }
  let str = String(value);
  if (typeof value === 'string' && CSV_FORMULA_PREFIX_PATTERN.test(str)) {
    str = `'${str}`;
  }
  if (/[",\r\n]/.test(str)) {
    return `"${str.replace(/"/g, '""')}"`;
  }
  return str;
}

export function exportTransactionsCsv(transactions: readonly ExportableExpenseTx[]): string {
  const header =
    'Date,Merchant,Category,Flow,Amount,Currency,BaseAmount,BaseCurrency,Source,Note,Excluded';
  if (!transactions.length) {
    return header;
  }
  const lines = transactions.map((tx) =>
    [
      tx.txDate,
      tx.merchantDisplay,
      tx.category,
      tx.flowKind,
      tx.amount,
      tx.currency,
      tx.baseAmount,
      tx.baseCurrency,
      tx.classificationSource,
      tx.userNote ?? '',
      tx.excluded,
    ]
      .map(escapeCsvCell)
      .join(',')
  );
  return [header, ...lines].join('\n');
}
