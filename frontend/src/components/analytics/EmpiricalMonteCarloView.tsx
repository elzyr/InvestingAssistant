import { useState, useEffect, useMemo, useId } from 'react';
import {
  TrendingUp,
  Activity,
  Percent,
  Target,
  Coins,
  ShieldCheck,
  Sparkles,
  Layers,
} from 'lucide-react';
import { marketService } from '../../services/market.service.ts';
import type {
  PortfolioStatsResponse,
  MonteCarloResponse,
  HoldingItem,
} from '../../types/analytics.types.ts';

interface EmpiricalMonteCarloViewProps {
  equityHoldings: Array<{ symbol: string; totalValue: number }>;
  currentBondVal: number;
  currentEquityVal: number;
  baseCurrency: string;
  isIkeAccount: boolean;
  weightedBondRate: number;
  defaultMonthlyDca: number;
}

export const EmpiricalMonteCarloView = ({
  equityHoldings,
  currentBondVal,
  currentEquityVal,
  baseCurrency,
  isIkeAccount: initialIsIke,
  weightedBondRate,
  defaultMonthlyDca,
}: EmpiricalMonteCarloViewProps) => {
  const gradientP90Id = useId();
  const gradientP50Id = useId();

  const totalCurrentVal = currentEquityVal + currentBondVal;
  const initialInitialAmount = totalCurrentVal > 0 ? Math.round(totalCurrentVal) : 50000;
  const initialEquityWeight =
    totalCurrentVal > 0 ? Math.round((currentEquityVal / totalCurrentVal) * 100) / 100 : 0.8;

  const [initialAmount, setInitialAmount] = useState<number>(initialInitialAmount);
  const [monthlyContribution, setMonthlyContribution] = useState<number>(defaultMonthlyDca || 1500);
  const [years, setYears] = useState<number>(15);
  const [equityWeight, setEquityWeight] = useState<number>(initialEquityWeight);
  const [isTaxExempt, setIsTaxExempt] = useState<boolean>(initialIsIke);
  const [targetGoal, setTargetGoal] = useState<number>(500000);

  const [stats, setStats] = useState<PortfolioStatsResponse | null>(null);
  const [mcResult, setMcResult] = useState<MonteCarloResponse | null>(null);
  const [loadingStats, setLoadingStats] = useState<boolean>(false);
  const [loadingSimulation, setLoadingSimulation] = useState<boolean>(false);
  const [hoveredIndex, setHoveredIndex] = useState<number | null>(null);

  const formattedHoldings: HoldingItem[] = useMemo(() => {
    if (equityHoldings.length === 0) {
      return [{ symbol: 'VWCE.DE', weight: 1.0, value: 50000 }];
    }
    const totalEq = equityHoldings.reduce((sum, h) => sum + h.totalValue, 0);
    return equityHoldings.map((h) => ({
      symbol: h.symbol,
      weight: totalEq > 0 ? h.totalValue / totalEq : 1.0 / equityHoldings.length,
      value: h.totalValue,
    }));
  }, [equityHoldings]);

  useEffect(() => {
    let isCancelled = false;
    setLoadingStats(true);

    marketService
      .getPortfolioStats({ holdings: formattedHoldings })
      .then((data) => {
        if (!isCancelled) {
          setStats(data);
        }
      })
      .catch((err) => {
        console.error('Failed to fetch portfolio stats:', err);
      })
      .finally(() => {
        if (!isCancelled) setLoadingStats(false);
      });

    return () => {
      isCancelled = true;
    };
  }, [formattedHoldings]);

  useEffect(() => {
    if (!stats) return;

    let isCancelled = false;
    const timer = setTimeout(() => {
      setLoadingSimulation(true);
      const bondWeight = Math.max(0, 1.0 - equityWeight);

      marketService
        .runMonteCarlo({
          initialAmount,
          monthlyContribution,
          years,
          simulationsCount: 10000,
          equityWeight,
          equityReturn: stats.weightedReturn || 0.08,
          equityVolatility: stats.weightedVolatility || 0.15,
          equityTer: stats.weightedTer || 0.002,
          bondWeight,
          bondRate: weightedBondRate || 0.06,
          isTaxExempt,
          targetGoal: targetGoal > 0 ? targetGoal : undefined,
        })
        .then((data) => {
          if (!isCancelled) {
            setMcResult(data);
          }
        })
        .catch((err) => {
          console.error('Monte Carlo simulation failed:', err);
        })
        .finally(() => {
          if (!isCancelled) setLoadingSimulation(false);
        });
    }, 250);

    return () => {
      isCancelled = true;
      clearTimeout(timer);
    };
  }, [
    stats,
    initialAmount,
    monthlyContribution,
    years,
    equityWeight,
    weightedBondRate,
    isTaxExempt,
    targetGoal,
  ]);

  const summary = mcResult?.summary;
  const timeline = mcResult?.timeline || [];

  const maxVal = useMemo(() => {
    if (timeline.length === 0) return 100000;
    const peak = Math.max(...timeline.map((p) => p.p90));
    return Math.max(1000, isFinite(peak) ? peak * 1.1 : 100000);
  }, [timeline]);

  const svgWidth = 900;
  const svgHeight = 340;
  const padLeft = 70;
  const padRight = 30;
  const padTop = 20;
  const padBottom = 40;
  const plotW = svgWidth - padLeft - padRight;
  const plotH = svgHeight - padTop - padBottom;

  const pointsP90 = useMemo(() => {
    if (timeline.length === 0) return '';
    return timeline
      .map((p, idx) => {
        const x = padLeft + (idx / (timeline.length - 1 || 1)) * plotW;
        const y = padTop + plotH - (p.p90 / maxVal) * plotH;
        return `${x},${y}`;
      })
      .join(' ');
  }, [timeline, maxVal, plotW, plotH, padLeft, padTop]);

  const pointsP50 = useMemo(() => {
    if (timeline.length === 0) return '';
    return timeline
      .map((p, idx) => {
        const x = padLeft + (idx / (timeline.length - 1 || 1)) * plotW;
        const y = padTop + plotH - (p.p50 / maxVal) * plotH;
        return `${x},${y}`;
      })
      .join(' ');
  }, [timeline, maxVal, plotW, plotH, padLeft, padTop]);

  const pointsP10 = useMemo(() => {
    if (timeline.length === 0) return '';
    return timeline
      .map((p, idx) => {
        const x = padLeft + (idx / (timeline.length - 1 || 1)) * plotW;
        const y = padTop + plotH - (p.p10 / maxVal) * plotH;
        return `${x},${y}`;
      })
      .join(' ');
  }, [timeline, maxVal, plotW, plotH, padLeft, padTop]);

  const pointsInvested = useMemo(() => {
    if (timeline.length === 0) return '';
    return timeline
      .map((p, idx) => {
        const x = padLeft + (idx / (timeline.length - 1 || 1)) * plotW;
        const y = padTop + plotH - (p.invested / maxVal) * plotH;
        return `${x},${y}`;
      })
      .join(' ');
  }, [timeline, maxVal, plotW, plotH, padLeft, padTop]);

  const fanAreaP90toP10 = useMemo(() => {
    if (timeline.length === 0) return '';
    const top = timeline.map((p, idx) => {
      const x = padLeft + (idx / (timeline.length - 1 || 1)) * plotW;
      const y = padTop + plotH - (p.p90 / maxVal) * plotH;
      return `${x},${y}`;
    });
    const bottom = timeline
      .slice()
      .reverse()
      .map((p, revIdx) => {
        const idx = timeline.length - 1 - revIdx;
        const x = padLeft + (idx / (timeline.length - 1 || 1)) * plotW;
        const y = padTop + plotH - (p.p10 / maxVal) * plotH;
        return `${x},${y}`;
      });
    return `M ${top.join(' L ')} L ${bottom.join(' L ')} Z`;
  }, [timeline, maxVal, plotW, plotH, padLeft, padTop]);

  const activeHoverPoint = hoveredIndex !== null && timeline[hoveredIndex] ? timeline[hoveredIndex] : null;

  return (
    <div className="space-y-6">
      {/* Top Banner / Explanation */}
      <div className="bg-slate-900/60 border border-slate-800 rounded-2xl p-5 backdrop-blur-sm">
        <div className="flex flex-col md:flex-row md:items-center justify-between gap-4">
          <div>
            <div className="flex items-center gap-2 mb-1">
              <span className="p-1.5 rounded-lg bg-indigo-500/10 text-indigo-400">
                <Sparkles className="w-5 h-5" />
              </span>
              <h2 className="text-xl font-bold text-white tracking-wide">
                Empiryczna Symulacja Monte Carlo ETF
              </h2>
            </div>
            <p className="text-sm text-slate-400 max-w-3xl">
              Model probabilistyczny oparty na stochastycznym rachunku różniczkowym Itô (GBM).
              Parametry $\mu$ (oczekiwany zwrot) i $\sigma$ (zmienność) są obliczane bezpośrednio z
              historycznych sesji Twoich instrumentów ETF z uwzględnieniem kosztów zarządzania (TER) i tarcia prowizyjnego.
            </p>
          </div>
          {loadingStats && (
            <div className="flex items-center gap-2 text-xs text-amber-400 bg-amber-500/10 border border-amber-500/20 px-3 py-1.5 rounded-xl">
              <div className="w-3 h-3 border-2 border-amber-400 border-t-transparent rounded-full animate-spin" />
              Pobieranie notowań historycznych...
            </div>
          )}
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-2 sm:grid-cols-3 lg:grid-cols-5 gap-3.5">
        <div className="bg-slate-900/60 border border-slate-800/80 rounded-2xl p-4">
          <div className="flex items-center justify-between text-slate-400 text-xs mb-1">
            <span>Średni ważony TER</span>
            <Percent className="w-4 h-4 text-rose-400" />
          </div>
          <div className="text-xl font-bold text-white">
            {stats ? `${(stats.weightedTer * 100).toFixed(2)}%` : '---'}
          </div>
          <div className="text-[11px] text-rose-400 mt-1 flex items-center gap-1 font-medium">
            <span>Utrata przez TER:</span>
            <span>
              {summary ? `~${Math.round(summary.totalFeeDrag).toLocaleString('pl-PL')} ${baseCurrency}` : '---'}
            </span>
          </div>
        </div>

        <div className="bg-slate-900/60 border border-slate-800/80 rounded-2xl p-4">
          <div className="flex items-center justify-between text-slate-400 text-xs mb-1">
            <span>Ważony wskaźnik C/Z (P/E)</span>
            <Layers className="w-4 h-4 text-cyan-400" />
          </div>
          <div className="text-xl font-bold text-white">
            {stats ? stats.weightedPe.toFixed(1) : '---'}
          </div>
          <div className="text-[11px] text-slate-400 mt-1">Wycena fundamentalna portfela</div>
        </div>

        <div className="bg-slate-900/60 border border-slate-800/80 rounded-2xl p-4">
          <div className="flex items-center justify-between text-slate-400 text-xs mb-1">
            <span>Zwrot roczny (μ)</span>
            <TrendingUp className="w-4 h-4 text-emerald-400" />
          </div>
          <div className="text-xl font-bold text-emerald-400">
            {stats ? `${(stats.weightedReturn * 100).toFixed(2)}%` : '---'}
          </div>
          <div className="text-[11px] text-slate-400 mt-1">Średnia geometryczna sesji</div>
        </div>

        <div className="bg-slate-900/60 border border-slate-800/80 rounded-2xl p-4">
          <div className="flex items-center justify-between text-slate-400 text-xs mb-1">
            <span>Zmienność roczna (σ)</span>
            <Activity className="w-4 h-4 text-amber-400" />
          </div>
          <div className="text-xl font-bold text-amber-400">
            {stats ? `${(stats.weightedVolatility * 100).toFixed(2)}%` : '---'}
          </div>
          <div className="text-[11px] text-slate-400 mt-1">Uwzględnia macierz kowariancji</div>
        </div>

        <div className="bg-slate-900/60 border border-slate-800/80 rounded-2xl p-4 col-span-2 sm:col-span-1">
          <div className="flex items-center justify-between text-slate-400 text-xs mb-1">
            <span>Współczynnik Beta (β)</span>
            <Target className="w-4 h-4 text-purple-400" />
          </div>
          <div className="text-xl font-bold text-white">
            {stats ? stats.portfolioBeta.toFixed(2) : '---'}
          </div>
          <div className="text-[11px] text-slate-400 mt-1">Względem rynku globalnego</div>
        </div>
      </div>

      {/* Main Interactive Workspace: Controls & Fan Chart */}
      <div className="grid grid-cols-1 lg:grid-cols-4 gap-6">
        {/* Controls Column */}
        <div className="lg:col-span-1 bg-slate-900/60 border border-slate-800 rounded-2xl p-5 space-y-4">
          <h3 className="text-sm font-semibold text-white uppercase tracking-wider flex items-center gap-2">
            <Coins className="w-4 h-4 text-indigo-400" />
            Parametry Projekcji
          </h3>

          <div>
            <label className="block text-xs text-slate-400 mb-1">Kapitał początkowy ({baseCurrency})</label>
            <input
              type="number"
              value={initialAmount}
              onChange={(e) => setInitialAmount(Math.max(0, Number(e.target.value)))}
              className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500"
            />
          </div>

          <div>
            <label className="block text-xs text-slate-400 mb-1">Dopłata miesięczna ({baseCurrency})</label>
            <input
              type="number"
              value={monthlyContribution}
              onChange={(e) => setMonthlyContribution(Math.max(0, Number(e.target.value)))}
              className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500"
            />
          </div>

          <div>
            <div className="flex justify-between text-xs text-slate-400 mb-1">
              <span>Horyzont czasowy</span>
              <span className="text-indigo-400 font-bold">{years} lat</span>
            </div>
            <input
              type="range"
              min={1}
              max={35}
              value={years}
              onChange={(e) => setYears(Number(e.target.value))}
              className="w-full accent-indigo-500 cursor-pointer"
            />
          </div>

          <div>
            <div className="flex justify-between text-xs text-slate-400 mb-1">
              <span>Alokacja Akcje / Obligacje</span>
              <span className="text-cyan-400 font-bold">
                {Math.round(equityWeight * 100)}% / {Math.round((1 - equityWeight) * 100)}%
              </span>
            </div>
            <input
              type="range"
              min={0}
              max={100}
              value={Math.round(equityWeight * 100)}
              onChange={(e) => setEquityWeight(Number(e.target.value) / 100)}
              className="w-full accent-cyan-500 cursor-pointer"
            />
          </div>

          <div>
            <label className="block text-xs text-slate-400 mb-1">Cel finansowy ({baseCurrency})</label>
            <input
              type="number"
              value={targetGoal}
              onChange={(e) => setTargetGoal(Math.max(0, Number(e.target.value)))}
              className="w-full bg-slate-950 border border-slate-800 rounded-xl px-3 py-2 text-sm text-white font-mono focus:outline-none focus:border-indigo-500"
            />
          </div>

          <div className="pt-2 border-t border-slate-800">
            <button
              type="button"
              onClick={() => setIsTaxExempt(!isTaxExempt)}
              className={`w-full flex items-center justify-between px-3 py-2.5 rounded-xl border text-xs font-medium transition-all ${
                isTaxExempt
                  ? 'bg-emerald-500/10 border-emerald-500/30 text-emerald-400'
                  : 'bg-slate-950 border-slate-800 text-slate-400 hover:border-slate-700'
              }`}
            >
              <span className="flex items-center gap-2">
                <ShieldCheck className="w-4 h-4" />
                Konto emerytalne (IKE/IKZE)
              </span>
              <span className="font-bold">{isTaxExempt ? 'Brak Belki' : 'Podatek 19%'}</span>
            </button>
          </div>

          {summary && targetGoal > 0 && (
            <div className="bg-slate-950/80 border border-slate-800/80 rounded-xl p-3 text-xs">
              <div className="text-slate-400 mb-1">Prawdopodobieństwo osiągnięcia celu:</div>
              <div className="flex items-center justify-between">
                <span className="text-lg font-bold text-indigo-400">
                  {(summary.successRate * 100).toFixed(1)}%
                </span>
                <span
                  className={`px-2 py-0.5 rounded text-[10px] font-semibold ${
                    summary.successRate >= 0.75
                      ? 'bg-emerald-500/20 text-emerald-300'
                      : summary.successRate >= 0.5
                      ? 'bg-amber-500/20 text-amber-300'
                      : 'bg-rose-500/20 text-rose-300'
                  }`}
                >
                  {summary.successRate >= 0.75 ? 'Wysokie' : summary.successRate >= 0.5 ? 'Średnie' : 'Niskie'}
                </span>
              </div>
            </div>
          )}
        </div>

        {/* Fan Chart Column */}
        <div className="lg:col-span-3 bg-slate-900/60 border border-slate-800 rounded-2xl p-5 flex flex-col justify-between">
          <div>
            <div className="flex flex-wrap items-center justify-between gap-3 mb-4">
              <div>
                <h3 className="text-base font-bold text-white flex items-center gap-2">
                  <span>Wachlarz Trajektorii Kapitału (P10 - P50 - P90)</span>
                </h3>
                <p className="text-xs text-slate-400">
                  2 000 symulowanych ścieżek rynkowych z uwzględnieniem zmienności i dragu TER
                </p>
              </div>

              {/* Legend */}
              <div className="flex flex-wrap items-center gap-3 text-xs">
                <div className="flex items-center gap-1.5 text-emerald-400">
                  <span className="w-3 h-0.5 bg-emerald-400 rounded-full" />
                  <span>P90 (Optymistyczny)</span>
                </div>
                <div className="flex items-center gap-1.5 text-indigo-400">
                  <span className="w-3 h-0.5 bg-indigo-400 rounded-full" />
                  <span>P50 (Mediana)</span>
                </div>
                <div className="flex items-center gap-1.5 text-rose-400">
                  <span className="w-3 h-0.5 bg-rose-400 rounded-full" />
                  <span>P10 (Pesymistyczny)</span>
                </div>
                <div className="flex items-center gap-1.5 text-slate-400">
                  <span className="w-3 h-0.5 bg-slate-500 border-dashed border-b rounded-full" />
                  <span>Wpłacony kapitał</span>
                </div>
              </div>
            </div>

            {/* Interactive SVG Chart */}
            <div className="relative w-full overflow-hidden">
              {loadingSimulation && (
                <div className="absolute inset-0 bg-slate-950/40 backdrop-blur-[2px] z-10 flex items-center justify-center">
                  <div className="flex items-center gap-2 text-indigo-400 text-sm font-medium">
                    <div className="w-4 h-4 border-2 border-indigo-400 border-t-transparent rounded-full animate-spin" />
                    Kalkulacja 2 000 ścieżek Monte Carlo...
                  </div>
                </div>
              )}

              <svg
                viewBox={`0 0 ${svgWidth} ${svgHeight}`}
                className="w-full h-auto select-none"
                onMouseLeave={() => setHoveredIndex(null)}
              >
                <defs>
                  <linearGradient id={gradientP90Id} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#10b981" stopOpacity="0.18" />
                    <stop offset="100%" stopColor="#6366f1" stopOpacity="0.02" />
                  </linearGradient>
                  <linearGradient id={gradientP50Id} x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="#6366f1" stopOpacity="0.25" />
                    <stop offset="100%" stopColor="#6366f1" stopOpacity="0.0" />
                  </linearGradient>
                </defs>

                {/* Horizontal Grid lines */}
                {[0, 0.25, 0.5, 0.75, 1.0].map((frac) => {
                  const y = padTop + plotH * (1 - frac);
                  const val = Math.round(maxVal * frac);
                  return (
                    <g key={frac}>
                      <line
                        x1={padLeft}
                        y1={y}
                        x2={svgWidth - padRight}
                        y2={y}
                        stroke="#1e293b"
                        strokeDasharray="4 4"
                      />
                      <text
                        x={padLeft - 8}
                        y={y + 4}
                        fill="#64748b"
                        fontSize="10"
                        textAnchor="end"
                        fontFamily="monospace"
                      >
                        {(val / 1000).toFixed(0)}k
                      </text>
                    </g>
                  );
                })}

                {/* Vertical year lines */}
                {timeline.map((p, idx) => {
                  if (timeline.length > 15 && idx % 2 !== 0 && idx !== timeline.length - 1) return null;
                  const x = padLeft + (idx / (timeline.length - 1 || 1)) * plotW;
                  return (
                    <g key={p.year}>
                      <line
                        x1={x}
                        y1={padTop}
                        x2={x}
                        y2={padTop + plotH}
                        stroke="#1e293b"
                        strokeDasharray="2 4"
                      />
                      <text
                        x={x}
                        y={padTop + plotH + 20}
                        fill="#64748b"
                        fontSize="10"
                        textAnchor="middle"
                      >
                        R{p.year}
                      </text>
                    </g>
                  );
                })}

                {/* P90 to P10 Fan area */}
                {fanAreaP90toP10 && <path d={fanAreaP90toP10} fill={`url(#${gradientP90Id})`} />}

                {/* Lines */}
                {pointsInvested && (
                  <polyline
                    points={pointsInvested}
                    fill="none"
                    stroke="#94a3b8"
                    strokeWidth="1.5"
                    strokeDasharray="4 4"
                  />
                )}
                {pointsP10 && (
                  <polyline
                    points={pointsP10}
                    fill="none"
                    stroke="#f43f5e"
                    strokeWidth="2"
                    strokeLinecap="round"
                  />
                )}
                {pointsP50 && (
                  <polyline
                    points={pointsP50}
                    fill="none"
                    stroke="#818cf8"
                    strokeWidth="2.5"
                    strokeLinecap="round"
                  />
                )}
                {pointsP90 && (
                  <polyline
                    points={pointsP90}
                    fill="none"
                    stroke="#34d399"
                    strokeWidth="2"
                    strokeLinecap="round"
                  />
                )}

                {/* Target Goal reference line */}
                {targetGoal > 0 && targetGoal <= maxVal && (
                  <g>
                    <line
                      x1={padLeft}
                      y1={padTop + plotH - (targetGoal / maxVal) * plotH}
                      x2={svgWidth - padRight}
                      y2={padTop + plotH - (targetGoal / maxVal) * plotH}
                      stroke="#f59e0b"
                      strokeWidth="1.2"
                      strokeDasharray="3 3"
                    />
                    <text
                      x={svgWidth - padRight - 5}
                      y={padTop + plotH - (targetGoal / maxVal) * plotH - 5}
                      fill="#f59e0b"
                      fontSize="9"
                      textAnchor="end"
                      fontWeight="bold"
                    >
                      Cel: {(targetGoal / 1000).toFixed(0)}k {baseCurrency}
                    </text>
                  </g>
                )}

                {/* Hover vertical bar triggers */}
                {timeline.map((_, idx) => {
                  const x = padLeft + (idx / (timeline.length - 1 || 1)) * plotW;
                  const colW = plotW / (timeline.length || 1);
                  return (
                    <rect
                      key={idx}
                      x={x - colW / 2}
                      y={padTop}
                      width={colW}
                      height={plotH}
                      fill="transparent"
                      className="cursor-pointer"
                      onMouseEnter={() => setHoveredIndex(idx)}
                    />
                  );
                })}

                {/* Active Hover vertical line & dots */}
                {hoveredIndex !== null && timeline[hoveredIndex] && (
                  <g>
                    {(() => {
                      const p = timeline[hoveredIndex];
                      const x = padLeft + (hoveredIndex / (timeline.length - 1 || 1)) * plotW;
                      const y90 = padTop + plotH - (p.p90 / maxVal) * plotH;
                      const y50 = padTop + plotH - (p.p50 / maxVal) * plotH;
                      const y10 = padTop + plotH - (p.p10 / maxVal) * plotH;
                      const yInv = padTop + plotH - (p.invested / maxVal) * plotH;
                      return (
                        <>
                          <line
                            x1={x}
                            y1={padTop}
                            x2={x}
                            y2={padTop + plotH}
                            stroke="#6366f1"
                            strokeWidth="1.5"
                          />
                          <circle cx={x} cy={y90} r="4" fill="#34d399" />
                          <circle cx={x} cy={y50} r="4" fill="#818cf8" />
                          <circle cx={x} cy={y10} r="4" fill="#f43f5e" />
                          <circle cx={x} cy={yInv} r="3" fill="#94a3b8" />
                        </>
                      );
                    })()}
                  </g>
                )}
              </svg>
            </div>
          </div>

          {/* Timeline Point Details / Final Summary */}
          <div className="mt-4 pt-4 border-t border-slate-800/80 bg-slate-950/40 rounded-xl p-3.5">
            {activeHoverPoint ? (
              <div className="flex flex-wrap items-center justify-between gap-4">
                <div className="text-xs text-slate-400">
                  Szczegóły dla roku:{' '}
                  <span className="font-bold text-white text-sm">Rok {activeHoverPoint.year}</span>
                </div>
                <div className="flex flex-wrap items-center gap-4 text-xs font-mono">
                  <div>
                    <span className="text-slate-400">P90: </span>
                    <span className="text-emerald-400 font-semibold">
                      {Math.round(activeHoverPoint.p90).toLocaleString('pl-PL')} {baseCurrency}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-400">P50: </span>
                    <span className="text-indigo-400 font-semibold">
                      {Math.round(activeHoverPoint.p50).toLocaleString('pl-PL')} {baseCurrency}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-400">P10: </span>
                    <span className="text-rose-400 font-semibold">
                      {Math.round(activeHoverPoint.p10).toLocaleString('pl-PL')} {baseCurrency}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-400">Wpłacono: </span>
                    <span className="text-slate-300">
                      {Math.round(activeHoverPoint.invested).toLocaleString('pl-PL')} {baseCurrency}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-400">Utrata TER: </span>
                    <span className="text-rose-400">
                      -{Math.round(activeHoverPoint.feeDrag).toLocaleString('pl-PL')} {baseCurrency}
                    </span>
                  </div>
                </div>
              </div>
            ) : summary ? (
              <div className="flex flex-wrap items-center justify-between gap-4">
                <div className="text-xs text-slate-400">
                  Końcowy wynik po horyzoncie{' '}
                  <span className="font-bold text-white">{years} lat</span>:
                </div>
                <div className="flex flex-wrap items-center gap-5 text-xs font-mono">
                  <div>
                    <span className="text-slate-400">P90 (Optymizm): </span>
                    <span className="text-emerald-400 font-bold text-sm">
                      {Math.round(summary.p90Final).toLocaleString('pl-PL')} {baseCurrency}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-400">P50 (Mediana): </span>
                    <span className="text-indigo-400 font-bold text-sm">
                      {Math.round(summary.p50Final).toLocaleString('pl-PL')} {baseCurrency}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-400">P10 (Ostrożny): </span>
                    <span className="text-rose-400 font-bold text-sm">
                      {Math.round(summary.p10Final).toLocaleString('pl-PL')} {baseCurrency}
                    </span>
                  </div>
                  <div>
                    <span className="text-slate-400">Suma dopłat: </span>
                    <span className="text-slate-300 font-medium">
                      {Math.round(summary.totalInvested).toLocaleString('pl-PL')} {baseCurrency}
                    </span>
                  </div>
                </div>
              </div>
            ) : null}
          </div>
        </div>
      </div>

      {/* Holdings Breakdown Table */}
      {stats && stats.instruments && stats.instruments.length > 0 && (
        <div className="bg-slate-900/60 border border-slate-800 rounded-2xl p-5 overflow-hidden">
          <h3 className="text-sm font-semibold text-white uppercase tracking-wider mb-3">
            Składniki Rynkowe Portfela Akcyjnego
          </h3>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-xs">
              <thead className="text-slate-400 border-b border-slate-800">
                <tr>
                  <th className="pb-2">Instrument</th>
                  <th className="pb-2">Waga</th>
                  <th className="pb-2">TER</th>
                  <th className="pb-2">C/Z (P/E)</th>
                  <th className="pb-2">Zwrot roczny (μ)</th>
                  <th className="pb-2">Zmienność (σ)</th>
                  <th className="pb-2">Beta (β)</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-800/50">
                {stats.instruments.map((inst) => (
                  <tr key={inst.symbol} className="text-slate-300 hover:bg-slate-800/20">
                    <td className="py-2.5 font-bold text-white font-mono">{inst.symbol}</td>
                    <td className="py-2.5">{(inst.weight * 100).toFixed(1)}%</td>
                    <td className="py-2.5 text-rose-400">{(inst.ter * 100).toFixed(2)}%</td>
                    <td className="py-2.5 text-cyan-400">{inst.trailingPe.toFixed(1)}</td>
                    <td className="py-2.5 text-emerald-400">{(inst.annualReturn * 100).toFixed(2)}%</td>
                    <td className="py-2.5 text-amber-400">{(inst.annualVolatility * 100).toFixed(2)}%</td>
                    <td className="py-2.5 text-purple-400">{inst.beta.toFixed(2)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </div>
  );
};
