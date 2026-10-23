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

export type ReportMonthStatus = 'RECORDED' | 'UPCOMING';

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
  cpiInflation: number;
  realProfitPct: number;
  status: ReportMonthStatus;
  breakdown: MonthlyCategoryBreakdown[];
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
  ytdRealReturnPct: number;
  avgCpi: number;
  baseCurrency?: string;
}

export type ReportViewMode = 'ANNUAL' | 'MONTH_DETAIL';
