import type { TaxMode, TimeframeOption } from './dashboard.ts';

export type AssetCategoryCode =
  | 'TREASURY_BONDS'
  | 'SP500'
  | 'ETF_ALL_WORLD'
  | 'ETF_WORLD'
  | 'EMERGING_MARKETS'
  | 'PL_STOCKS'
  | 'US_STOCKS'
  | 'OTHER';

export interface ValuationSummaryDto {
  totalValue: number;
  grossTotalValue: number;
  totalInvested: number;
  netProfit: number;
  netProfitPct: number;
  grossProfit: number;
  realizedProfit: number;
  taxPaid: number;
  dividendsTotal: number;
  dailyChange: number;
  dailyChangePct: number;
  xirrPct: number | null;
  realProfitAmount: number | null;
  realReturnPct: number | null;
  realXirrPct: number | null;
  currentCpiPct: number | null;
  positionsCount: number;
}

export interface FxDecompositionDto {
  quoteCurrency: string;
  baseCurrency: string;
  marketReturnPct: number;
  fxReturnPct: number;
}

export interface ValuationSubPositionDto {
  id: string;
  transactionId: string | null;
  label: string;
  date: string | null;
  quantity: number;
  txPrice: number;
  txCurrency: string;
  sharePercent: number;
  investedBase: number;
  valueBase: number;
  profitBase: number;
  profitPct: number;
  xirrPct: number | null;
  currentPriceNative: number;
  averageBuyPriceNative: number;
  bondRate: number | null;
  bondMargin: number | null;
  fxDecomposition: FxDecompositionDto | null;
}

export interface ValuationPositionDto {
  id: string;
  instrumentId: string | null;
  symbol: string;
  name: string;
  category: 'ETF' | 'BOND' | 'STOCK';
  categoryCode: AssetCategoryCode | string;
  quoteCurrency: string;
  quantity: number;
  currentPriceNative: number;
  averageBuyPriceNative: number;
  valueBase: number;
  investedBase: number;
  profitBase: number;
  grossProfitBase: number;
  profitPct: number;
  dailyChangeBase: number;
  dailyChangePct: number;
  allocationPct: number;
  xirrPct: number | null;
  dividendsBase: number;
  realizedProfitBase: number;
  bondFamily: string | null;
  bondRate: number | null;
  bondRateMin: number | null;
  bondRateMax: number | null;
  bondMargin: number | null;
  isPriceUnavailable: boolean;
  isCpiUnavailable: boolean;
  subPositionsType: 'PURCHASES' | 'BONDS_YEARLY';
  fxDecomposition: FxDecompositionDto | null;
  subPositions: ValuationSubPositionDto[];
}

export interface EquityWeightDto {
  symbol: string;
  name: string;
  categoryCode: AssetCategoryCode | string;
  quoteCurrency: string;
  valueBase: number;
  weightPct: number;
}

export interface ProjectionSeedDto {
  startTotalValue: number;
  startEquityValue: number;
  startBondValue: number;
  startInvestedCapital: number;
  weightedBondYieldPct: number | null;
  currentCpiPct: number | null;
  belkaTaxRate: number;
  equityWeights: EquityWeightDto[];
}

export interface PortfolioValuationResponse {
  portfolioId: string;
  baseCurrency: string;
  taxMode: TaxMode;
  summary: ValuationSummaryDto;
  positions: ValuationPositionDto[];
  projectionSeed: ProjectionSeedDto;
}

export interface TimelineContributorDto {
  id: string;
  name: string;
  symbol: string;
  category: 'ETF' | 'BOND' | 'STOCK' | 'OTHER';
  valuationBase: number;
  costBase: number;
  gainBase: number;
  returnContributionPct: number;
  sharePct: number;
  isOther: boolean;
}

export interface TimelinePointDto {
  date: string;
  value: number;
  invested: number;
  netProfit: number;
  returnPct: number;
  stepGain: number;
  equityValue: number;
  bondValue: number;
  contributors: TimelineContributorDto[];
}

export interface PortfolioTimelineResponse {
  portfolioId: string;
  baseCurrency: string;
  timeframe: TimeframeOption | string;
  taxMode: TaxMode;
  periodProfit: number;
  periodProfitPct: number;
  periodTwrPct: number;
  points: TimelinePointDto[];
  contributors: TimelineContributorDto[];
}

export interface MonthlyCategoryDto {
  categoryCode: AssetCategoryCode | string;
  invested: number;
  currentValue: number;
  profitGross: number;
  profitNet: number;
  returnGrossPct: number;
  returnNetPct: number;
  sharePct: number;
}

export interface MonthlyPositionDto {
  key: string;
  symbol: string;
  name: string;
  categoryCode: AssetCategoryCode | string;
  invested: number;
  currentValue: number;
  profitGross: number;
  profitNet: number;
  profitNetMom: number;
  returnGrossPct: number;
  returnNetPct: number;
  sharePct: number;
}

export interface MonthlyReportDto {
  monthIndex: number;
  status: 'RECORDED' | 'BEFORE_PORTFOLIO' | 'FUTURE';
  portfolioValue: number;
  investedTotal: number;
  profitGross: number;
  returnGrossPct: number;
  profitNet: number;
  returnNetPct: number;
  profitNetMom: number;
  returnNetMomPct: number;
  cpiInflation: number | null;
  inflationIsFallback: boolean;
  realProfitPct: number | null;
  breakdown: MonthlyCategoryDto[];
  positions?: MonthlyPositionDto[];
}

export interface AnnualReportResponse {
  portfolioId: string;
  baseCurrency: string;
  year: number;
  taxMode: TaxMode;
  isCurrentYear: boolean;
  availableYears: number[];
  ytdPortfolioValue: number;
  ytdInvested: number;
  ytdProfitGross: number;
  ytdReturnGrossPct: number;
  ytdProfitNet: number;
  ytdReturnNetPct: number;
  ytdTwrPct: number;
  ytdRealReturnPct: number | null;
  avgCpi: number | null;
  months: MonthlyReportDto[];
}

export interface ProjectionRequestPayload {
  horizonYears: number;
  monthlyDca: number;
  dcaEquityShare: number;
  taxMode: TaxMode;
  realMode: boolean;
  bondRollover: boolean;
  targetGoal: number | null;
  etfReturnOverridePct: number | null;
}

export interface ProjectionPointResponse {
  monthOffset: number;
  date: string;
  p10: number;
  p50: number;
  p90: number;
  invested: number;
}

export interface ProjectionSummaryResponse {
  terminalP10: number;
  terminalP50: number;
  terminalP90: number;
  totalInvested: number;
  goalProbability: number | null;
}

export interface ProjectionResponse {
  points: ProjectionPointResponse[];
  proxyWarnings: string[];
  summary: ProjectionSummaryResponse;
}
