import { useTranslation } from 'react-i18next';
import type { PortfolioSummary } from '../../types/dashboard.ts';

interface KpiCardProps {
  title: string;
  value: string;
  subValue: string;
  isAccent?: boolean;
  detail?: string;
}

const KpiCard = ({
  title,
  value,
  subValue,
  isAccent = false,
  detail,
}: KpiCardProps) => {
  return (
    <div className="bg-[#161922] border border-zinc-800/90 rounded-xl p-3 flex flex-col justify-between min-h-[96px]">
      <div>
        <div className="flex items-center justify-between gap-1">
          <span className="text-xs font-medium text-zinc-300">{title}</span>
          {detail && (
            <span className="text-[10px] font-mono font-normal text-zinc-400 bg-zinc-800/80 px-1.5 py-0.2 rounded">
              {detail}
            </span>
          )}
        </div>
        <div className="text-lg font-bold font-mono text-white tracking-tight mt-0.5">
          {value}
        </div>
      </div>
      <div
        className={`text-[11px] font-mono whitespace-nowrap mt-1 ${
          isAccent
            ? subValue.startsWith('-')
              ? 'font-semibold text-rose-400'
              : 'font-semibold text-emerald-400'
            : 'font-medium text-zinc-400'
        }`}
      >
        {subValue}
      </div>
    </div>
  );
};

interface KpiGridProps {
  summary: PortfolioSummary;
}

export const KpiGrid = ({ summary }: KpiGridProps) => {
  const { t } = useTranslation();

  return (
    <div className="grid grid-cols-2 sm:grid-cols-4 gap-2.5 items-stretch">
      <KpiCard
        title="Wartość portfela"
        value={summary.portfolioValueFormatted}
        subValue={`${summary.dailyChangeAmountFormatted} (${summary.dailyChangePercentFormatted})`}
        isAccent={true}
      />

      <KpiCard
        title="Wpłacony kapitał"
        value={summary.totalCostFormatted}
        subValue="Wkład własny"
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
        title={t('dashboard.realProfitVsCpi', 'Zysk realny (vs CPI)')}
        value={summary.realProfitAmountFormatted}
        subValue={summary.realProfitPercentFormatted}
        isAccent={true}
      />
    </div>
  );
};
