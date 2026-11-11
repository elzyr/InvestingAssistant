import type { PositionResponse, TransactionResponse } from '../../types/portfolio.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { PriceQuote } from '../../services/market.service.ts';
import type { EnrichedHolding } from '../engines/dashboard.ts';
import { isBondItem } from './assets.ts';
import { calculateBondCurrentPrice } from './bondPricing.ts';
import { reduceBondLotsFifo, valueBondLots, type BondLot } from './bondLots.ts';
import { MS_PER_DAY, standardizeDate } from './dates.ts';

export interface EnrichHoldingsParams {
  positions: PositionResponse[];
  transactions: TransactionResponse[];
  instruments: Record<string, InstrumentResponse>;
  quotes: Record<string, PriceQuote>;
  rates: Record<string, number>;
  baseCurrency: string;
  cpi: number | null;
  nowTimestamp: number;
  historicalPrices?: Record<string, Record<string, number>>;
  historicalSortedDates?: Record<string, string[]>;
}

function buildActiveBondLots(
  pos: PositionResponse,
  transactions: TransactionResponse[]
): BondLot[] {
  const bondTxs = transactions
    .filter((t) => t.instrumentId === pos.instrumentId && (t.seriesName || '') === (pos.seriesName || ''))
    .sort((a, b) => new Date(a.executedAt).getTime() - new Date(b.executedAt).getTime());

  const lots: BondLot[] = [];
  let soldQty = 0;
  for (const tx of bondTxs) {
    if (tx.transactionType === 'BUY') {
      lots.push({
        quantity: tx.quantity,
        executedAt: tx.executedAt,
        bondRate: tx.bondRate ?? pos.bondRate,
        bondMargin: tx.bondMargin ?? pos.bondMargin,
        seriesName: pos.seriesName,
        termMonths: tx.termMonths ?? pos.termMonths,
        fx: 1,
      });
    } else if (tx.transactionType === 'SELL') {
      soldQty += tx.quantity;
    }
  }
  if (soldQty > 0) {
    reduceBondLotsFifo(lots, soldQty);
  }
  return lots;
}

export function enrichPortfolioHoldings({
  positions,
  transactions,
  instruments,
  quotes,
  rates,
  baseCurrency,
  cpi,
  nowTimestamp,
  historicalPrices,
  historicalSortedDates,
}: EnrichHoldingsParams): EnrichedHolding[] {
  const todayDateStr = standardizeDate(nowTimestamp);

  return positions.map((pos) => {
    const inst = instruments[pos.instrumentId];
    const isBond = isBondItem(pos, inst);
    const symbol = pos.seriesName || inst?.symbol || 'UNKNOWN';
    const isForeign = pos.currency !== baseCurrency;
    const fallbackFx =
      pos.totalCostBase != null && pos.quantity * pos.averageBuyPrice > 0
        ? pos.totalCostBase / (pos.quantity * pos.averageBuyPrice)
        : undefined;
    const fxRate = (isForeign ? rates[pos.currency] : 1) || fallbackFx || 1;

    let currentPrice: number;
    let previousPrice: number;

    if (isBond) {
      const activeLots = buildActiveBondLots(pos, transactions);
      if (activeLots.length > 0 && pos.quantity > 0) {
        const sumNow = valueBondLots(activeLots, {
          cpi,
          targetDate: nowTimestamp,
          fallbackFx: 1,
          fallback: pos,
        });
        const sumPrev = valueBondLots(activeLots, {
          cpi,
          targetDate: nowTimestamp - MS_PER_DAY,
          fallbackFx: 1,
          fallback: pos,
        });
        currentPrice = sumNow / pos.quantity;
        previousPrice = sumPrev / pos.quantity;
      } else {
        currentPrice = calculateBondCurrentPrice({
          purchaseDate: pos.purchaseDate,
          bondRate: pos.bondRate,
          bondMargin: pos.bondMargin,
          cpi,
          targetDate: nowTimestamp,
          seriesName: pos.seriesName,
          termMonths: pos.termMonths,
        });
        previousPrice = calculateBondCurrentPrice({
          purchaseDate: pos.purchaseDate,
          bondRate: pos.bondRate,
          bondMargin: pos.bondMargin,
          cpi,
          targetDate: nowTimestamp - MS_PER_DAY,
          seriesName: pos.seriesName,
          termMonths: pos.termMonths,
        });
      }
    } else {
      const quoteSymbol = inst?.symbol || symbol;
      const quote = quotes[quoteSymbol] ?? quotes[symbol];
      currentPrice = quote?.price && quote.price > 0 ? quote.price : pos.averageBuyPrice;
      previousPrice = currentPrice;
      if (quote?.previousClose && quote.previousClose > 0) {
        previousPrice = quote.previousClose;
      } else if (historicalPrices && historicalSortedDates && inst?.symbol && historicalPrices[inst.symbol]) {
        const dates = historicalSortedDates[inst.symbol];
        const dateMap = historicalPrices[inst.symbol];
        if (dates && dates.length > 0) {
          const lastDate = dates[dates.length - 1];
          if (lastDate >= todayDateStr && dates.length >= 2) {
            previousPrice = dateMap[dates[dates.length - 2]] ?? currentPrice;
          } else {
            previousPrice = dateMap[lastDate] ?? currentPrice;
          }
        }
      }
    }

    const totalVal = pos.quantity * currentPrice * fxRate;
    const totalCostVal = pos.totalCostBase !== undefined ? pos.totalCostBase : pos.quantity * pos.averageBuyPrice * fxRate;
    const profit = totalVal - totalCostVal;
    const profitPct = totalCostVal > 0 ? (profit / totalCostVal) * 100 : 0;
    const dailyChange = pos.quantity * (currentPrice - previousPrice) * fxRate;

    let assetType: 'ETF' | 'BOND' | 'STOCK' = 'ETF';
    if (isBond) {
      assetType = 'BOND';
    } else if (inst?.type === 'STOCK') {
      assetType = 'STOCK';
    }

    return {
      id: pos.id,
      instrumentId: pos.instrumentId,
      ticker: symbol,
      name: inst?.name || symbol,
      assetType,
      isBond,
      quantity: pos.quantity,
      averagePrice: pos.averageBuyPrice,
      currentPrice,
      previousPrice,
      currency: pos.currency,
      totalValue: totalVal,
      totalCost: totalCostVal,
      profit,
      profitPct,
      dailyChange,
      purchaseDate: pos.purchaseDate,
      seriesName: pos.seriesName,
      bondRate: pos.bondRate,
      bondMargin: pos.bondMargin,
      termMonths: pos.termMonths,
      isPriceUnavailable: Boolean(!isBond && quotes[inst?.symbol || symbol]?.isUnavailable),
    };
  });
}
