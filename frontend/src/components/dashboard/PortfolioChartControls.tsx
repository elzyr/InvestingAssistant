import React, { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Check, ChevronDown, Settings2, Target, X } from 'lucide-react';
import type { PortfolioStatsResponse } from '../../types/analytics.types.ts';
import { formatNumberWithSpaces, roundOneDecimal } from '../../utils/reportsFormatters.ts';

const PRESET_CAGRS = [
  { value: 6.0, key: 'chart.conservativeCagr' },
  { value: 7.5, key: 'chart.balancedCagr' },
  { value: 8.5, key: 'chart.msciWorldCagr' },
  { value: 10.0, key: 'chart.sp500Cagr' },
  { value: 12.0, key: 'chart.dynamicCagr' },
] as const;

interface PortfolioChartControlsProps {
  baseCurrency: string;
  monthlyDca: number;
  onMonthlyDcaChange: (val: number) => void;
  dcaBondShare: number;
  onDcaBondShareChange: (val: number) => void;
  targetGoal: number | null;
  onTargetGoalChange?: (goal: number | null) => void;
  expectedEtfReturn: number | null;
  onExpectedEtfReturnChange: (val: number | null) => void;
  bondRollover: boolean;
  onBondRolloverChange: (val: boolean) => void;
  empiricalStats?: PortfolioStatsResponse | null;
}

export const PortfolioChartControls: React.FC<PortfolioChartControlsProps> = ({
  baseCurrency,
  monthlyDca,
  onMonthlyDcaChange,
  dcaBondShare,
  onDcaBondShareChange,
  targetGoal,
  onTargetGoalChange,
  expectedEtfReturn,
  onExpectedEtfReturnChange,
  bondRollover,
  onBondRolloverChange,
  empiricalStats,
}) => {
  const { t } = useTranslation();
  const [showAdvanced, setShowAdvanced] = useState(false);
  const advancedContainerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!showAdvanced) return;
    const handleMouseDown = (e: MouseEvent) => {
      if (advancedContainerRef.current && !advancedContainerRef.current.contains(e.target as Node)) {
        setShowAdvanced(false);
      }
    };
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key === 'Escape') {
        setShowAdvanced(false);
      }
    };
    document.addEventListener('mousedown', handleMouseDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('mousedown', handleMouseDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [showAdvanced]);

  const empiricalRounded =
    empiricalStats && empiricalStats.weightedReturn != null
      ? roundOneDecimal(empiricalStats.weightedReturn * 100)
      : null;
  const showCustomCagr =
    expectedEtfReturn != null &&
    expectedEtfReturn !== empiricalRounded &&
    !PRESET_CAGRS.some((preset) => preset.value === expectedEtfReturn);

  return (
    <div className="px-4 py-2 bg-[#0e1017] border-b border-zinc-800/80 text-xs font-mono flex flex-wrap items-center justify-between gap-3">
      <div className="flex flex-wrap items-center gap-3 sm:gap-4">
        <div className="flex items-center gap-1.5">
          <span className="text-zinc-400 text-[11px] font-medium select-none">{t('chart.contributionLabel')}</span>
          <div className="flex items-center rounded-md border border-zinc-700/80 bg-zinc-900/90 focus-within:border-sky-500 focus-within:ring-1 focus-within:ring-sky-500/20 transition">
            <input
              type="number"
              min="0"
              step="100"
              value={monthlyDca === 0 ? '' : monthlyDca}
              placeholder="0"
              onChange={(e) => onMonthlyDcaChange(Math.max(0, Number(e.target.value) || 0))}
              className="w-20 bg-transparent px-2 py-1 text-xs font-mono font-medium text-white text-right focus:outline-none [appearance:textfield] [&::-webkit-outer-spin-button]:appearance-none [&::-webkit-inner-spin-button]:appearance-none"
            />
            <span className="px-1.5 py-1 text-[10px] text-zinc-400 font-semibold border-l border-zinc-800 select-none">
              {t('chart.perMonth', { currency: baseCurrency })}
            </span>
          </div>
        </div>

        <div className="flex items-center gap-1.5">
          <span className="text-zinc-400 text-[11px] font-medium select-none">{t('chart.allocationLabel')}</span>
          <div className="flex items-center gap-2 bg-zinc-900/90 border border-zinc-700/80 px-2.5 py-1 rounded-md">
            <button
              type="button"
              onClick={() => onDcaBondShareChange(1)}
              title={t('chart.bonds100')}
              className="text-amber-400 hover:text-amber-300 transition font-semibold text-[10px] min-w-[48px] text-right select-none"
            >
              {t('chart.bondsPct', { pct: Math.round(dcaBondShare * 100) })}
            </button>
            <input
              type="range"
              min="0"
              max="100"
              step="5"
              value={Math.round((1 - dcaBondShare) * 100)}
              onChange={(e) => onDcaBondShareChange(Number(((100 - Number(e.target.value)) / 100).toFixed(2)))}
              style={{
                background: 'linear-gradient(to right, rgb(245 158 11 / 0.4), rgb(16 185 129 / 0.4))',
              }}
              className="w-20 sm:w-24 accent-sky-400 cursor-pointer h-1.5 rounded-lg appearance-none"
            />
            <button
              type="button"
              onClick={() => onDcaBondShareChange(0)}
              title={t('chart.etf100')}
              className="text-emerald-400 hover:text-emerald-300 transition font-semibold text-[10px] min-w-[48px] select-none text-left"
            >
              ETF {Math.round((1 - dcaBondShare) * 100)}%
            </button>
          </div>
        </div>

        <div className="flex items-center gap-1.5">
          <div className="flex items-center rounded-md border border-purple-900/60 bg-zinc-900/90 focus-within:border-purple-400 transition">
            <span className="pl-2 pr-1 text-[11px] text-purple-300 flex items-center gap-1.5 select-none font-medium">
              <Target className="w-3.5 h-3.5 text-purple-400" /> {t('chart.targetLabel')}
            </span>
            <input
              type="text"
              inputMode="numeric"
              value={targetGoal != null ? formatNumberWithSpaces(targetGoal) : ''}
              placeholder={t('chart.targetOptional')}
              onChange={(e) => {
                const digits = e.target.value.replace(/\D/g, '');
                onTargetGoalChange?.(digits ? Number(digits) : null);
              }}
              className="w-24 bg-transparent px-1.5 py-1 text-xs font-mono font-medium text-white text-right focus:outline-none"
            />
            <span className="px-1.5 py-1 text-[10px] text-purple-300 font-semibold border-l border-purple-900/60 select-none">
              {baseCurrency}
            </span>
            {targetGoal != null && targetGoal > 0 && (
              <button
                type="button"
                onClick={() => onTargetGoalChange?.(null)}
                title={t('chart.removeTarget')}
                className="px-1.5 py-1 text-zinc-400 hover:text-white transition cursor-pointer"
              >
                <X className="w-3 h-3" />
              </button>
            )}
          </div>
        </div>

        <div className="flex items-center gap-1.5">
          <span className="text-zinc-400 text-[11px] font-medium select-none">{t('chart.etfCagrLabel')}</span>
          <div className="relative">
            <select
              value={expectedEtfReturn ?? ''}
              onChange={(e) =>
                onExpectedEtfReturnChange(e.target.value === '' ? null : Number(e.target.value))
              }
              className="bg-zinc-900/90 border border-zinc-700/80 text-emerald-400 font-bold text-xs rounded-md px-2 py-1 pr-6 focus:outline-none focus:border-emerald-500 cursor-pointer appearance-none"
            >
              {expectedEtfReturn == null && (
                <option value="" className="bg-zinc-900 text-zinc-400">
                  —
                </option>
              )}
              {empiricalStats && empiricalStats.weightedReturn != null && empiricalRounded != null && (
                <option
                  value={empiricalRounded}
                  className="bg-zinc-900 text-zinc-200"
                >
                  {(empiricalStats.weightedReturn * 100).toFixed(1)}% ({t('chart.portfolioCagr')})
                </option>
              )}
              {showCustomCagr && expectedEtfReturn != null && (
                <option value={expectedEtfReturn} className="bg-zinc-900 text-zinc-200">
                  {expectedEtfReturn.toFixed(1)}% ({t('chart.customCagr')})
                </option>
              )}
              {PRESET_CAGRS.filter((preset) => preset.value !== empiricalRounded).map((preset) => (
                <option key={preset.value} value={preset.value} className="bg-zinc-900 text-zinc-200">
                  {preset.value.toFixed(1)}% ({t(preset.key)})
                </option>
              ))}
            </select>
            <ChevronDown className="w-3 h-3 text-zinc-400 absolute right-1.5 top-1/2 -translate-y-1/2 pointer-events-none" />
          </div>
        </div>

        {empiricalStats && (
          <div className="px-2 py-0.5 rounded-md bg-zinc-900 border border-zinc-700/80 text-[10px] text-zinc-300 font-mono flex items-center gap-1.5 select-none">
            <span>ETF:</span>
            {empiricalStats.weightedTer != null && (
              <span className="text-zinc-400">TER {(empiricalStats.weightedTer * 100).toFixed(2)}%</span>
            )}
            {empiricalStats.weightedVolatility != null && (
              <>
                <span>·</span>
                <span className="text-zinc-400">σ {(empiricalStats.weightedVolatility * 100).toFixed(1)}%</span>
              </>
            )}
          </div>
        )}
      </div>

      <div ref={advancedContainerRef} className="relative">
        <button
          type="button"
          onClick={() => setShowAdvanced(!showAdvanced)}
          className={`px-2 py-1 rounded-md border text-[11px] flex items-center gap-1.5 transition cursor-pointer ${
            showAdvanced
              ? 'bg-zinc-800 border-zinc-600 text-white'
              : 'bg-zinc-900/90 border-zinc-700/80 text-zinc-400 hover:text-zinc-200'
          }`}
          title={t('chart.advancedTooltip')}
        >
          <Settings2 className="w-3 h-3" />
          <span className="text-[10px]">{t('chart.advancedAssumptions')}</span>
        </button>

        {showAdvanced && (
          <div className="absolute right-0 top-full mt-1.5 z-40 bg-zinc-900 border border-zinc-700/80 rounded-lg p-3 shadow-2xl text-xs font-mono min-w-[260px]">
            <span className="text-[10px] text-zinc-400 font-semibold uppercase tracking-wider block mb-2">
              {t('chart.advancedOptions')}
            </span>
            <label className="flex items-center gap-2 cursor-pointer select-none">
              <input
                type="checkbox"
                checked={bondRollover}
                onChange={(e) => onBondRolloverChange(e.target.checked)}
                className="sr-only"
              />
              <div
                className={`w-3.5 h-3.5 rounded border flex items-center justify-center transition ${
                  bondRollover
                    ? 'bg-sky-500 border-sky-400 text-zinc-950'
                    : 'bg-zinc-950 border-zinc-700'
                }`}
              >
                {bondRollover && <Check className="w-2.5 h-2.5 stroke-[3]" />}
              </div>
              <span className="text-[11px] text-zinc-200">{t('chart.bondRolloverLabel')}</span>
            </label>
            <p className="text-[10px] text-zinc-500 mt-1 leading-normal">
              {t('chart.bondRolloverHelp')}
            </p>
          </div>
        )}
      </div>
    </div>
  );
};
