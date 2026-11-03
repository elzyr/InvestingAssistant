import { useTranslation } from 'react-i18next';
import type { PortfolioSummary } from '../../types/dashboard.ts';

interface KpiCardProps {
  title: string;
  value: string;
  subValue?: string;
  isAccent?: boolean;
  detail?: string;
}

const KpiCard = ({ title, value, subValue, isAccent, detail }: KpiCardProps) => (
  <div className="bg-[#10121a] border border-zinc-850 hover:border-zinc-750 transition-colors rounded-xl p-3 sm:p-3.5 flex flex-col justify-between h-full min-h-[96px]">
    <div className="flex items-center justify-between gap-1 mb-1">
      <span className="text-[11px] font-mono uppercase text-zinc-400 font-semibold tracking-wider truncate">
        {title}
      </span>
      {detail && (
        <span className="text-[10px] font-mono text-zinc-400 bg-zinc-800/80 px-1.5 py-0.5 rounded border border-zinc-700/60 shrink-0">
          {detail}
        </span>
      )}
    </div>

    <div>
      <div
        className={`text-lg sm:text-xl font-mono font-bold tracking-tight ${
          isAccent ? 'text-zinc-100' : 'text-zinc-200'
        }`}
      >
        {value}
      </div>

      {subValue && (
        <div className="text-[11px] font-mono text-zinc-400 mt-1 truncate">
          {subValue}
        </div>
      )}
    </div>
  </div>
);

interface KpiGridProps {
  summary: PortfolioSummary;
}

export const KpiGrid = ({ summary }: KpiGridProps) => {
  const { t } = useTranslation();

  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 items-stretch">
      <KpiCard
        title={summary.portfolioValueLabel || t('dashboard.portfolioValue')}
        value={summary.portfolioValueFormatted}
        subValue={`${summary.dailyChangeAmountFormatted} (${summary.dailyChangePercentFormatted})`}
        isAccent={true}
      />

      <KpiCard
        title={t('dashboard.investedCapital')}
        value={summary.totalCostFormatted}
        subValue={t('dashboard.ownContribution')}
        isAccent={false}
      />

      <KpiCard
        title={summary.profitLabel}
        value={summary.totalProfitAmountFormatted}
        subValue={summary.totalProfitPercentFormatted}
        isAccent={true}
        detail={summary.taxNoteFormatted}
      />

      <KpiCard
        title={t('dashboard.realProfitVsCpi')}
        value={summary.realProfitAmountFormatted}
        subValue={summary.realProfitPercentFormatted}
        isAccent={true}
      />
    </div>
  );
};
