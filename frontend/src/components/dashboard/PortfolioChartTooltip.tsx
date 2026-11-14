import React from 'react';
import { useTranslation } from 'react-i18next';
import type { ChartMetricMode, ChartPoint } from '../../types/dashboard.ts';
import {
  formatNumberWithSpaces,
  formatSignedFixedPercent,
  formatSignedNumberWithSpaces,
  formatSignedPercentPoints,
} from '../../utils/reportsFormatters.ts';
import { CONTRIBUTOR_COLORS } from './usePortfolioChart.ts';

interface PortfolioChartTooltipProps {
  point: ChartPoint;
  baseCurrency: string;
  metricMode: ChartMetricMode;
  showAttribution: boolean;
}

export const PortfolioChartTooltip: React.FC<PortfolioChartTooltipProps> = ({
  point,
  baseCurrency,
  metricMode,
  showAttribution,
}) => {
  const { t } = useTranslation();

  return (
    <div className="absolute top-4 left-4 z-30 pointer-events-none bg-zinc-950/95 border border-zinc-700/90 rounded-lg p-2.5 shadow-2xl text-xs font-mono backdrop-blur-md min-w-[230px]">
      <div className="flex items-center justify-between border-b border-zinc-800 pb-1 mb-1.5 text-zinc-300">
        <span className="font-semibold text-sky-400">
          {point.isFuture ? `${t('chart.projection')} ` : ''}{point.dateLabel}
        </span>
        {point.isFuture && (
          <span className="text-[10px] text-zinc-500 uppercase">Monte Carlo</span>
        )}
      </div>

      <div className="space-y-1 text-[11px]">
        <div className="flex justify-between items-center text-white">
          <span className="text-zinc-400">
            {point.isFuture ? t('chart.medianLabel') : t('chart.portfolioValuation')}
          </span>
          <span className="font-bold text-sm text-sky-300">
            {metricMode === 'PLN'
              ? `${formatNumberWithSpaces(point.valuation)} ${baseCurrency}`
              : formatSignedFixedPercent(point.returnPct, 2)}
          </span>
        </div>

        <div className="flex justify-between items-center text-zinc-400">
          <span>{t('chart.ownContributionDca')}</span>
          <span className="font-mono text-zinc-300">
            {formatNumberWithSpaces(point.cost)} {baseCurrency}
          </span>
        </div>

        <div className="flex justify-between items-center pt-1 border-t border-zinc-800/60">
          <span className="text-zinc-400">{t('chart.netProfitLabel')}</span>
          <span
            className={`font-semibold ${
              point.profit >= 0 ? 'text-emerald-400' : 'text-rose-400'
            }`}
          >
            {formatSignedNumberWithSpaces(point.profit)} {baseCurrency} (
            {formatSignedFixedPercent(point.returnPct, 2)})
          </span>
        </div>

        {showAttribution && !point.isFuture && point.contributors && point.contributors.length > 0 && (
          <div className="pt-1.5 mt-1 border-t border-zinc-800/80 space-y-1 text-[10px]">
            <div className="text-[10px] text-zinc-400 font-medium pb-0.5 border-b border-zinc-800/40">
              {t('chart.profitAttributionBreakdown')}:
            </div>
            {point.contributors
              .map((c, originalIdx) => ({ c, originalIdx }))
              .sort((a, b) => b.c.profit - a.c.profit)
              .map(({ c, originalIdx }) => {
                const color = CONTRIBUTOR_COLORS[originalIdx % CONTRIBUTOR_COLORS.length];
                return (
                  <div key={c.key} className="flex justify-between items-center gap-2">
                    <div className="flex items-center gap-1.5 min-w-0">
                      <span className="w-1.5 h-1.5 rounded-full shrink-0" style={{ backgroundColor: color }} />
                      <span className="truncate text-zinc-300">{c.label}</span>
                    </div>
                    <div className="flex items-center gap-1 font-mono shrink-0">
                      <span className={c.profit >= 0 ? 'text-emerald-400' : 'text-rose-400'}>
                        {metricMode === 'PLN'
                          ? `${formatSignedNumberWithSpaces(c.profit)} ${baseCurrency}`
                          : formatSignedPercentPoints(c.returnContributionPct, 2)}
                      </span>
                      <span className="text-zinc-500">
                        ({formatSignedFixedPercent(c.sharePct, 1)})
                      </span>
                    </div>
                  </div>
                );
              })}
          </div>
        )}

        {point.isFuture && (
          <div className="pt-1.5 mt-1 border-t border-zinc-800/80 space-y-0.5 text-[10px]">
            <div className="flex justify-between text-sky-300/80">
              <span>{t('chart.bullMarket')}</span>
              <span className="font-mono font-medium">
                {metricMode === 'PLN'
                  ? `${formatNumberWithSpaces(point.netP90 ?? 0)} ${baseCurrency}`
                  : formatSignedFixedPercent(point.returnP90 ?? point.returnPct, 2)}
              </span>
            </div>
            <div className="flex justify-between text-sky-400/70">
              <span>{t('chart.bearMarket')}</span>
              <span className="font-mono font-medium">
                {metricMode === 'PLN'
                  ? `${formatNumberWithSpaces(point.netP10 ?? 0)} ${baseCurrency}`
                  : formatSignedFixedPercent(point.returnP10 ?? point.returnPct, 2)}
              </span>
            </div>
          </div>
        )}
      </div>
    </div>
  );
};
