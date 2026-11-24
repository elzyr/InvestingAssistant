import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import axios from 'axios';
import {
  CalendarDays,
  ChevronDown,
  Download,
  Loader2,
  PieChart,
  Receipt,
  RefreshCw,
  SlidersHorizontal,
  Sparkles,
  TableProperties,
  Upload,
  X,
} from 'lucide-react';
import type {
  ExpenseAnalyticsResponse,
  ExpenseCategory,
  ExpensePeriodPreset,
  ExpenseStatusResponse,
  ExpenseViewMode,
  MerchantRuleResponse,
  ReclassifyExpensesResponse,
  StatementSummaryResponse,
  UpdateExpenseTransactionRequest,
} from '../types/expense.types.ts';
import { expenseService } from '../services/expense.service.ts';
import { extractApiErrorMessage } from '../api/client.ts';
import {
  buildExpenseYearGrid,
  computePeriodPresetRange,
  deriveAvailableExpenseYears,
  exportTransactionsCsv,
  formatExpenseCurrency,
  formatMonthDateRange,
} from '../utils/expenseFormatters.ts';
import { ErrorBanner } from '../components/common/ErrorBanner.tsx';
import { ExpenseKpiGrid } from '../components/expenses/ExpenseKpiGrid.tsx';
import { ExpenseCategoryBreakdown } from '../components/expenses/ExpenseCategoryBreakdown.tsx';
import { ExpenseMonthlyTrendChart } from '../components/expenses/ExpenseMonthlyTrendChart.tsx';
import { ExpenseSmartInsights } from '../components/expenses/ExpenseSmartInsights.tsx';
import { ExpenseTransactionsTable } from '../components/expenses/ExpenseTransactionsTable.tsx';
import { ExpenseStatementsAndRulesPanel } from '../components/expenses/ExpenseStatementsAndRulesPanel.tsx';
import { ExpenseAnnualSummary } from '../components/expenses/ExpenseAnnualSummary.tsx';
import { StatementUploadModal } from '../components/expenses/StatementUploadModal.tsx';

const PRESETS: ExpensePeriodPreset[] = [
  'THIS_MONTH',
  'LAST_MONTH',
  'LAST_3_MONTHS',
  'YTD',
  'ALL',
];

const VIEW_MODES: ExpenseViewMode[] = ['ANNUAL', 'MONTH_DETAIL', 'CUSTOM'];

export const ExpensesPage: React.FC = () => {
  const { t, i18n } = useTranslation();
  const locale = i18n.language.startsWith('pl') ? 'pl-PL' : 'en-US';
  const lang = i18n.language.startsWith('pl') ? 'pl' : 'en';

  const [viewMode, setViewMode] = useState<ExpenseViewMode>('MONTH_DETAIL');
  const [selectedYear, setSelectedYear] = useState<number | null>(null);
  const [selectedMonthKey, setSelectedMonthKey] = useState<string | null>(null);

  const [preset, setPreset] = useState<ExpensePeriodPreset>('ALL');
  const [fromDate, setFromDate] = useState<string>('');
  const [toDate, setToDate] = useState<string>('');
  const [statementId, setStatementId] = useState<string>('');
  const [selectedCategory, setSelectedCategory] = useState<ExpenseCategory | ''>('');

  const [status, setStatus] = useState<ExpenseStatusResponse | null>(null);
  const [statements, setStatements] = useState<StatementSummaryResponse[]>([]);
  const [rules, setRules] = useState<MerchantRuleResponse[]>([]);
  const [analytics, setAnalytics] = useState<ExpenseAnalyticsResponse | null>(null);

  const [loading, setLoading] = useState<boolean>(true);
  const [metaLoading, setMetaLoading] = useState<boolean>(true);
  const [reclassifying, setReclassifying] = useState<boolean>(false);
  const [reclassifyNotice, setReclassifyNotice] = useState<ReclassifyExpensesResponse | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [uploadOpen, setUploadOpen] = useState<boolean>(false);
  const [metaReloadTick, setMetaReloadTick] = useState<number>(0);
  const [analyticsReloadTick, setAnalyticsReloadTick] = useState<number>(0);

  const skipScopeFetchRef = useRef<{
    scopeKey: string;
    reloadTick: number;
    lang: string;
  } | null>(null);

  const monthlyTrend = useMemo(
    () => analytics?.monthlyTrend ?? [],
    [analytics?.monthlyTrend]
  );

  const availableYears = useMemo(
    () => deriveAvailableExpenseYears(monthlyTrend, new Date().getFullYear()),
    [monthlyTrend]
  );

  const activeYear =
    selectedYear !== null && availableYears.includes(selectedYear)
      ? selectedYear
      : availableYears[0];

  const yearGrid = useMemo(
    () => buildExpenseYearGrid(monthlyTrend, activeYear, locale),
    [monthlyTrend, activeYear, locale]
  );

  const effectiveMonthKey = useMemo(() => {
    if (selectedMonthKey && selectedMonthKey.startsWith(`${activeYear}-`)) {
      return selectedMonthKey;
    }
    const latestInYear = yearGrid.months.filter((m) => m.hasData).at(-1);
    return latestInYear ? latestInYear.monthKey : null;
  }, [selectedMonthKey, activeYear, yearGrid.months]);

  const activeMonthCell = useMemo(() => {
    if (!effectiveMonthKey) {
      return yearGrid.months.at(-1) ?? null;
    }
    return yearGrid.months.find((m) => m.monthKey === effectiveMonthKey) ?? null;
  }, [yearGrid.months, effectiveMonthKey]);

  const queryScope = useMemo(() => {
    if (viewMode === 'ANNUAL') {
      return {
        from: `${activeYear}-01-01`,
        to: `${activeYear}-12-31`,
        statementId: undefined as string | undefined,
      };
    }
    if (viewMode === 'MONTH_DETAIL') {
      if (effectiveMonthKey) {
        const [yrStr, moStr] = effectiveMonthKey.split('-');
        const yr = Number(yrStr);
        const moIdx = Number(moStr) - 1;
        if (Number.isFinite(yr) && moIdx >= 0 && moIdx < 12) {
          const range = formatMonthDateRange(yr, moIdx);
          return { from: range.from, to: range.to, statementId: undefined as string | undefined };
        }
      }
      return { from: undefined, to: undefined, statementId: undefined as string | undefined };
    }
    return {
      from: fromDate || undefined,
      to: toDate || undefined,
      statementId: statementId || undefined,
    };
  }, [viewMode, activeYear, effectiveMonthKey, fromDate, toDate, statementId]);

  const scopeKey = `${queryScope.from ?? ''}|${queryScope.to ?? ''}|${queryScope.statementId ?? ''}`;
  const isInitialAutoMonthResolve =
    viewMode === 'MONTH_DETAIL' &&
    selectedYear === null &&
    selectedMonthKey === null &&
    !queryScope.from &&
    !queryScope.to;

  const triggerFullReload = useCallback(() => {
    skipScopeFetchRef.current = null;
    setMetaReloadTick((n) => n + 1);
    setAnalyticsReloadTick((n) => n + 1);
  }, []);

  const triggerMetaReload = useCallback(() => {
    setMetaReloadTick((n) => n + 1);
  }, []);

  const handleSelectPreset = (nextPreset: ExpensePeriodPreset) => {
    setPreset(nextPreset);
    const range = computePeriodPresetRange(nextPreset);
    setFromDate(range.from ?? '');
    setToDate(range.to ?? '');
  };

  const handleSelectMonth = (monthIndex: number) => {
    setSelectedCategory('');
    setSelectedMonthKey(`${activeYear}-${String(monthIndex + 1).padStart(2, '0')}`);
    setViewMode('MONTH_DETAIL');
  };

  const handleSwitchYear = (year: number) => {
    setSelectedCategory('');
    setSelectedYear(year);
    setSelectedMonthKey(null);
  };

  const handleSwitchViewMode = (mode: ExpenseViewMode) => {
    setSelectedCategory('');
    setViewMode(mode);
  };

  useEffect(() => {
    const controller = new AbortController();
    let active = true;

    const loadMetadata = async () => {
      setMetaLoading(true);
      try {
        const [statusRes, statementsRes, rulesRes] = await Promise.all([
          expenseService.getStatus(controller.signal),
          expenseService.listStatements(controller.signal),
          expenseService.listRules(controller.signal),
        ]);
        if (!active) return;
        setStatus(statusRes);
        setStatements(statementsRes);
        setRules(rulesRes);
      } catch (err) {
        if (controller.signal.aborted || axios.isCancel(err)) return;
        if (!active) return;
        setError(extractApiErrorMessage(err, t('expenses.errors.loadFailed')));
      } finally {
        if (active) {
          setMetaLoading(false);
        }
      }
    };

    void loadMetadata();
    return () => {
      active = false;
      controller.abort();
    };
  }, [metaReloadTick, t]);

  useEffect(() => {
    const skipped = skipScopeFetchRef.current;
    if (
      skipped &&
      skipped.scopeKey === scopeKey &&
      skipped.reloadTick === analyticsReloadTick &&
      skipped.lang === lang
    ) {
      skipScopeFetchRef.current = null;
      return;
    }
    skipScopeFetchRef.current = null;

    const controller = new AbortController();
    let active = true;

    const loadAnalytics = async () => {
      setLoading(true);
      setError(null);
      try {
        const res = await expenseService.getAnalytics({
          from: queryScope.from,
          to: queryScope.to,
          statementId: queryScope.statementId,
          includeTransactions: !isInitialAutoMonthResolve,
          lang,
          signal: controller.signal,
        });
        if (!active) return;

        if (isInitialAutoMonthResolve) {
          const years = deriveAvailableExpenseYears(res.monthlyTrend, new Date().getFullYear());
          const latestYear = years[0];
          const latestMonth =
            res.monthlyTrend
              .filter((m) => m.month.trim().startsWith(`${latestYear}-`))
              .at(-1)
              ?.month.trim() ??
            res.monthlyTrend.at(-1)?.month.trim() ??
            null;

          if (latestMonth) {
            const [yrStr, moStr] = latestMonth.split('-');
            const yr = Number(yrStr);
            const moIdx = Number(moStr) - 1;
            const range = formatMonthDateRange(yr, moIdx);
            const finalRes = await expenseService.getAnalytics({
              from: range.from,
              to: range.to,
              includeTransactions: true,
              lang,
              signal: controller.signal,
            });
            if (!active) return;

            skipScopeFetchRef.current = {
              scopeKey: `${range.from}|${range.to}|`,
              reloadTick: analyticsReloadTick,
              lang,
            };
            setSelectedYear(latestYear);
            setSelectedMonthKey(latestMonth);
            setAnalytics(finalRes);
            return;
          }
        }

        setAnalytics(res);
      } catch (err) {
        if (controller.signal.aborted || axios.isCancel(err)) return;
        if (!active) return;
        setError(extractApiErrorMessage(err, t('expenses.errors.loadFailed')));
      } finally {
        if (active) {
          setLoading(false);
        }
      }
    };

    void loadAnalytics();
    return () => {
      active = false;
      controller.abort();
    };
  }, [
    scopeKey,
    queryScope.from,
    queryScope.to,
    queryScope.statementId,
    isInitialAutoMonthResolve,
    analyticsReloadTick,
    lang,
    t,
  ]);

  const handleReclassify = async () => {
    setReclassifying(true);
    setError(null);
    setReclassifyNotice(null);
    try {
      const res = await expenseService.reclassify({
        from: queryScope.from,
        to: queryScope.to,
        statementId: queryScope.statementId,
      });
      setReclassifyNotice(res);
      triggerFullReload();
    } catch (err) {
      setError(extractApiErrorMessage(err, t('expenses.errors.reclassifyFailed')));
    } finally {
      setReclassifying(false);
    }
  };

  const handleExportCsv = () => {
    if (!analytics?.transactions.length) return;
    const rows =
      selectedCategory && viewMode !== 'ANNUAL'
        ? analytics.transactions.filter((tx) => tx.category === selectedCategory)
        : analytics.transactions;
    const csv = exportTransactionsCsv(rows);
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
    const url = URL.createObjectURL(blob);
    const suffix =
      viewMode === 'MONTH_DETAIL' && effectiveMonthKey
        ? effectiveMonthKey
        : viewMode === 'ANNUAL'
        ? String(activeYear)
        : `${fromDate || 'all'}_${toDate || 'all'}`;
    const link = document.createElement('a');
    link.href = url;
    link.download = `expenses-${suffix}.csv`;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  };

  const handleUpdateTransaction = async (
    txId: string,
    payload: UpdateExpenseTransactionRequest
  ) => {
    try {
      await expenseService.updateTransaction(txId, payload);
      triggerFullReload();
    } catch (err) {
      setError(extractApiErrorMessage(err, t('expenses.errors.updateFailed')));
      throw err;
    }
  };

  const handleDeleteStatement = async (id: string) => {
    try {
      await expenseService.deleteStatement(id);
      if (statementId === id) {
        setStatementId('');
      }
      if (statements.length <= 1) {
        setSelectedYear(null);
        setSelectedMonthKey(null);
      }
      triggerFullReload();
    } catch (err) {
      setError(extractApiErrorMessage(err, t('expenses.errors.deleteFailed')));
    }
  };

  const handleDeleteRule = async (id: string) => {
    try {
      await expenseService.deleteRule(id);
      triggerMetaReload();
    } catch (err) {
      setError(extractApiErrorMessage(err, t('expenses.errors.deleteFailed')));
    }
  };

  const currency = analytics?.baseCurrency || status?.baseCurrency || 'PLN';
  const hasAnyData = statements.length > 0 || (analytics?.transactions.length ?? 0) > 0;
  const isEmptyMonthCard =
    viewMode === 'MONTH_DETAIL' &&
    Boolean(activeMonthCell) &&
    !activeMonthCell?.hasData &&
    (analytics?.transactions.length ?? 0) === 0;
  const isRefreshing = loading || metaLoading;

  return (
    <div className="w-full max-w-[1920px] mx-auto px-6 lg:px-8 py-6 space-y-6">
      <div className="flex flex-col xl:flex-row xl:items-center justify-between gap-4 pb-4 border-b border-zinc-800/80">
        <div>
          <div className="flex items-center gap-2 text-xs text-zinc-400 mb-1">
            <Receipt className="w-4 h-4 text-emerald-400" />
            <span>{t('expenses.badge')}</span>
          </div>
          <h1 className="text-xl sm:text-2xl font-bold text-zinc-100 tracking-tight">
            {t('expenses.title')}
          </h1>
          <p className="text-xs sm:text-sm text-zinc-400 mt-0.5 max-w-3xl">
            {t('expenses.subtitle')}
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-2 shrink-0">
          {hasAnyData && (
            <>
              <div className="relative">
                <select
                  value={activeYear}
                  onChange={(e) => handleSwitchYear(Number(e.target.value))}
                  aria-label={t('expenses.selectYearAria')}
                  className="appearance-none bg-zinc-900 border border-zinc-700/80 text-zinc-200 text-xs sm:text-sm font-semibold rounded-lg pl-3 pr-8 py-2 cursor-pointer hover:border-zinc-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
                >
                  {availableYears.map((y) => (
                    <option key={y} value={y}>
                      {t('expenses.yearLabel', { year: y })}
                    </option>
                  ))}
                </select>
                <ChevronDown className="w-4 h-4 text-zinc-400 absolute right-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
              </div>

              <div className="inline-flex rounded-lg bg-zinc-900/90 p-1 border border-zinc-800 text-xs font-medium">
                {VIEW_MODES.map((mode) => {
                  const Icon =
                    mode === 'ANNUAL'
                      ? TableProperties
                      : mode === 'MONTH_DETAIL'
                      ? PieChart
                      : SlidersHorizontal;
                  return (
                    <button
                      type="button"
                      key={mode}
                      onClick={() => handleSwitchViewMode(mode)}
                      className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md transition cursor-pointer ${
                        viewMode === mode
                          ? 'bg-zinc-800 text-emerald-400 shadow-sm font-semibold'
                          : 'text-zinc-400 hover:text-zinc-200'
                      }`}
                    >
                      <Icon className="w-3.5 h-3.5" />
                      <span>{t(`expenses.viewModes.${mode}`)}</span>
                    </button>
                  );
                })}
              </div>

              <button
                type="button"
                disabled={reclassifying}
                onClick={handleReclassify}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg bg-zinc-900 hover:bg-zinc-800 border border-zinc-700/80 text-zinc-200 text-xs font-semibold transition disabled:opacity-50 cursor-pointer"
              >
                {reclassifying ? (
                  <Loader2 className="w-3.5 h-3.5 text-emerald-400 animate-spin" />
                ) : (
                  <Sparkles className="w-3.5 h-3.5 text-emerald-400" />
                )}
                <span>
                  {reclassifying ? t('expenses.reclassifying') : t('expenses.reclassifyAction')}
                </span>
              </button>

              <button
                type="button"
                disabled={!analytics?.transactions.length}
                onClick={handleExportCsv}
                className="inline-flex items-center gap-1.5 px-3 py-2 rounded-lg bg-zinc-900 hover:bg-zinc-800 border border-zinc-700/80 text-zinc-200 text-xs font-semibold transition disabled:opacity-40 cursor-pointer"
              >
                <Download className="w-3.5 h-3.5 text-zinc-400" />
                <span>{t('expenses.exportCsv')}</span>
              </button>
            </>
          )}

          <button
            type="button"
            onClick={triggerFullReload}
            className="p-2 rounded-lg bg-zinc-900 border border-zinc-800 text-zinc-400 hover:text-zinc-200 hover:border-zinc-700 transition cursor-pointer"
            title={t('dashboard.refreshTitle')}
          >
            <RefreshCw className={`w-4 h-4 ${isRefreshing ? 'animate-spin' : ''}`} />
          </button>

          <button
            type="button"
            onClick={() => setUploadOpen(true)}
            className="inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs sm:text-sm font-semibold shadow-sm transition cursor-pointer"
          >
            <Upload className="w-4 h-4" />
            <span>{t('expenses.importButton')}</span>
          </button>
        </div>
      </div>

      <ErrorBanner error={error} onDismiss={() => setError(null)} />

      {reclassifyNotice && (
        <div className="flex items-center justify-between gap-3 px-4 py-2.5 rounded-xl bg-emerald-950/35 border border-emerald-700/40 text-xs text-emerald-200">
          <div className="flex flex-wrap items-center gap-2">
            <Sparkles className="w-4 h-4 text-emerald-400 shrink-0" />
            <span>
              {t('expenses.reclassifySuccess', {
                processed: reclassifyNotice.processedCount,
                updated: reclassifyNotice.updatedCount,
              })}
            </span>
            {reclassifyNotice.aiWarning && (
              <span className="text-amber-300">({reclassifyNotice.aiWarning})</span>
            )}
          </div>
          <button
            type="button"
            onClick={() => setReclassifyNotice(null)}
            className="text-zinc-400 hover:text-zinc-200 cursor-pointer"
          >
            <X className="w-4 h-4" />
          </button>
        </div>
      )}

      {isRefreshing && !analytics ? (
        <div className="bg-zinc-900/40 border border-zinc-800/80 rounded-xl p-12 text-center flex flex-col items-center justify-center">
          <Loader2 className="w-8 h-8 text-emerald-400 animate-spin mb-3" />
          <span className="text-sm text-zinc-300">{t('expenses.loading')}</span>
        </div>
      ) : !hasAnyData ? (
        <div className="bg-zinc-900/40 border border-zinc-800/80 rounded-xl p-12 text-center flex flex-col items-center justify-center gap-3">
          <Receipt className="w-10 h-10 text-zinc-500" />
          <h2 className="text-base font-semibold text-zinc-200">{t('expenses.emptyTitle')}</h2>
          <p className="text-xs sm:text-sm text-zinc-400 max-w-md">{t('expenses.emptySubtitle')}</p>
          <button
            type="button"
            onClick={() => setUploadOpen(true)}
            className="mt-2 inline-flex items-center gap-2 px-4 py-2 rounded-lg bg-emerald-600 hover:bg-emerald-500 text-white text-xs sm:text-sm font-semibold transition cursor-pointer"
          >
            <Upload className="w-4 h-4" />
            <span>{t('expenses.emptyCta')}</span>
          </button>
        </div>
      ) : analytics ? (
        <>
          {viewMode !== 'CUSTOM' ? (
            <div className="bg-zinc-900/40 border border-zinc-800/80 rounded-xl p-2.5 sm:p-3 overflow-x-auto">
              <div className="flex items-center gap-1.5 min-w-[640px]">
                {yearGrid.months.map((m) => {
                  const isCurrentSelected =
                    viewMode === 'MONTH_DETAIL' && activeMonthCell?.monthIndex === m.monthIndex;
                  return (
                    <button
                      type="button"
                      key={m.monthKey}
                      onClick={() => handleSelectMonth(m.monthIndex)}
                      className={`flex-1 py-2 px-2 rounded-lg text-xs font-mono transition flex flex-col items-center justify-center gap-1 cursor-pointer ${
                        isCurrentSelected
                          ? 'bg-emerald-500/15 border border-emerald-500/40 text-emerald-300 font-bold shadow-sm'
                          : m.hasData
                          ? 'bg-zinc-800/40 hover:bg-zinc-800/80 text-zinc-300 hover:text-zinc-100 border border-zinc-800/60'
                          : 'bg-zinc-900/30 text-zinc-600 hover:text-zinc-400 border border-zinc-800/30'
                      }`}
                    >
                      <span className="font-sans font-semibold tracking-wide text-[11px]">
                        {m.shortName}
                      </span>
                      <div className="flex items-center gap-1">
                        {m.hasData && m.point ? (
                          <span
                            className={`w-1.5 h-1.5 rounded-full ${
                              m.point.netCashflow >= 0 ? 'bg-emerald-400' : 'bg-rose-400'
                            }`}
                          />
                        ) : (
                          <span className="w-1.5 h-1.5 rounded-full bg-zinc-700" />
                        )}
                        <span className="text-[10px] text-zinc-500 font-sans truncate max-w-[68px]">
                          {m.hasData && m.point
                            ? formatExpenseCurrency(m.point.expenses, false, currency, locale)
                            : '—'}
                        </span>
                      </div>
                    </button>
                  );
                })}
              </div>
            </div>
          ) : (
            <div className="flex flex-wrap items-center justify-between gap-3 bg-zinc-900/40 border border-zinc-800/80 rounded-xl p-3">
              <div className="inline-flex flex-wrap rounded-lg bg-zinc-950 p-1 border border-zinc-800 text-xs font-medium">
                {PRESETS.map((p) => (
                  <button
                    type="button"
                    key={p}
                    onClick={() => handleSelectPreset(p)}
                    className={`px-3 py-1.5 rounded-md transition cursor-pointer ${
                      preset === p
                        ? 'bg-zinc-800 text-emerald-400 font-semibold'
                        : 'text-zinc-400 hover:text-zinc-200'
                    }`}
                  >
                    {t(`expenses.presets.${p}`)}
                  </button>
                ))}
              </div>

              <div className="flex flex-wrap items-center gap-2 text-xs">
                <input
                  type="date"
                  value={fromDate}
                  onChange={(e) => {
                    setPreset('ALL');
                    setFromDate(e.target.value);
                  }}
                  className="bg-zinc-950 border border-zinc-800 text-zinc-200 rounded-lg px-2.5 py-1.5 font-mono focus:outline-none focus:border-emerald-500"
                />
                <span className="text-zinc-600">→</span>
                <input
                  type="date"
                  value={toDate}
                  onChange={(e) => {
                    setPreset('ALL');
                    setToDate(e.target.value);
                  }}
                  className="bg-zinc-950 border border-zinc-800 text-zinc-200 rounded-lg px-2.5 py-1.5 font-mono focus:outline-none focus:border-emerald-500"
                />

                {statements.length > 0 && (
                  <select
                    value={statementId}
                    onChange={(e) => {
                      setStatementId(e.target.value);
                    }}
                    className="bg-zinc-950 border border-zinc-800 text-zinc-200 rounded-lg px-2.5 py-1.5 focus:outline-none focus:border-emerald-500 max-w-[220px]"
                  >
                    <option value="">{t('expenses.allStatements')}</option>
                    {statements.map((st) => (
                      <option key={st.id} value={st.id}>
                        {st.fileName} ({st.bankProfile})
                      </option>
                    ))}
                  </select>
                )}
              </div>
            </div>
          )}

          {viewMode === 'ANNUAL' ? (
            <ExpenseAnnualSummary
              grid={yearGrid}
              currency={currency}
              selectedMonthIndex={activeMonthCell?.monthIndex ?? null}
              onSelectMonth={handleSelectMonth}
            />
          ) : (
            <>
              {viewMode === 'MONTH_DETAIL' && activeMonthCell && (
                <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 bg-zinc-900/60 border border-zinc-800/80 px-4 py-3 rounded-xl">
                  <div className="flex items-center gap-3">
                    <div className="w-9 h-9 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 flex items-center justify-center font-bold text-sm">
                      {activeMonthCell.shortName}
                    </div>
                    <div>
                      <h2 className="text-base font-bold text-zinc-100">
                        {activeMonthCell.monthName} {activeYear}
                      </h2>
                      <span className="text-xs text-zinc-400">
                        {t('expenses.monthCardSubtitle', {
                          count:
                            activeMonthCell.point?.transactionCount ?? analytics.transactions.length,
                        })}
                      </span>
                    </div>
                  </div>

                  <button
                    type="button"
                    onClick={() => handleSwitchViewMode('ANNUAL')}
                    className="text-xs text-zinc-400 hover:text-zinc-200 bg-zinc-800/60 hover:bg-zinc-800 px-3 py-1.5 rounded-lg border border-zinc-700/60 transition cursor-pointer self-start sm:self-auto"
                  >
                    {t('expenses.backToAnnual')}
                  </button>
                </div>
              )}

              {isEmptyMonthCard && activeMonthCell ? (
                <div className="bg-zinc-900/40 border border-zinc-800/80 rounded-xl p-10 text-center flex flex-col items-center justify-center gap-3">
                  <CalendarDays className="w-9 h-9 text-zinc-500" />
                  <h3 className="text-sm font-semibold text-zinc-200">
                    {t('expenses.emptyMonthTitle', {
                      month: activeMonthCell.monthName,
                      year: activeYear,
                    })}
                  </h3>
                  <p className="text-xs text-zinc-400 max-w-md">{t('expenses.emptyMonthDesc')}</p>
                </div>
              ) : (
                <>
                  <ExpenseKpiGrid kpis={analytics.kpis} currency={currency} />

                  <div className="grid grid-cols-1 xl:grid-cols-12 gap-6">
                    <div className="xl:col-span-7 space-y-6">
                      <ExpenseCategoryBreakdown
                        breakdown={analytics.categoryBreakdown}
                        topMerchants={analytics.topMerchants}
                        totalExpenses={analytics.kpis.totalExpenses}
                        currency={currency}
                        selectedCategory={selectedCategory}
                        onSelectCategory={setSelectedCategory}
                      />
                      {viewMode === 'CUSTOM' && (
                        <ExpenseMonthlyTrendChart points={analytics.monthlyTrend} currency={currency} />
                      )}
                    </div>

                    <div className="xl:col-span-5">
                      <ExpenseSmartInsights
                        insights={analytics.insights}
                        recurringCharges={analytics.recurringCharges}
                        outliers={analytics.outliers}
                        currency={currency}
                      />
                    </div>
                  </div>

                  <ExpenseTransactionsTable
                    transactions={analytics.transactions}
                    selectedCategory={selectedCategory}
                    onSelectCategory={setSelectedCategory}
                    onUpdateTransaction={handleUpdateTransaction}
                  />
                </>
              )}
            </>
          )}

          <ExpenseStatementsAndRulesPanel
            statements={statements}
            rules={rules}
            activeStatementId={viewMode === 'CUSTOM' ? statementId : ''}
            onSelectStatement={(id) => {
              setSelectedCategory('');
              setStatementId(id);
              setViewMode(id ? 'CUSTOM' : viewMode);
            }}
            onDeleteStatement={handleDeleteStatement}
            onDeleteRule={handleDeleteRule}
          />
        </>
      ) : null}

      <StatementUploadModal
        isOpen={uploadOpen}
        onClose={() => setUploadOpen(false)}
        status={status}
        onCommitted={(latestMonth) => {
          if (latestMonth) {
            const [yrStr] = latestMonth.split('-');
            const yr = Number(yrStr);
            if (Number.isFinite(yr)) {
              setSelectedYear(yr);
            }
            setSelectedMonthKey(latestMonth);
            setViewMode('MONTH_DETAIL');
          }
          triggerFullReload();
        }}
      />
    </div>
  );
};
