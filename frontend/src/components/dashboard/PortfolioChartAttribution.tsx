import React from 'react';
import { useTranslation } from 'react-i18next';
import type { ChartMetricMode, ChartPoint } from '../../types/dashboard.ts';
import {
  formatSignedFixedPercent,
  formatSignedNumberWithSpaces,
  formatSignedPercentPoints,
} from '../../utils/reportsFormatters.ts';
import { CONTRIBUTOR_COLORS } from './usePortfolioChart.ts';

interface PortfolioChartAttributionProps {
  activePoint: ChartPoint;
  baseCurrency: string;
  metricMode: ChartMetricMode;
}

export const PortfolioChartAttribution: React.FC<PortfolioChartAttributionProps> = ({
  activePoint,
  baseCurrency,
  metricMode,
}) => {
  const { t } = useTranslation();
  const contribs = activePoint.contributors || [];
  if (contribs.length === 0) return null;

  return (
    <div className="px-4 py-2 bg-[#0c0e14] border-b border-zinc-800/80 text-xs font-mono">
      <div className="flex items-center justify-between text-[11px] mb-1.5">
        <span className="text-zinc-400 font-medium">
          {t('chart.profitAttributionBreakdown')} ({activePoint.dateLabel}):
        </span>
        <span className={`font-semibold ${activePoint.profit >= 0 ? 'text-emerald-400' : 'text-rose-400'}`}>
          {metricMode === 'PLN'
            ? `${formatSignedNumberWithSpaces(activePoint.profit)} ${baseCurrency}`
            : formatSignedFixedPercent(activePoint.returnPct, 2)}
        </span>
      </div>

      <div className="h-1.5 w-full rounded-full overflow-hidden flex bg-zinc-800/80 mb-2">
        {contribs.map((c, idx) => {
          const width = c.sharePct === 0 ? 0 : Math.min(100, Math.max(2, Math.abs(c.sharePct)));
          const color = CONTRIBUTOR_COLORS[idx % CONTRIBUTOR_COLORS.length];
          return (
            <div
              key={c.key}
              style={{ width: `${width}%`, backgroundColor: color }}
              title={`${c.label}: ${formatSignedNumberWithSpaces(c.profit)} ${baseCurrency} (${formatSignedFixedPercent(c.sharePct, 1)})`}
              className="h-full transition-all"
            />
          );
        })}
      </div>

      <div className="flex flex-wrap items-center gap-1.5">
        {contribs.map((c, idx) => {
          const color = CONTRIBUTOR_COLORS[idx % CONTRIBUTOR_COLORS.length];
          return (
            <div
              key={c.key}
              className="inline-flex items-center gap-1 px-2 py-0.5 rounded-md bg-zinc-900/90 border border-zinc-800 text-[10px]"
            >
              <span className="w-2 h-2 rounded-full shrink-0" style={{ backgroundColor: color }} />
              <span className="font-semibold text-zinc-200">{c.label}</span>
              <span className={c.profit >= 0 ? 'text-emerald-400 font-medium' : 'text-rose-400 font-medium'}>
                {metricMode === 'PLN'
                  ? `${formatSignedNumberWithSpaces(c.profit)} ${baseCurrency}`
                  : formatSignedPercentPoints(c.returnContributionPct, 2)}
              </span>
              <span className="text-zinc-500">
                ({formatSignedFixedPercent(c.sharePct, 1)})
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
};
