export type AssetFilterType = 'ALL' | 'ETF' | 'BOND' | 'STOCK';
export type SortOption = 'value' | 'profit' | 'share' | 'name' | 'xirr';
export type TaxMode = 'NETTO' | 'BRUTTO';
export type TimeframeOption = '1M' | '3M' | 'YTD' | '1Y' | 'MAX';
export type ChartMetricMode = 'PLN' | 'PERCENT';
export type ProjectionHorizonOption = 'OFF' | '3Y' | '5Y' | '10Y' | '20Y';
export type InflationAdjustMode = 'NOMINAL' | 'REAL';

export interface ProfitContributor {
  key: string;
  label: string;
  assetType: 'ETF' | 'BOND' | 'STOCK' | 'OTHER';
  valuation: number;
  cost: number;
  profit: number;
  returnContributionPct: number;
  sharePct: number;
}

export interface ChartPoint {
  contributors?: ProfitContributor[];
  timestamp: number;
  dateLabel: string;
  valuation: number;
  cost: number;
  profit: number;
  returnPct: number;
  returnP90?: number;
  returnP10?: number;
  isFuture?: boolean;
  netP90?: number;
  netP10?: number;

  goalProbability?: number;
  weightedTer?: number | null;
  diversifiedVolatility?: number | null;
}

export interface SubPosition {
  id: string;
  name: string;
  subName?: string;
  sharePercent: number;
  shareNote?: string;
  priceFormatted: string;
  valueFormatted: string;
  profitFormatted: string;
  profitPercentFormatted: string;
  isProfit: boolean;
  transactionId?: string;
  xirr?: number | null;
  fxDecomposition?: { marketReturnPct: number; fxReturnPct: number } | null;
  isPriceUnavailable?: boolean;
  isCpiUnavailable?: boolean;
}

export interface Position {
  id: string;
  ticker: string;
  name: string;
  type: 'ETF' | 'BOND' | 'STOCK';
  quantityFormatted: string;
  sharePercent: number;
  valueBase: number;
  profitBase: number;
  profitPct: number;
  priceFormatted: string;
  valueFormatted: string;
  profitFormatted: string;
  profitPercentFormatted: string;
  isProfit: boolean;
  subPositionsType?: 'bonds_yearly' | 'purchases';
  subPositions?: SubPosition[];
  xirr?: number | null;
  fxDecomposition?: { marketReturnPct: number; fxReturnPct: number } | null;
  isPriceUnavailable?: boolean;
  isCpiUnavailable?: boolean;
}

export interface PortfolioSummary {
  portfolioValueFormatted: string;
  dailyChangeAmountFormatted: string;
  dailyChangePercentFormatted: string;
  totalCostFormatted: string;
  totalProfitAmountFormatted: string;
  totalProfitPercentFormatted: string;
  realProfitAmountFormatted: string;
  realProfitPercentFormatted: string;
  positionCount: number;
  taxMode: TaxMode;
  profitLabel: string;
  portfolioValueLabel?: string;
  taxNoteFormatted?: string;
}
