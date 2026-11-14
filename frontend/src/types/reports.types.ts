export interface MonthlyCategoryBreakdown {
  category: string;
  color: string;
  invested: number;
  currentValue: number;
  profitGross: number;
  profitNet: number;
  returnGrossPct: number;
  returnNetPct: number;
  sharePct: number;
}

export interface MonthlyPositionBreakdown {
  key: string;
  symbol: string;
  name: string;
  categoryCode: string;
  color: string;
  invested: number;
  currentValue: number;
  profitGross: number;
  profitNet: number;
  profitNetMom: number;
  returnGrossPct: number;
  returnNetPct: number;
  sharePct: number;
}

export type ReportMonthStatus = 'RECORDED' | 'BEFORE_PORTFOLIO' | 'FUTURE';

export interface MonthlyReportRecord {
  monthIndex: number;
  monthName: string;
  shortName: string;
  year: number;
  portfolioValue: number;
  investedTotal: number;
  profitGross: number;
  returnGrossPct: number;
  profitNet: number;
  returnNetPct: number;
  profitNetMom: number;
  returnNetMomPct: number;
  cpiInflation: number | null;
  realProfitPct: number | null;
  status: ReportMonthStatus;
  breakdown: MonthlyCategoryBreakdown[];
  positions: MonthlyPositionBreakdown[];
  baseCurrency?: string;
}

export interface AnnualSummaryData {
  year: number;
  records: MonthlyReportRecord[];
  ytdPortfolioValue: number;
  ytdInvested: number;
  ytdProfitGross: number;
  ytdReturnGrossPct: number;
  ytdProfitNet: number;
  ytdReturnNetPct: number;
  ytdRealReturnPct: number | null;
  ytdTwr?: number;
  avgCpi: number | null;
  baseCurrency?: string;
}

export type ReportViewMode = 'ANNUAL' | 'MONTH_DETAIL';
