import React from 'react';
import { useTranslation } from 'react-i18next';
import { ChevronDown, ChevronUp, LineChart, Sparkles, Info, Maximize2, Target, Layers } from 'lucide-react';
import type {
  ChartPoint,
  TaxMode,
  TimeframeOption,
  ChartMetricMode,
  ProjectionHorizonOption,
  InflationAdjustMode,
} from '../../types/dashboard.ts';
import { PortfolioChartAttribution } from './PortfolioChartAttribution.tsx';
import { PortfolioChartControls } from './PortfolioChartControls.tsx';
import { PortfolioChartTooltip } from './PortfolioChartTooltip.tsx';
import {
  CONTRIBUTOR_COLORS,
  usePortfolioChart,
} from './usePortfolioChart.ts';
import type { PortfolioStatsResponse } from '../../types/analytics.types.ts';
import {
  formatNumberWithSpaces,
  formatSignedFixedPercent,
  formatSignedNumberWithSpaces,
} from '../../utils/reportsFormatters.ts';

interface PortfolioChartProps {
  data: ChartPoint[];
  baseCurrency: string;
  taxMode: TaxMode;
  timeframe: TimeframeOption;
  onTimeframeChange: (tf: TimeframeOption) => void;
  metricMode: ChartMetricMode;
  onMetricModeChange: (m: ChartMetricMode) => void;
  projectionHorizon: ProjectionHorizonOption;
  onProjectionHorizonChange: (horizon: ProjectionHorizonOption) => void;
  monthlyDca: number;
  onMonthlyDcaChange: (val: number) => void;
  dcaBondShare: number;
  onDcaBondShareChange: (val: number) => void;
  expectedEtfReturn: number | null;
  onExpectedEtfReturnChange: (val: number | null) => void;
  bondRollover: boolean;
  onBondRolloverChange: (val: boolean) => void;
  inflationAdjustMode?: InflationAdjustMode;
  onInflationAdjustModeChange?: (mode: InflationAdjustMode) => void;
  targetGoal?: number | null;
  onTargetGoalChange?: (goal: number | null) => void;
  empiricalStats?: PortfolioStatsResponse | null;
  proxyWarnings?: string[];
}

const TIMEFRAMES: TimeframeOption[] = ['1M', '3M', 'YTD', '1Y', 'MAX'];
const PROJECTIONS: ProjectionHorizonOption[] = ['3Y', '5Y', '10Y', '20Y'];



export const PortfolioChart: React.FC<PortfolioChartProps> = ({
  data,
  baseCurrency,
  taxMode,
  timeframe,
  onTimeframeChange,
  metricMode,
  onMetricModeChange,
  projectionHorizon,
  onProjectionHorizonChange,
  monthlyDca,
  onMonthlyDcaChange,
  dcaBondShare,
  onDcaBondShareChange,
  expectedEtfReturn,
  onExpectedEtfReturnChange,
  bondRollover,
  onBondRolloverChange,
  inflationAdjustMode = 'NOMINAL',
  onInflationAdjustModeChange,
  targetGoal = null,
  onTargetGoalChange,
  empiricalStats,
  proxyWarnings,
}) => {
  const { t, i18n } = useTranslation();

  const {
    showAttribution,
    setShowAttribution,
    chartContainerRef,
    fitContent,
    hoveredPoint,
    todayXCoord,
    isCollapsed,
    setIsCollapsed,
    isProjectionActive,
    latestHistoricalPoint,
    goalProb,
    weightedTer,
    diversifiedVol,
    activeAttributionPoint,
  } = usePortfolioChart({
    data,
    baseCurrency,
    metricMode,
    projectionHorizon,
    targetGoal,
    t,
    locale: i18n.language,
  });

  if (!data || data.length === 0) return null;

  return (
    <div className="bg-[#141720] border border-zinc-800 rounded-xl overflow-hidden shadow-sm transition-all">
      <div className="px-4 py-3 flex flex-col md:flex-row md:items-center justify-between gap-3 border-b border-zinc-800/80">
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-center gap-2">
            <LineChart className="w-4 h-4 text-emerald-400" />
            <span className="text-xs font-bold text-zinc-200">
              {isProjectionActive ? t('chart.titleProjection') : t('chart.titleHistory')}
            </span>
            <span className="text-[10px] font-mono text-zinc-400 bg-zinc-800/80 px-1.5 py-0.5 rounded border border-zinc-700/60">
              {taxMode === 'NETTO' ? t('chart.taxNettoBadge') : t('chart.taxBruttoBadge')}
            </span>
            {inflationAdjustMode === 'REAL' && (
              <span className="text-[10px] font-mono text-amber-300 bg-amber-950/70 px-1.5 py-0.5 rounded border border-amber-800/60">
                {t('chart.realBadge')}
              </span>
            )}
            {isProjectionActive && (
              <span className="text-[10px] font-mono text-sky-400 bg-sky-950/60 px-1.5 py-0.5 rounded border border-sky-800/60 flex items-center gap-1">
                <Sparkles className="w-3 h-3" />
                {t('chart.projectionHorizonBadge', { years: projectionHorizon.replace('Y', '') })}
              </span>
            )}
            {targetGoal != null && targetGoal > 0 && isProjectionActive && goalProb !== undefined && (
              <span
                className={`text-[10px] font-mono px-1.5 py-0.5 rounded border flex items-center gap-1 ${
                  goalProb >= 70
                    ? 'bg-emerald-950/80 text-emerald-300 border-emerald-700/60'
                    : goalProb >= 40
                    ? 'bg-amber-950/80 text-amber-300 border-amber-700/60'
                    : 'bg-rose-950/80 text-rose-300 border-rose-700/60'
                }`}
              >
                <Target className="w-3 h-3 text-purple-400" />{' '}
                {t('chart.targetGoalBadge', { val: formatNumberWithSpaces(targetGoal / 1000) })}{' '}
                <span className="font-bold">{t('chart.targetGoalChance', { prob: goalProb })}</span>
              </span>
            )}
            {isProjectionActive && (
              <div className="relative group inline-flex items-center">
                <button
                  type="button"
                  className="text-zinc-500 hover:text-zinc-300 transition cursor-pointer p-0.5"
                  aria-label={t('chart.equityParams')}
                >
                  <Info className="w-3.5 h-3.5" />
                </button>
                <div className="absolute left-0 top-full mt-1.5 hidden group-hover:flex flex-col gap-1 z-50 bg-zinc-900 border border-zinc-700/80 rounded-lg p-2.5 shadow-2xl text-[11px] font-mono text-zinc-300 w-64 backdrop-blur-md pointer-events-none">
                  <span className="font-semibold text-white border-b border-zinc-800 pb-1">{t('chart.equityParams')}</span>
                  <div className="flex justify-between">
                    <span className="text-zinc-400">{t('chart.weightedTer')}</span>
                    <span className="font-semibold text-sky-300">{weightedTer != null ? `${weightedTer.toFixed(2)}%` : '—'}</span>
                  </div>
                  <div className="flex justify-between pt-1 border-t border-zinc-800">
                    <span className="text-zinc-400">{t('chart.volatility')}</span>
                    <span className="font-semibold text-emerald-300">{diversifiedVol != null ? `${diversifiedVol.toFixed(1)}%` : '—'}</span>
                  </div>
                  <span className="text-[10px] text-zinc-500 mt-0.5 leading-tight">
                    {t('chart.equityParamsHelp')}
                  </span>
                </div>
              </div>
            )}
          </div>

          <div className="flex flex-wrap items-baseline gap-3 mt-1.5 min-h-[26px]">
            {latestHistoricalPoint && (
              <div className="flex items-baseline gap-1.5 shrink-0">
                {metricMode === 'PERCENT' ? (
                  <>
                    <span className="text-[10px] font-mono uppercase text-zinc-400 font-medium">{t('chart.currentReturn')}</span>
                    <span className={`text-base font-bold font-mono ${latestHistoricalPoint.returnPct >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                      {formatSignedFixedPercent(latestHistoricalPoint.returnPct, 2)}
                    </span>
                    <span className="text-xs font-mono font-medium text-zinc-400">
                      ({formatSignedNumberWithSpaces(latestHistoricalPoint.profit)} {baseCurrency})
                    </span>
                  </>
                ) : (
                  <>
                    <span className="text-[10px] font-mono uppercase text-zinc-400 font-medium">{t('chart.currentValuation')}</span>
                    <span className="text-base font-bold font-mono text-white">
                      {formatNumberWithSpaces(latestHistoricalPoint.valuation)} {baseCurrency}
                    </span>
                    <span className={`text-xs font-mono font-semibold ${latestHistoricalPoint.profit >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
                      {formatSignedNumberWithSpaces(latestHistoricalPoint.profit)} {baseCurrency} (
                      {formatSignedFixedPercent(latestHistoricalPoint.returnPct, 2)})
                    </span>
                  </>
                )}
              </div>
            )}

            {hoveredPoint && (
              <div className="flex items-baseline gap-2 bg-[#1c2233] px-2 py-0.5 rounded-md border border-sky-800/80 text-xs font-mono shrink-0">
                <span className="text-sky-300 font-semibold text-[11px]">
                  {hoveredPoint.isFuture ? `${t('chart.projection')} ` : ''}{hoveredPoint.dateLabel}
                </span>
                <span className="text-white font-bold">
                  {formatNumberWithSpaces(hoveredPoint.valuation)} {baseCurrency}
                </span>
                <span className="text-zinc-400 text-[11px]">
                  ({t('chart.costBasisLabel')}: {formatNumberWithSpaces(hoveredPoint.cost)})
                </span>
              </div>
            )}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2 self-start md:self-auto">
          <div className="flex items-center bg-[#10121a] border border-zinc-800 rounded-lg p-0.5 text-xs font-mono">
            <button
              type="button"
              onClick={() => onMetricModeChange('PLN')}
              className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                metricMode === 'PLN'
                  ? 'bg-zinc-800 text-emerald-400 font-bold border border-zinc-700/80 shadow-xs'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {baseCurrency}
            </button>
            <button
              type="button"
              onClick={() => onMetricModeChange('PERCENT')}
              className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                metricMode === 'PERCENT'
                  ? 'bg-zinc-800 text-emerald-400 font-bold border border-zinc-700/80 shadow-xs'
                  : 'text-zinc-400 hover:text-zinc-200'
              }`}
            >
              {t('chart.metricReturn')}
            </button>
          </div>

          <button
            type="button"
            onClick={() => setShowAttribution(!showAttribution)}
            title={t('chart.profitAttributionTitle')}
            className={`px-2 py-1 rounded-lg border text-xs font-mono font-medium transition cursor-pointer flex items-center gap-1.5 ${
              showAttribution
                ? 'bg-amber-500/20 text-amber-300 font-bold border-amber-500/50 shadow-xs'
                : 'bg-[#10121a] border-zinc-800 text-zinc-400 hover:text-zinc-200'
            }`}
          >
            <Layers className="w-3.5 h-3.5" />
            <span>{t('chart.profitAttribution')}</span>
          </button>

          {isProjectionActive && (
            <div className="flex items-center bg-[#10121a] border border-zinc-800 rounded-lg p-0.5 text-xs font-mono">
              <button
                type="button"
                onClick={() => onInflationAdjustModeChange?.('NOMINAL')}
                className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                  inflationAdjustMode === 'NOMINAL'
                    ? 'bg-zinc-800 text-zinc-200 font-bold border border-zinc-700/80 shadow-xs'
                    : 'text-zinc-400 hover:text-zinc-200'
                }`}
              >
                {t('chart.nominalMode')}
              </button>
              <button
                type="button"
                onClick={() => onInflationAdjustModeChange?.('REAL')}
                className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                  inflationAdjustMode === 'REAL'
                    ? 'bg-amber-950 text-amber-300 font-bold border border-amber-800/80 shadow-xs'
                    : 'text-zinc-400 hover:text-zinc-200'
                }`}
                title={t('chart.realModeTitle')}
              >
                {t('chart.realMode')}
              </button>
            </div>
          )}

          <div className="flex items-center bg-[#10121a] border border-zinc-800 rounded-lg p-0.5 text-xs font-mono">
            {TIMEFRAMES.map((tf) => {
              const active = timeframe === tf;
              return (
                <button
                  key={tf}
                  type="button"
                  onClick={() => onTimeframeChange(tf)}
                  className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                    active
                      ? 'bg-emerald-400 text-zinc-950 font-bold shadow-xs'
                      : 'text-zinc-400 hover:text-zinc-200'
                  }`}
                >
                  {tf === '1Y' ? (i18n.language.startsWith('pl') ? '1R' : '1Y') : tf}
                </button>
              );
            })}

            <div className="w-[1px] h-3.5 bg-zinc-700/80 mx-1 select-none" />

            {PROJECTIONS.map((h) => {
              const active = projectionHorizon === h;
              const yrUnit = i18n.language.startsWith('en') ? 'Y' : 'L';
              const label = `+${h.replace('Y', yrUnit)}`;
              return (
                <button
                  key={h}
                  type="button"
                  onClick={() => onProjectionHorizonChange(active ? 'OFF' : h)}
                  title={active ? t('chart.turnOffProjection') : t('chart.enableProjection', { label })}
                  className={`px-2 py-0.5 rounded-md text-[11px] font-medium transition cursor-pointer ${
                    active
                      ? 'bg-sky-500 text-zinc-950 font-bold shadow-xs'
                      : 'text-sky-400/80 hover:text-sky-300 hover:bg-sky-950/40'
                  }`}
                >
                  {label}
                </button>
              );
            })}
          </div>

          <button
            type="button"
            onClick={fitContent}
            title={t('chart.fitContent')}
            className="p-1 rounded-lg bg-zinc-900 border border-zinc-800 text-zinc-400 hover:text-zinc-200 transition cursor-pointer"
          >
            <Maximize2 className="w-3.5 h-3.5" />
          </button>

          <button
            type="button"
            onClick={() => setIsCollapsed(!isCollapsed)}
            title={isCollapsed ? t('chart.expandChart') : t('chart.collapseChart')}
            className="p-1 rounded-lg bg-zinc-900 border border-zinc-800 text-zinc-400 hover:text-zinc-200 transition cursor-pointer"
          >
            {isCollapsed ? <ChevronDown className="w-3.5 h-3.5" /> : <ChevronUp className="w-3.5 h-3.5" />}
          </button>
        </div>
      </div>

      {!isCollapsed && showAttribution && activeAttributionPoint && (
        <PortfolioChartAttribution
          activePoint={activeAttributionPoint}
          baseCurrency={baseCurrency}
          metricMode={metricMode}
        />
      )}

      {!isCollapsed && isProjectionActive && (
        <PortfolioChartControls
          baseCurrency={baseCurrency}
          monthlyDca={monthlyDca}
          onMonthlyDcaChange={onMonthlyDcaChange}
          dcaBondShare={dcaBondShare}
          onDcaBondShareChange={onDcaBondShareChange}
          targetGoal={targetGoal}
          onTargetGoalChange={onTargetGoalChange}
          expectedEtfReturn={expectedEtfReturn}
          onExpectedEtfReturnChange={onExpectedEtfReturnChange}
          bondRollover={bondRollover}
          onBondRolloverChange={onBondRolloverChange}
          empiricalStats={empiricalStats}
        />
      )}

      {!isCollapsed && isProjectionActive && proxyWarnings && proxyWarnings.length > 0 && (
        <div
          role="status"
          className="px-4 py-1.5 bg-amber-500/10 border-b border-amber-500/30 text-[11px] font-mono text-amber-200 flex items-center gap-2"
        >
          <Info className="w-3.5 h-3.5 shrink-0 text-amber-400" />
          <span>{proxyWarnings.join(' • ')}</span>
        </div>
      )}

      {!isCollapsed && (
        <div className="p-4 pt-2 pb-5 bg-[#10121a] relative">
          {hoveredPoint && (
            <PortfolioChartTooltip
              point={hoveredPoint}
              baseCurrency={baseCurrency}
              metricMode={metricMode}
              showAttribution={showAttribution}
            />
          )}

          <div className="relative w-full">
            <div ref={chartContainerRef} className="w-full" />

            {isProjectionActive && todayXCoord !== null && (
              <div className="pointer-events-none absolute inset-0">
                <div
                  className="absolute top-0 bottom-[26px] w-[1px] border-l border-sky-400/80 border-dashed"
                  style={{ left: `${todayXCoord}px` }}
                />
                <div
                  className="absolute bottom-1 -translate-x-1/2 z-10"
                  style={{ left: `${todayXCoord}px` }}
                >
                  <span className="text-[9px] font-mono font-bold text-sky-300 bg-[#0c1e30] px-1 py-0.5 rounded border border-sky-500/70 shadow-xs">
                    {t('chart.today')}
                  </span>
                </div>
              </div>
            )}
          </div>

          <div className="flex flex-wrap items-center justify-between text-[11px] text-zinc-400 mt-4 pt-3 px-1 border-t border-zinc-800/80 font-mono gap-4">
            <div className="flex flex-wrap items-center gap-5">
              {showAttribution ? (
                <>
                  <div className="flex items-center gap-1.5">
                    <span className="w-2.5 h-0.5 bg-emerald-400 inline-block rounded-full"></span>
                    <span>{t('chart.totalProfitSeries')}</span>
                  </div>
                  {latestHistoricalPoint?.contributors?.map((c, idx) => {
                    const color = CONTRIBUTOR_COLORS[idx % CONTRIBUTOR_COLORS.length];
                    return (
                      <div key={c.key} className="flex items-center gap-1.5">
                        <span className="w-2 h-2 rounded-full inline-block" style={{ backgroundColor: color }}></span>
                        <span>{c.label}</span>
                      </div>
                    );
                  })}
                </>
              ) : (
                <div className="flex items-center gap-1.5">
                  <span className="w-2.5 h-0.5 bg-emerald-400 inline-block rounded-full"></span>
                  <span>{t('chart.valuationHistory')}</span>
                </div>
              )}

              {isProjectionActive && (
                <>
                  <div className="flex items-center gap-1.5">
                    <span className="w-2.5 h-0.5 bg-sky-400 inline-block rounded-full"></span>
                    <span>{t('chart.medianP50')}</span>
                  </div>
                  <div className="flex items-center gap-1.5">
                    <span className="w-2.5 h-0.5 border-b border-sky-400 border-dotted inline-block"></span>
                    <span>{t('chart.bandP10P90')}</span>
                  </div>
                </>
              )}

              {metricMode === 'PLN' && !showAttribution && (
                <div className="flex items-center gap-1.5">
                  <span className="w-2.5 h-0.5 border-b border-zinc-400 border-dashed inline-block"></span>
                  <span>{isProjectionActive ? t('chart.contributionWithDca') : t('chart.contributionCapital')}</span>
                </div>
              )}

              {targetGoal != null && targetGoal > 0 && metricMode === 'PLN' && !showAttribution && (
                <div className="flex items-center gap-1.5">
                  <span className="w-2.5 h-0.5 border-b border-purple-400 border-dashed inline-block"></span>
                  <span className="text-purple-300 font-medium">{t('chart.targetGoalLabel', { val: formatNumberWithSpaces(targetGoal / 1000), currency: baseCurrency })}</span>
                </div>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
};
