import { describe, it } from 'node:test';
import assert from 'node:assert/strict';
import {
  ALL_EXPENSE_CATEGORIES,
  getCategoryMeta,
  getSelectableCategoriesForAmount,
  isFlowCompatibleWithAmount,
  resolveCategoryFlowKind,
  shouldAutoRememberRule,
  groupPreviewRows,
  computePeriodPresetRange,
  formatInsightMessage,
  filterExpenseTransactions,
  deriveAvailableExpenseYears,
  buildExpenseYearGrid,
  groupPreviewRowsByMonth,
  formatMonthDateRange,
  exportTransactionsCsv,
} from './expenseFormatters.ts';

const t = (key, vars = {}) => {
  const entries = Object.entries(vars)
    .map(([k, v]) => `${k}=${v}`)
    .join(',');
  return entries ? `${key}(${entries})` : key;
};

describe('expenseFormatters', () => {
  it('returns metadata for consumption, non-consumption, and unknown categories', () => {
    const groceries = getCategoryMeta('GROCERIES');
    assert.strictEqual(groceries.isConsumption, true);
    assert.strictEqual(groceries.flowKind, 'EXPENSE');
    assert.ok(groceries.labelKey.includes('GROCERIES'));
    assert.ok(groceries.color.length > 0);

    const transfer = getCategoryMeta('INTERNAL_TRANSFER');
    assert.strictEqual(transfer.isConsumption, false);
    assert.strictEqual(transfer.flowKind, 'INTERNAL_TRANSFER');

    const savings = getCategoryMeta('INVESTMENTS_SAVINGS');
    assert.strictEqual(savings.isConsumption, false);

    const fallback = getCategoryMeta('NON_EXISTENT_CATEGORY');
    assert.ok(fallback.labelKey.length > 0);
    assert.ok(fallback.color.length > 0);
  });

  it('groups preview rows into needsReview, duplicates, and ready without duplicating counts', () => {
    const rows = [
      { id: '1', duplicate: false, needsReview: true, merchantDisplay: 'Unknown' },
      { id: '2', duplicate: true, needsReview: true, merchantDisplay: 'Costa Coffee' },
      { id: '3', duplicate: false, needsReview: false, merchantDisplay: 'Biedronka' },
      { id: '4', duplicate: true, needsReview: false, merchantDisplay: 'Zabka' },
    ];

    const grouped = groupPreviewRows(rows);
    assert.deepStrictEqual(
      grouped.needsReview.map((r) => r.id),
      ['1']
    );
    assert.deepStrictEqual(
      grouped.duplicates.map((r) => r.id),
      ['2', '4']
    );
    assert.deepStrictEqual(
      grouped.ready.map((r) => r.id),
      ['3']
    );

    const empty = groupPreviewRows([]);
    assert.deepStrictEqual(empty, { needsReview: [], duplicates: [], ready: [] });
  });

  it('computes deterministic YYYY-MM-DD period preset ranges', () => {
    const ref = new Date(2026, 9, 15);

    const thisMonth = computePeriodPresetRange('THIS_MONTH', ref);
    assert.strictEqual(thisMonth.from, '2026-10-01');
    assert.ok(thisMonth.to >= '2026-10-15' && thisMonth.to <= '2026-10-31');

    const lastMonth = computePeriodPresetRange('LAST_MONTH', ref);
    assert.deepStrictEqual(lastMonth, { from: '2026-09-01', to: '2026-09-30' });

    const ytd = computePeriodPresetRange('YTD', ref);
    assert.strictEqual(ytd.from, '2026-01-01');
    assert.ok(ytd.to >= '2026-10-15');

    const last3 = computePeriodPresetRange('LAST_3_MONTHS', ref);
    assert.strictEqual(last3.from, '2026-08-01');

    const all = computePeriodPresetRange('ALL', ref);
    assert.ok(!all.from && !all.to);
  });

  it('formats typed insights via i18n helper and handles missing params', () => {
    const msg = formatInsightMessage(
      { code: 'CONVENIENCE_DRAIN', severity: 'WARN', params: { merchant: 'Zabka', count: 6 } },
      t
    );
    assert.ok(msg.includes('CONVENIENCE_DRAIN'));
    assert.ok(msg.includes('merchant=Zabka'));
    assert.ok(msg.includes('count=6'));

    const noParams = formatInsightMessage({ code: 'EMPTY_HINT', severity: 'INFO' }, t);
    assert.ok(noParams.includes('EMPTY_HINT'));
  });

  it('filters expense transactions by search, category, flowKind, needsReviewOnly, and hideExcluded', () => {
    const txs = [
      {
        id: '1',
        merchantDisplay: 'Biedronka',
        rawDescription: 'PLATNOSC KARTA BIEDRONKA',
        category: 'GROCERIES',
        flowKind: 'EXPENSE',
        needsReview: false,
        excluded: false,
      },
      {
        id: '2',
        merchantDisplay: 'Kurs angielskiego',
        rawDescription: 'PAYU / KURS ANGIELSKIEGO',
        category: 'OTHER_EXPENSE',
        flowKind: 'EXPENSE',
        needsReview: true,
        excluded: false,
      },
      {
        id: '3',
        merchantDisplay: 'Konto wlasne',
        rawDescription: 'PRZELEW WEWNETRZNY',
        category: 'INTERNAL_TRANSFER',
        flowKind: 'INTERNAL_TRANSFER',
        needsReview: false,
        excluded: true,
      },
    ];

    assert.strictEqual(filterExpenseTransactions(txs, {}).length, 3);
    assert.deepStrictEqual(
      filterExpenseTransactions(txs, { search: 'kurs' }).map((x) => x.id),
      ['2']
    );
    assert.deepStrictEqual(
      filterExpenseTransactions(txs, { category: 'GROCERIES' }).map((x) => x.id),
      ['1']
    );
    assert.deepStrictEqual(
      filterExpenseTransactions(txs, { flowKind: 'INTERNAL_TRANSFER' }).map((x) => x.id),
      ['3']
    );
    assert.deepStrictEqual(
      filterExpenseTransactions(txs, { needsReviewOnly: true }).map((x) => x.id),
      ['2']
    );
    assert.deepStrictEqual(
      filterExpenseTransactions(txs, { hideExcluded: true }).map((x) => x.id),
      ['1', '2']
    );
    assert.deepStrictEqual(filterExpenseTransactions([], { search: 'x' }), []);
  });

  it('constrains category choices and maps flowKind safely by transaction amount sign', () => {
    const outflowCategories = getSelectableCategoriesForAmount(-500);
    assert.ok(!outflowCategories.includes('INCOME'));
    assert.ok(!outflowCategories.includes('REFUND'));
    assert.ok(outflowCategories.includes('SHOPPING'));
    assert.ok(outflowCategories.includes('INVESTMENTS_SAVINGS'));
    assert.ok(outflowCategories.includes('INTERNAL_TRANSFER'));

    const inflowCategories = getSelectableCategoriesForAmount(450);
    assert.ok(inflowCategories.includes('INCOME'));
    assert.ok(inflowCategories.includes('REFUND'));
    assert.ok(inflowCategories.includes('SHOPPING'));
    assert.strictEqual(inflowCategories.length, 27);

    assert.strictEqual(isFlowCompatibleWithAmount('EXPENSE', 450), false);
    assert.strictEqual(isFlowCompatibleWithAmount('INCOME', -500), false);
    assert.strictEqual(isFlowCompatibleWithAmount('REFUND', -500), false);
    assert.strictEqual(isFlowCompatibleWithAmount('REFUND', 450), true);
    assert.strictEqual(isFlowCompatibleWithAmount('EXPENSE', -450), true);
    assert.strictEqual(isFlowCompatibleWithAmount('INCOME', 450), true);
    assert.strictEqual(isFlowCompatibleWithAmount('INTERNAL_TRANSFER', -100), true);
    assert.strictEqual(isFlowCompatibleWithAmount('INTERNAL_TRANSFER', 100), true);
    assert.strictEqual(isFlowCompatibleWithAmount('INVESTMENT_SAVING', -100), true);
    assert.strictEqual(isFlowCompatibleWithAmount('INVESTMENT_SAVING', 100), true);

    assert.strictEqual(resolveCategoryFlowKind('SHOPPING', 450), 'REFUND');
    assert.strictEqual(resolveCategoryFlowKind('GROCERIES', 50), 'REFUND');
    assert.strictEqual(resolveCategoryFlowKind('SHOPPING', -450), 'EXPENSE');
    assert.strictEqual(resolveCategoryFlowKind('INCOME', 5000), 'INCOME');
    assert.strictEqual(resolveCategoryFlowKind('REFUND', 450), 'REFUND');
    assert.strictEqual(resolveCategoryFlowKind('INTERNAL_TRANSFER', 100), 'INTERNAL_TRANSFER');
    assert.strictEqual(resolveCategoryFlowKind('INVESTMENTS_SAVINGS', 100), 'INVESTMENT_SAVING');

    assert.strictEqual(shouldAutoRememberRule('SHOPPING', 'REFUND', 200), false);
    assert.strictEqual(shouldAutoRememberRule('REFUND', 'INCOME', 200), false);
    assert.strictEqual(shouldAutoRememberRule('INCOME', 'REFUND', 200), true);
    assert.strictEqual(shouldAutoRememberRule('SHOPPING', 'OTHER_EXPENSE', -200), true);
    assert.strictEqual(shouldAutoRememberRule('SHOPPING', 'SHOPPING', -200), false);
  });

  it('exposes all 27 granular categories with distinct colors and valid flow metadata', () => {
    const expectedAll = [
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

    assert.strictEqual(ALL_EXPENSE_CATEGORIES.length, 27);
    assert.deepStrictEqual(new Set(ALL_EXPENSE_CATEGORIES), new Set(expectedAll));

    const colors = new Set();
    for (const cat of expectedAll) {
      const meta = getCategoryMeta(cat);
      assert.strictEqual(meta.category, cat);
      assert.strictEqual(meta.labelKey, `expenses.categories.${cat}`);
      assert.ok(meta.color.startsWith('#') && meta.color.length >= 4);
      colors.add(meta.color.toLowerCase());
    }
    assert.strictEqual(colors.size, 27);

    const outflowCats = getSelectableCategoriesForAmount(-100);
    assert.strictEqual(outflowCats.length, 25);
    assert.ok(outflowCats.includes('UTILITIES'));
    assert.ok(outflowCats.includes('TELECOM'));
    assert.ok(outflowCats.includes('CLOTHING'));
    assert.ok(outflowCats.includes('BEAUTY_CARE'));
    assert.ok(outflowCats.includes('ELECTRONICS'));
    assert.ok(outflowCats.includes('PETS'));
    assert.ok(outflowCats.includes('GIFTS'));
    assert.strictEqual(resolveCategoryFlowKind('CLOTHING', 250), 'REFUND');
    assert.strictEqual(resolveCategoryFlowKind('UTILITIES', -250), 'EXPENSE');
  });

  it('derives descending available years and builds 12-month expense year grids', () => {
    assert.deepStrictEqual(deriveAvailableExpenseYears([], 2026), [2026]);
    assert.deepStrictEqual(
      deriveAvailableExpenseYears([{ month: 'invalid' }], 2025),
      [2025]
    );

    const points = [
      {
        month: '2024-03',
        income: 5000,
        expenses: 2000,
        investmentsSavings: 500,
        netCashflow: 3000,
        transactionCount: 10,
        topCategory: 'GROCERIES',
        topCategoryAmount: 900,
        savingsRatePct: 60,
        momExpensesChangePct: null,
      },
      {
        month: '2026-10',
        income: 6000,
        expenses: 3000,
        investmentsSavings: 1000,
        netCashflow: 3000,
        transactionCount: 14,
        topCategory: 'HOUSING',
        topCategoryAmount: 2000,
        savingsRatePct: 50,
        momExpensesChangePct: 20,
      },
      {
        month: '2026-01',
        income: 4000,
        expenses: 1000,
        investmentsSavings: 500,
        netCashflow: 3000,
        transactionCount: 6,
        topCategory: 'UTILITIES',
        topCategoryAmount: 400,
        savingsRatePct: 75,
        momExpensesChangePct: null,
      },
      {
        month: '2025-07',
        income: 0,
        expenses: 800,
        investmentsSavings: 0,
        netCashflow: -800,
        transactionCount: 3,
        topCategory: 'TRAVEL',
        topCategoryAmount: 800,
        savingsRatePct: null,
        momExpensesChangePct: null,
      },
    ];

    assert.deepStrictEqual(deriveAvailableExpenseYears(points, 2026), [2026, 2025, 2024]);

    const grid2026 = buildExpenseYearGrid(points, 2026, 'en-US');
    assert.strictEqual(grid2026.months.length, 12);
    assert.strictEqual(grid2026.months[0].monthIndex, 0);
    assert.strictEqual(grid2026.months[0].monthKey, '2026-01');
    assert.strictEqual(grid2026.months[0].hasData, true);
    assert.strictEqual(grid2026.months[0].point.expenses, 1000);
    assert.ok(grid2026.months[0].shortName.length > 0);
    assert.ok(grid2026.months[0].monthName.length > 0);

    assert.strictEqual(grid2026.months[1].monthIndex, 1);
    assert.strictEqual(grid2026.months[1].monthKey, '2026-02');
    assert.strictEqual(grid2026.months[1].hasData, false);
    assert.strictEqual(grid2026.months[1].point, null);

    assert.strictEqual(grid2026.months[9].monthKey, '2026-10');
    assert.strictEqual(grid2026.months[9].hasData, true);
    assert.strictEqual(grid2026.months[9].point.topCategory, 'HOUSING');

    assert.strictEqual(grid2026.totals.income, 10000);
    assert.strictEqual(grid2026.totals.expenses, 4000);
    assert.strictEqual(grid2026.totals.investmentsSavings, 1500);
    assert.strictEqual(grid2026.totals.netCashflow, 6000);
    assert.strictEqual(grid2026.totals.transactionCount, 20);
    assert.strictEqual(grid2026.totals.savingsRatePct, 60);

    const grid2025 = buildExpenseYearGrid(points, 2025, 'en-US');
    assert.strictEqual(grid2025.totals.income, 0);
    assert.strictEqual(grid2025.totals.expenses, 800);
    assert.strictEqual(grid2025.totals.savingsRatePct, null);
  });

  it('groups preview rows by month, formats month date ranges, and exports RFC-4180 CSV', () => {
    assert.deepStrictEqual(groupPreviewRowsByMonth([]), []);

    const previewRows = [
      { txDate: '2026-10-05', amount: -120.5 },
      { txDate: '2026-08-14', amount: -50.25 },
      { txDate: '2026-10-01', amount: 5000 },
      { txDate: '2026-08-20', amount: 250 },
      { txDate: '2026-09-10', amount: -300 },
    ];

    assert.deepStrictEqual(groupPreviewRowsByMonth(previewRows), [
      { month: '2026-08', count: 2, totalOutflow: 50.25, totalInflow: 250 },
      { month: '2026-09', count: 1, totalOutflow: 300, totalInflow: 0 },
      { month: '2026-10', count: 2, totalOutflow: 120.5, totalInflow: 5000 },
    ]);

    assert.deepStrictEqual(formatMonthDateRange(2026, 0), { from: '2026-01-01', to: '2026-01-31' });
    assert.deepStrictEqual(formatMonthDateRange(2024, 1), { from: '2024-02-01', to: '2024-02-29' });
    assert.deepStrictEqual(formatMonthDateRange(2026, 1), { from: '2026-02-01', to: '2026-02-28' });
    assert.deepStrictEqual(formatMonthDateRange(2026, 11), { from: '2026-12-01', to: '2026-12-31' });

    const expectedHeader = 'Date,Merchant,Category,Flow,Amount,Currency,BaseAmount,BaseCurrency,Source,Note,Excluded';
    assert.strictEqual(exportTransactionsCsv([]).trim(), expectedHeader);

    const csv = exportTransactionsCsv([
      {
        txDate: '2026-10-05',
        merchantDisplay: 'Sklep, "Alfa"',
        category: 'CLOTHING',
        flowKind: 'EXPENSE',
        amount: -199.99,
        currency: 'PLN',
        baseAmount: -199.99,
        baseCurrency: 'PLN',
        classificationSource: 'RULE_ENGINE',
        userNote: 'Line 1\nLine 2',
        excluded: false,
      },
      {
        txDate: '2026-10-06',
        merchantDisplay: 'Pracodawca',
        category: 'INCOME',
        flowKind: 'INCOME',
        amount: 5000,
        currency: 'PLN',
        baseAmount: 5000,
        baseCurrency: 'PLN',
        classificationSource: 'USER_OVERRIDE',
        userNote: null,
        excluded: true,
      },
    ]);

    const lines = csv.split(/\r?\n/);
    assert.strictEqual(lines[0], expectedHeader);
    assert.ok(csv.includes('2026-10-05,"Sklep, ""Alfa""",CLOTHING,EXPENSE,-199.99,PLN,-199.99,PLN,RULE_ENGINE,"Line 1\nLine 2",false'));
    assert.ok(csv.includes('2026-10-06,Pracodawca,INCOME,INCOME,5000,PLN,5000,PLN,USER_OVERRIDE,,true'));
  });
});
