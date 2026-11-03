import type { TransactionResponse, PositionResponse } from '../../../types/portfolio.ts';
import type { InstrumentResponse } from '../../../types/instrument.ts';
import type {
  TaxMode,
  TimeframeOption,
  ProjectionHorizonOption,
  InflationAdjustMode,
} from '../../../types/dashboard.ts';
import type { EtfProfile } from '../../domain/assets.ts';
import type { BondLot } from '../../domain/bondLots.ts';

export interface UnifiedEngineParams {
  transactions: TransactionResponse[];
  positions: PositionResponse[];
  instruments?: Record<string, InstrumentResponse>;
  totalPortfolioValue: number;
  totalPortfolioCost: number;
  taxMode: TaxMode;
  timeframe: TimeframeOption;
  projectionHorizon: ProjectionHorizonOption;
  cpi: number;
  rates: Record<string, number>;
  baseCurrency: string;
  instrumentPrices?: Record<string, number>;
  monthlyDca?: number;
  dcaBondShare?: number;
  expectedEtfReturn?: number;
  etfVolatility?: number;
  empiricalTer?: number;
  bondRollover?: boolean;
  isIkeAccount?: boolean;
  whtDrag?: number;
  historicalPrices?: Record<string, Record<string, number>>;
  historicalSortedDates?: Record<string, string[]>;
  inflationAdjustMode?: InflationAdjustMode;
  targetGoal?: number | null;
  locale?: string;
}

export interface PreparedTx {
  timeMs: number;
  executedAt: string | Date | number;
  transactionType: string;
  quantity: number;
  fx: number;
  txCost: number;
  isBond: boolean;
  bondRate?: number | null;
  bondMargin?: number | null;
  seriesName?: string | null;
  termMonths?: number | null;
  symbol: string;
  fallbackPrice: number;
  instrumentId: string;
  currency: string;
}

export interface RawContributor {
  key: string;
  label: string;
  assetType: 'ETF' | 'BOND' | 'STOCK' | 'OTHER';
  valuation: number;
  cost: number;
  isBond: boolean;
  netProfit: number;
}

export interface TimelineState {
  now: number;
  startMs: number;
  stepDays: number;
  stepMs: number;
  totalHistorySteps: number;
  isProjectionActive: boolean;
  horizonYears: number;
  preparedTxs: PreparedTx[];
  bondLotsMap: Map<string, BondLot[]>;
  getBondValuation: (pos: PositionResponse, targetDate: number | string | Date, fx: number) => number;
  posInstrumentCounts: Map<string, number>;
  currentBondCapital: number;
  currentEquityCapital: number;
  currentBondCost: number;
  currentEquityCost: number;
  equityHoldings: { profile: EtfProfile; value: number }[];
  userBondValSum: number;
  userBondWeightedRateSum: number;
}
