import type { TransactionResponse, PositionResponse } from '../../../types/portfolio.ts';
import { isBondItem, detectEtfProfile } from '../../domain/assets.ts';
import { calculateBondCurrentPrice } from '../../domain/bondPricing.ts';
import { resolveTxFx, resolveTxCost } from '../../domain/transactions.ts';
import { reduceBondLotsFifo, valueBondLots, type BondLot } from '../../domain/bondLots.ts';
import type { UnifiedEngineParams, TimelineState, PreparedTx } from './types.ts';

export function prepareTimelineState({
  transactions,
  positions,
  instruments = {},
  totalPortfolioValue,
  totalPortfolioCost,
  timeframe,
  projectionHorizon,
  cpi,
  rates,
  baseCurrency,
  instrumentPrices = {},
}: UnifiedEngineParams): TimelineState {
  const now = Date.now();
  const preparedTxs: PreparedTx[] = transactions
    .map((tx: TransactionResponse) => {
      const timeMs = new Date(tx.executedAt).getTime();
      const fx = resolveTxFx(tx, baseCurrency, rates, 'current');
      const txCost = resolveTxCost(tx, fx);
      const inst = instruments[tx.instrumentId];
      const isBond = isBondItem(tx, inst);
      const symbol = inst?.symbol || tx.seriesName || '';
      const fallbackPrice = instrumentPrices[tx.instrumentId] ?? tx.price;
      return {
        timeMs,
        executedAt: tx.executedAt,
        transactionType: tx.transactionType || 'BUY',
        quantity: tx.quantity,
        fx,
        txCost,
        isBond,
        bondRate: tx.bondRate,
        bondMargin: tx.bondMargin,
        seriesName: tx.seriesName,
        termMonths: tx.termMonths,
        symbol,
        fallbackPrice,
        instrumentId: tx.instrumentId,
        currency: tx.currency,
      };
    })
    .sort((a: PreparedTx, b: PreparedTx) => a.timeMs - b.timeMs);

  const earliestTx = preparedTxs.length > 0 ? preparedTxs[0].timeMs : now - 365 * 24 * 3600 * 1000;

  let currentBondCapital = 0;
  let currentEquityCapital = 0;
  let currentBondCost = 0;
  let currentEquityCost = 0;
  const equityHoldings: TimelineState['equityHoldings'] = [];
  let userBondValSum = 0;
  let userBondWeightedRateSum = 0;

  const bondLotsMap = new Map<string, BondLot[]>();

  for (const tx of preparedTxs) {
    if (!tx.isBond) continue;
    const key = `${tx.instrumentId}_${tx.seriesName || ''}`;
    let lots = bondLotsMap.get(key);
    if (!lots) {
      lots = [];
      bondLotsMap.set(key, lots);
    }
    if (tx.transactionType === 'BUY') {
      lots.push({
        quantity: tx.quantity,
        executedAt: tx.executedAt,
        bondRate: tx.bondRate,
        bondMargin: tx.bondMargin,
        seriesName: tx.seriesName,
        termMonths: tx.termMonths,
        fx: tx.fx,
      });
    } else if (tx.transactionType === 'SELL') {
      reduceBondLotsFifo(lots, tx.quantity);
    }
  }

  const getBondValuation = (
    pos: PositionResponse,
    targetDate: number | string | Date,
    fx: number
  ): number => {
    const key = `${pos.instrumentId}_${pos.seriesName || ''}`;
    const lots = bondLotsMap.get(key);
    if (lots && lots.length > 0) {
      return valueBondLots(lots, {
        cpi,
        targetDate,
        fallbackFx: fx,
        fallback: pos,
      });
    }
    const p = calculateBondCurrentPrice({
      purchaseDate: pos.purchaseDate,
      bondRate: pos.bondRate,
      bondMargin: pos.bondMargin,
      cpi,
      targetDate,
      seriesName: pos.seriesName,
      termMonths: pos.termMonths,
    });
    return pos.quantity * p * fx;
  };

  const posInstrumentCounts = new Map<string, number>();
  for (const p of positions) {
    posInstrumentCounts.set(p.instrumentId, (posInstrumentCounts.get(p.instrumentId) || 0) + 1);
  }

  for (const pos of positions) {
    const inst = instruments[pos.instrumentId];
    const isBond = isBondItem(pos, inst);
    const fx = (pos.currency === baseCurrency ? 1 : rates[pos.currency]) || 1;
    let val = 0;
    let cost = 0;

    if (pos.totalCostBase != null && pos.totalCostBase > 0) {
      cost = pos.totalCostBase;
    } else {
      cost = pos.quantity * (pos.averageBuyPrice || 100) * fx;
    }

    if (isBond) {
      val = getBondValuation(pos, now, fx);
    } else {
      const curPrice = instrumentPrices[pos.instrumentId] ?? pos.averageBuyPrice ?? 100;
      val = pos.quantity * curPrice * fx;
    }

    if (isBond) {
      currentBondCapital += val;
      currentBondCost += cost;
      let r: number | undefined = pos.bondRate != null ? pos.bondRate : undefined;
      if (pos.bondMargin != null && pos.bondMargin > 0 && cpi != null) {
        r = cpi + pos.bondMargin;
      }
      if (r !== undefined) {
        userBondValSum += val;
        userBondWeightedRateSum += val * r;
      }
    } else {
      currentEquityCapital += val;
      currentEquityCost += cost;
      const profile = detectEtfProfile(inst?.symbol || pos.seriesName || '', inst?.name || '');
      equityHoldings.push({ profile, value: val });
    }
  }

  if (currentBondCapital + currentEquityCapital === 0) {
    currentEquityCapital = totalPortfolioValue;
    currentEquityCost = totalPortfolioCost;
  }

  let startMs: number;
  switch (timeframe) {
    case '1M':
      startMs = now - 30 * 24 * 3600 * 1000;
      break;
    case '3M':
      startMs = now - 90 * 24 * 3600 * 1000;
      break;
    case '6M':
      startMs = now - 180 * 24 * 3600 * 1000;
      break;
    case 'YTD': {
      const yearStart = new Date(new Date().getFullYear(), 0, 1).getTime();
      startMs = Math.min(yearStart, now - 30 * 24 * 3600 * 1000);
      break;
    }
    case '1R':
      startMs = now - 365 * 24 * 3600 * 1000;
      break;
    case 'MAX':
    default:
      startMs = Math.min(earliestTx, now - 90 * 24 * 3600 * 1000);
      break;
  }

  if (startMs >= now) {
    startMs = now - 30 * 24 * 3600 * 1000;
  }

  const horizonYearsMap: Record<string, number> = {
    '3Y': 3,
    '5Y': 5,
    '10Y': 10,
    '20Y': 20,
  };

  const horizonYears = horizonYearsMap[projectionHorizon] || 0;
  const isProjectionActive = horizonYears > 0;

  let stepDays = 1;
  if (isProjectionActive) {
    if (horizonYears <= 3) {
      stepDays = 14;
    } else if (horizonYears <= 5) {
      stepDays = 21;
    } else {
      stepDays = 30.4375;
    }
  }

  const oneDayMs = 24 * 3600 * 1000;
  const stepMs = stepDays * oneDayMs;
  const totalHistorySteps = isProjectionActive
    ? Math.max(1, Math.round((now - startMs) / stepMs))
    : Math.max(1, Math.round((now - startMs) / oneDayMs));

  return {
    now,
    startMs,
    stepDays,
    stepMs,
    totalHistorySteps,
    isProjectionActive,
    horizonYears,
    preparedTxs,
    bondLotsMap,
    getBondValuation,
    posInstrumentCounts,
    currentBondCapital,
    currentEquityCapital,
    currentBondCost,
    currentEquityCost,
    equityHoldings,
    userBondValSum,
    userBondWeightedRateSum,
  };
}
