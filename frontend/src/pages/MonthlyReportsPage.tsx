import React, { useState, useEffect, useMemo } from 'react';
import type { ReportViewMode } from '../types/reports.types.ts';
import {
  extractAvailableYears,
  generateRealAnnualSummary,
} from '../utils/realReportsEngine.ts';
import { useAppDispatch, useAppSelector } from '../store/hooks.ts';
import {
  fetchPortfolios,
  fetchPositions,
  fetchTransactions,
} from '../store/thunks/portfolio.thunk.ts';
import { setActivePortfolioId } from '../store/slices/portfolio.slice.ts';
import { marketService } from '../services/market.service.ts';
import { AnnualSummaryTable } from '../components/reports/AnnualSummaryTable.tsx';
import { MonthDetailView } from '../components/reports/MonthDetailView.tsx';
import { MonthlyProfitBarChart } from '../components/reports/MonthlyProfitBarChart.tsx';
import {
  CalendarDays,
  TableProperties,
  PieChart,
  ChevronDown,
  Briefcase,
  Loader2,
} from 'lucide-react';

export const MonthlyReportsPage: React.FC = () => {
  const dispatch = useAppDispatch();
  const { user } = useAppSelector((state) => state.auth);
  const baseCurrency = user?.baseCurrency || 'PLN';

  const {
    activePortfolioId,
    portfolios,
    transactions,
    instruments,
    quotes,
    rates,
    historicalPrices,
    historicalSortedDates,
    loading,
  } = useAppSelector((state) => state.portfolio);

  const [cpi, setCpi] = useState<number>(3.6);
  const [monthlyCpiMap, setMonthlyCpiMap] = useState<Record<number, number>>({});
  const [selectedYear, setSelectedYear] = useState<number>(new Date().getFullYear());
  const [viewMode, setViewMode] = useState<ReportViewMode>('ANNUAL');
  const [userSelectedMonthIndex, setUserSelectedMonthIndex] = useState<number | null>(null);

  // Initialize portfolios
  useEffect(() => {
    if (portfolios.length === 0) {
      dispatch(fetchPortfolios(baseCurrency));
    }
  }, [dispatch, portfolios.length, baseCurrency]);

  // Load transactions and positions for active portfolio
  useEffect(() => {
    if (activePortfolioId) {
      dispatch(fetchPositions(activePortfolioId));
      dispatch(fetchTransactions(activePortfolioId));
    }
  }, [dispatch, activePortfolioId]);

  // Load latest CPI rate
  useEffect(() => {
    marketService
      .getInflation()
      .then((inf) => {
        if (inf && inf.currentCpi != null) {
          setCpi(inf.currentCpi);
        }
      })
      .catch(() => {});
  }, []);

  const availableYears = useMemo(
    () => extractAvailableYears(transactions),
    [transactions]
  );

  const activeYear = availableYears.length > 0 && !availableYears.includes(selectedYear)
    ? availableYears[0]
    : selectedYear;

  // Load monthly CPI history for selected year from database
  useEffect(() => {
    marketService
      .getMonthlyInflation(activeYear)
      .then((points) => {
        if (Array.isArray(points)) {
          const map: Record<number, number> = {};
          for (const p of points) {
            if (p.year === activeYear) {
              map[p.month - 1] = p.rate;
            }
          }
          setMonthlyCpiMap(map);
        }
      })
      .catch(() => {});
  }, [activeYear]);

  const activePortfolio = useMemo(
    () => portfolios.find((p) => p.id === activePortfolioId),
    [portfolios, activePortfolioId]
  );

  const isIkeAccount = useMemo(() => {
    return activePortfolio?.name?.toLowerCase().includes('ike') ?? false;
  }, [activePortfolio]);

  // Generate real data
  const summary = useMemo(() => {
    return generateRealAnnualSummary({
      transactions,
      instruments,
      quotes,
      rates,
      cpi,
      monthlyCpi: monthlyCpiMap,
      historicalPrices,
      historicalSortedDates,
      baseCurrency,
      isIkeAccount,
      year: activeYear,
    });
  }, [
    transactions,
    instruments,
    quotes,
    rates,
    historicalPrices,
    historicalSortedDates,
    cpi,
    monthlyCpiMap,
    baseCurrency,
    isIkeAccount,
    activeYear,
  ]);

  const defaultMonthIndex = summary.records.filter((r) => r.status === 'RECORDED').at(-1)?.monthIndex ?? 0;
  const selectedMonthIndex = userSelectedMonthIndex ?? defaultMonthIndex;

  const selectedRecord = useMemo(() => {
    return (
      summary.records.find((r) => r.monthIndex === selectedMonthIndex) ||
      summary.records[0]
    );
  }, [summary, selectedMonthIndex]);

  const handleSelectMonth = (monthIndex: number) => {
    setUserSelectedMonthIndex(monthIndex);
    setViewMode('MONTH_DETAIL');
  };

  return (
    <div className="w-full max-w-[1920px] mx-auto px-6 lg:px-8 py-6 space-y-6">
      {/* Page Title & Navigation Bar */}
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-4 border-b border-zinc-800/80">
        <div>
          <div className="flex items-center gap-2 text-xs text-zinc-400 mb-1">
            <CalendarDays className="w-4 h-4 text-emerald-400" />
            <span>Raporty Finansowe &bull; Rzeczywiste Transakcje</span>
          </div>
          <h1 className="text-xl sm:text-2xl font-bold text-zinc-100 tracking-tight">
            Miesięczne Karty Portfela
          </h1>
          <p className="text-xs sm:text-sm text-zinc-400 mt-0.5">
            Zestawienie wyceny, podatku Belki, realnej siły nabywczej i struktury alokacji z historii transakcji
          </p>
        </div>

        {/* Portfolio, Year & Mode Selectors */}
        <div className="flex flex-wrap items-center gap-2.5">
          {/* Portfolio Switcher (if multiple portfolios exist) */}
          {portfolios.length > 1 && (
            <div className="relative">
              <select
                value={activePortfolioId || ''}
                onChange={(e) => dispatch(setActivePortfolioId(e.target.value))}
                aria-label="Wybierz portfel"
                className="appearance-none bg-zinc-900 border border-zinc-700/80 text-zinc-200 text-xs sm:text-sm font-semibold rounded-lg pl-8 pr-8 py-2 cursor-pointer hover:border-zinc-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
              >
                {portfolios.map((p) => (
                  <option key={p.id} value={p.id}>
                    {p.name}
                  </option>
                ))}
              </select>
              <Briefcase className="w-3.5 h-3.5 text-zinc-400 absolute left-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
              <ChevronDown className="w-4 h-4 text-zinc-400 absolute right-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
            </div>
          )}

          {/* Year Selector */}
          <div className="relative">
            <select
              value={activeYear}
              onChange={(e) => {
                setSelectedYear(Number(e.target.value));
                setUserSelectedMonthIndex(null);
              }}
              aria-label="Wybierz rok raportu"
              className="appearance-none bg-zinc-900 border border-zinc-700/80 text-zinc-200 text-xs sm:text-sm font-semibold rounded-lg pl-3 pr-8 py-2 cursor-pointer hover:border-zinc-500 focus:outline-none focus:ring-1 focus:ring-emerald-500"
            >
              {availableYears.map((y) => (
                <option key={y} value={y}>
                  Rok {y}
                </option>
              ))}
            </select>
            <ChevronDown className="w-4 h-4 text-zinc-400 absolute right-2.5 top-1/2 -translate-y-1/2 pointer-events-none" />
          </div>

          {/* Mode Switcher Segmented Control */}
          <div className="inline-flex rounded-lg bg-zinc-900/90 p-1 border border-zinc-800 text-xs font-medium">
            <button
              onClick={() => setViewMode('ANNUAL')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md transition ${
                viewMode === 'ANNUAL'
                  ? 'bg-zinc-800 text-emerald-400 shadow-sm font-semibold'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              <TableProperties className="w-3.5 h-3.5" />
              <span>Podsumowanie Roczne</span>
            </button>
            <button
              onClick={() => setViewMode('MONTH_DETAIL')}
              className={`flex items-center gap-1.5 px-3 py-1.5 rounded-md transition ${
                viewMode === 'MONTH_DETAIL'
                  ? 'bg-zinc-800 text-emerald-400 shadow-sm font-semibold'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              <PieChart className="w-3.5 h-3.5" />
              <span>Karta Miesiąca</span>
            </button>
          </div>
        </div>
      </div>

      {loading && transactions.length === 0 ? (
        <div className="bg-zinc-900/40 border border-zinc-800/80 rounded-xl p-12 text-center flex flex-col items-center justify-center">
          <Loader2 className="w-8 h-8 text-emerald-400 animate-spin mb-3" />
          <span className="text-sm text-zinc-300">Wczytywanie historii transakcji i wyceny portfela...</span>
        </div>
      ) : (
        <>
          {/* Quick Month Navigation Bar (Sty ... Gru) */}
          <div className="bg-zinc-900/40 border border-zinc-800/80 rounded-xl p-2.5 sm:p-3 overflow-x-auto">
            <div className="flex items-center gap-1.5 min-w-[640px]">
              {summary.records.map((r) => {
                const isRecorded = r.status === 'RECORDED';
                const isCurrentSelected =
                  viewMode === 'MONTH_DETAIL' && selectedMonthIndex === r.monthIndex;

                return (
                  <button
                    key={r.shortName}
                    onClick={() => {
                      setUserSelectedMonthIndex(r.monthIndex);
                      setViewMode('MONTH_DETAIL');
                    }}
                    className={`flex-1 py-2 px-2 rounded-lg text-xs font-mono transition flex flex-col items-center justify-center gap-1 relative ${
                      isCurrentSelected
                        ? 'bg-emerald-500/15 border border-emerald-500/40 text-emerald-300 font-bold shadow-sm'
                        : isRecorded
                        ? 'bg-zinc-800/40 hover:bg-zinc-800/80 text-zinc-300 hover:text-zinc-100 border border-zinc-800/60'
                        : 'bg-zinc-900/30 text-zinc-600 hover:text-zinc-400 border border-zinc-800/30 cursor-pointer'
                    }`}
                  >
                    <span className="font-sans font-semibold tracking-wide text-[11px]">
                      {r.shortName}
                    </span>

                    {/* Status Dot / Return Indicator */}
                    <div className="flex items-center gap-1">
                      {isRecorded ? (
                        <span
                          className={`w-1.5 h-1.5 rounded-full ${
                            r.profitNetMom >= 0 ? 'bg-emerald-400' : 'bg-rose-400'
                          }`}
                        />
                      ) : (
                        <span className="w-1.5 h-1.5 rounded-full bg-zinc-700" />
                      )}
                      <span className="text-[10px] text-zinc-500 font-sans">
                        {isRecorded ? `${r.returnNetPct.toFixed(1)}%` : '—'}
                      </span>
                    </div>
                  </button>
                );
              })}
            </div>
          </div>

          {/* Main Content Area Based on Selected Mode */}
          {viewMode === 'ANNUAL' ? (
            <div className="space-y-6">
              {/* Month-over-Month Net Profit Chart */}
              <MonthlyProfitBarChart
                records={summary.records}
                onSelectMonth={handleSelectMonth}
                selectedMonthIndex={selectedMonthIndex}
              />

              {/* Full Annual Summary Table */}
              <AnnualSummaryTable
                summary={summary}
                onSelectMonth={handleSelectMonth}
                selectedMonthIndex={selectedMonthIndex}
              />
            </div>
          ) : (
            <div className="space-y-6">
              {/* Active Month Header with Month Switcher Pill */}
              <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 bg-zinc-900/60 border border-zinc-800/80 px-4 py-3 rounded-xl">
                <div className="flex items-center gap-3">
                  <div className="w-9 h-9 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-emerald-400 flex items-center justify-center font-bold text-sm">
                    {selectedRecord.shortName}
                  </div>
                  <div>
                    <h2 className="text-base font-bold text-zinc-100">
                      {selectedRecord.monthName} {selectedRecord.year}
                    </h2>
                    <span className="text-xs text-zinc-400">
                      Szczegółowy arkusz rozliczeniowy portfela &bull; {activePortfolio?.name || 'Główny'}
                    </span>
                  </div>
                </div>

                <div className="flex items-center gap-2">
                  <button
                    onClick={() => setViewMode('ANNUAL')}
                    className="text-xs text-zinc-400 hover:text-zinc-200 bg-zinc-800/60 hover:bg-zinc-800 px-3 py-1.5 rounded-lg border border-zinc-700/60 transition"
                  >
                    ← Wróć do tabeli rocznej
                  </button>
                </div>
              </div>

              {/* Detailed Month Breakdown & Donut */}
              <MonthDetailView record={selectedRecord} />
            </div>
          )}
        </>
      )}
    </div>
  );
};
