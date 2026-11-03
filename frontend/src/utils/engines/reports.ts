import i18n from '../../i18n/config.ts';
import type { TransactionResponse } from '../../types/portfolio.ts';
import type { InstrumentResponse } from '../../types/instrument.ts';
import type { PriceQuote } from '../../services/market.service.ts';
import type {
  AnnualSummaryData,
  MonthlyCategoryBreakdown,
  MonthlyReportRecord,
} from '../../types/reports.types.ts';
import { isBondItem } from '../domain/assets.ts';
import { getHistoricalPrice } from '../domain/prices.ts';
import { standardizeDate } from '../domain/dates.ts';
import { calculateTwr } from './twr.ts';
import { computeBelka } from '../domain/tax.ts';
import { resolveTxFx, resolveTxCost } from '../domain/transactions.ts';
import { reduceBondLotsFifo, valueBondLots, type BondLot } from '../domain/bondLots.ts';
import { compoundHurdle } from '../domain/inflation.ts';

export interface RealReportsParams {
  transactions: TransactionResponse[];
  instruments: Record<string, InstrumentResponse>;
  quotes: Record<string, PriceQuote>;
  rates: Record<string, number>;
  historicalPrices?: Record<string, Record<string, number>>;
  historicalSortedDates?: Record<string, string[]>;
  cpi?: number | null;
  monthlyCpi?: Record<number, number>;
  baseCurrency: string;
  isIkeAccount: boolean;
  year: number;
  t?: (key: string, options?: any) => string;
  locale?: string;
  nowTimestamp?: number;
}

interface CategoryInfo {
  name: string;
  color: string;
}

function resolveCategory(
  tx: TransactionResponse,
  instruments: Record<string, InstrumentResponse>,
  t?: (key: string, options?: any) => string
): CategoryInfo {
  const translate = t || i18n.t.bind(i18n);
  const inst = instruments[tx.instrumentId];
  if (isBondItem(tx, inst)) {
    return { name: translate('reports.catTreasuryBonds'), color: '#3b82f6' };
  }

  const symbol = (inst?.symbol || tx.seriesName || '').toUpperCase();
  const name = (inst?.name || '').toUpperCase();

  if (symbol.includes('SXR8') || symbol.includes('VUAA') || name.includes('S&P 500') || name.includes('SP500')) {
    return { name: 'S&P 500', color: '#10b981' };
  }
  if (symbol.includes('VWCE') || symbol.includes('FWRA') || name.includes('ALL-WORLD')) {
    return { name: 'ETF All-World', color: '#8b5cf6' };
  }
  if (symbol.includes('EUNL') || symbol.includes('IWDA') || symbol.includes('SWDA') || name.includes('MSCI WORLD')) {
    return { name: 'ETF World', color: '#06b6d4' };
  }
  if (symbol.includes('AMEM') || symbol.includes('EIMI') || symbol.includes('EM') || name.includes('EMERGING')) {
    return { name: translate('reports.catEmerging'), color: '#f59e0b' };
  }
  if (symbol.includes('PKO') || inst?.currency === 'PLN') {
    return { name: translate('reports.catPlStocks'), color: '#ec4899' };
  }
  if (inst?.currency === 'USD' || symbol.includes('AAPL')) {
    return { name: translate('reports.catUsStocks'), color: '#6366f1' };
  }

  return { name: inst?.name || symbol || translate('reports.catOther'), color: '#14b8a6' };
}

export function extractAvailableYears(transactions: TransactionResponse[]): number[] {
  const yearsSet = new Set<number>();
  const currentYear = new Date().getFullYear();
  yearsSet.add(currentYear);

  for (const tx of transactions) {
    if (tx.executedAt) {
      const y = new Date(tx.executedAt).getFullYear();
      if (!isNaN(y) && y > 2000 && y < 2100) {
        yearsSet.add(y);
      }
    }
  }

  return Array.from(yearsSet).sort((a, b) => b - a);
}

function createUpcomingMonthRecord(
  m: number,
  year: number,
  locale: string,
  monthInflation: number | null
): MonthlyReportRecord {
  const d = new Date(year, m, 1);
  return {
    monthIndex: m,
    monthName: d.toLocaleDateString(locale, { month: 'long' }).replace(/^./, (str) => str.toUpperCase()),
    shortName: d.toLocaleDateString(locale, { month: 'short' }).replace(/^./, (str) => str.toUpperCase()),
    year,
    portfolioValue: 0,
    investedTotal: 0,
    profitGross: 0,
    returnGrossPct: 0,
    profitNet: 0,
    returnNetPct: 0,
    profitNetMom: 0,
    returnNetMomPct: 0,
    cpiInflation: monthInflation != null ? Number(monthInflation.toFixed(1)) : null,
    realProfitPct: null,
    status: 'UPCOMING',
    breakdown: [],
  };
}

export function generateRealAnnualSummary({
  transactions,
  instruments,
  quotes,
  rates,
  historicalPrices = {},
  historicalSortedDates = {},
  cpi = null,
  monthlyCpi = {},
  baseCurrency,
  isIkeAccount,
  year,
  t,
  locale = 'pl-PL',
  nowTimestamp,
}: RealReportsParams): AnnualSummaryData {
  const now = nowTimestamp ?? Date.now();
  const records: MonthlyReportRecord[] = [];

  const preparedTxs = transactions
    .map((tx) => {
      const timeMs = new Date(tx.executedAt).getTime();
      const fx = resolveTxFx(tx, baseCurrency, rates, 'current');
      const txCost = resolveTxCost(tx, fx);
      const inst = instruments[tx.instrumentId];
      const isBond = isBondItem(tx, inst);
      const cat = resolveCategory(tx, instruments, t);
      const symbol = inst?.symbol || tx.seriesName || '';
      const fallbackPrice = quotes[symbol]?.price ?? tx.price;
      return {
        timeMs,
        executedAt: tx.executedAt,
        quantity: tx.quantity,
        fx,
        txCost,
        isBond,
        bondRate: tx.bondRate,
        bondMargin: tx.bondMargin,
        seriesName: tx.seriesName,
        termMonths: tx.termMonths,
        symbol,
        cat,
        fallbackPrice,
        transactionType: tx.transactionType || 'BUY',
        instrumentId: tx.instrumentId,
        currency: tx.currency,
      };
    })
    .sort((a, b) => a.timeMs - b.timeMs);

  const holdings = new Map<string, {
    currentQty: number;
    costBasis: number;
    isBond: boolean;
    symbol: string;
    fallbackPrice: number;
    fx: number;
    cat: CategoryInfo;
    costLots: { cost: number; timeMs: number }[];
    lots: BondLot[];
  }>();
  let txIdx = 0;

  let lastKnownCpi: number | null = cpi ?? null;
  for (let m = 0; m < 12; m++) {
    const monthStartMs = new Date(year, m, 1, 0, 0, 0, 0).getTime();
    const monthEndMs = new Date(year, m + 1, 0, 23, 59, 59, 999).getTime();
    const hasMonthCpi = monthlyCpi[m] !== undefined;
    const monthInflation = hasMonthCpi ? monthlyCpi[m] : lastKnownCpi;
    if (hasMonthCpi) {
      lastKnownCpi = monthInflation;
    }

    if (monthStartMs > now) {
      records.push(createUpcomingMonthRecord(m, year, locale, monthInflation));
      continue;
    }

    const tMs = Math.min(now, monthEndMs);
    if (preparedTxs.length === 0 || preparedTxs[0].timeMs > tMs) {
      records.push(createUpcomingMonthRecord(m, year, locale, monthInflation));
      continue;
    }

    const monthEndStr = standardizeDate(tMs);

    while (txIdx < preparedTxs.length && preparedTxs[txIdx].timeMs <= tMs) {
      const tx = preparedTxs[txIdx++];
      const key = tx.isBond
        ? `${tx.instrumentId}_${tx.seriesName || ''}`
        : `${tx.instrumentId}_${tx.currency}`;

      let h = holdings.get(key);
      if (!h) {
        h = {
          currentQty: 0,
          costBasis: 0,
          isBond: tx.isBond,
          symbol: tx.symbol,
          fallbackPrice: tx.fallbackPrice,
          fx: tx.fx,
          cat: tx.cat,
          costLots: [],
          lots: [],
        };
        holdings.set(key, h);
      }

      if (tx.transactionType === 'BUY') {
        h.currentQty += tx.quantity;
        h.costBasis += tx.txCost;
        h.costLots.push({ cost: tx.txCost, timeMs: tx.timeMs });
        if (tx.isBond) {
          h.lots.push({
            quantity: tx.quantity,
            executedAt: tx.executedAt,
            bondRate: tx.bondRate,
            bondMargin: tx.bondMargin,
            seriesName: tx.seriesName,
            termMonths: tx.termMonths,
            fx: tx.fx,
          });
        }
      } else if (tx.transactionType === 'SELL' && h.currentQty > 0) {
        const sellQty = Math.min(tx.quantity, h.currentQty);
        const keepRatio = 1 - sellQty / h.currentQty;
        h.costBasis *= keepRatio;
        h.costLots.forEach((l) => { l.cost *= keepRatio; });
        h.currentQty -= sellQty;
        if (h.currentQty <= 0) {
          h.costBasis = 0;
          h.currentQty = 0;
          h.lots = [];
          h.costLots = [];
        } else if (tx.isBond) {
          reduceBondLotsFifo(h.lots, sellQty);
        }
      }
    }

    const categoryMap: Record<
      string,
      {
        name: string;
        color: string;
        invested: number;
        currentValue: number;
      }
    > = {};

    let inflationHurdle = 0;
    for (const h of holdings.values()) {
      if (h.currentQty <= 0) continue;
      if (!categoryMap[h.cat.name]) {
        categoryMap[h.cat.name] = {
          name: h.cat.name,
          color: h.cat.color,
          invested: 0,
          currentValue: 0,
        };
      }
      categoryMap[h.cat.name].invested += h.costBasis;
      if (monthInflation != null) { inflationHurdle += compoundHurdle(h.costLots, monthInflation, tMs); }

      if (h.isBond) {
        categoryMap[h.cat.name].currentValue += valueBondLots(h.lots, {
          cpi: monthInflation,
          targetDate: tMs,
        });
      } else {
        const realClose = getHistoricalPrice(
          historicalPrices[h.symbol],
          historicalSortedDates[h.symbol],
          monthEndStr,
          h.fallbackPrice
        );
        categoryMap[h.cat.name].currentValue += h.currentQty * realClose * h.fx;
      }
    }

    Object.values(categoryMap).forEach((c) => {
      c.invested = Math.max(0, c.invested);
      c.currentValue = Math.max(0, c.currentValue);
    });

    const totalInvested = Object.values(categoryMap).reduce(
      (sum, c) => sum + c.invested,
      0
    );
    const totalValue = Object.values(categoryMap).reduce(
      (sum, c) => sum + c.currentValue,
      0
    );

    const breakdown: MonthlyCategoryBreakdown[] = Object.values(categoryMap).map(
      (c) => {
        const profitGross = c.currentValue - c.invested;
        const tax = computeBelka(profitGross, isIkeAccount);
        const profitNet = profitGross - tax;
        const returnGrossPct =
          c.invested > 0 ? (profitGross / c.invested) * 100 : 0;
        const returnNetPct =
          c.invested > 0 ? (profitNet / c.invested) * 100 : 0;
        const sharePct = totalValue > 0 ? (c.currentValue / totalValue) * 100 : 0;

        return {
          category: c.name,
          color: c.color,
          invested: Number(c.invested.toFixed(2)),
          currentValue: Number(c.currentValue.toFixed(2)),
          profitGross: Number(profitGross.toFixed(2)),
          profitNet: Number(profitNet.toFixed(2)),
          returnGrossPct: Number(returnGrossPct.toFixed(2)),
          returnNetPct: Number(returnNetPct.toFixed(2)),
          sharePct: Number(sharePct.toFixed(2)),
        };
      }
    );

    const profitGrossTotal = totalValue - totalInvested;
    const profitNetTotal = breakdown.reduce((sum, b) => sum + b.profitNet, 0);
    const returnGrossTotalPct =
      totalInvested > 0 ? (profitGrossTotal / totalInvested) * 100 : 0;
    const returnNetTotalPct =
      totalInvested > 0 ? (profitNetTotal / totalInvested) * 100 : 0;
    const realProfitPct = monthInflation != null && inflationHurdle > 0 ? ((totalInvested + profitNetTotal) / inflationHurdle - 1) * 100 : null;

    records.push({
      monthIndex: m,
      monthName: new Date(year, m, 1).toLocaleDateString(locale, { month: 'long' }).replace(/^./, (str) => str.toUpperCase()),
      shortName: new Date(year, m, 1).toLocaleDateString(locale, { month: 'short' }).replace(/^./, (str) => str.toUpperCase()),
      year,
      portfolioValue: Number(totalValue.toFixed(2)),
      investedTotal: Number(totalInvested.toFixed(2)),
      profitGross: Number(profitGrossTotal.toFixed(2)),
      returnGrossPct: Number(returnGrossTotalPct.toFixed(2)),
      profitNet: Number(profitNetTotal.toFixed(2)),
      returnNetPct: Number(returnNetTotalPct.toFixed(2)),
      profitNetMom: 0,
      returnNetMomPct: 0,
      cpiInflation: monthInflation != null ? Number(monthInflation.toFixed(1)) : null,
      realProfitPct: realProfitPct != null ? Number(realProfitPct.toFixed(2)) : null,
      status: 'RECORDED',
      breakdown,
      baseCurrency,
    });
  }

  for (let m = 0; m < records.length; m++) {
    const cur = records[m];
    if (cur.status === 'RECORDED') {
      let prevRecorded: MonthlyReportRecord | null = null;
      for (let p = m - 1; p >= 0; p--) {
        if (records[p].status === 'RECORDED') {
          prevRecorded = records[p];
          break;
        }
      }

      if (prevRecorded) {
        const momDiff = cur.profitNet - prevRecorded.profitNet;
        const momPct =
          prevRecorded.portfolioValue > 0
            ? (momDiff / prevRecorded.portfolioValue) * 100
            : cur.returnNetPct;
        cur.profitNetMom = Number(momDiff.toFixed(2));
        cur.returnNetMomPct = Number(momPct.toFixed(2));
      } else {
        cur.profitNetMom = cur.profitNet;
        cur.returnNetMomPct = cur.returnNetPct;
      }
    }
  }

  const recordedOnly = records.filter((r) => r.status === 'RECORDED');
  const latest = recordedOnly[recordedOnly.length - 1];

  const ytdPortfolioValue = latest ? latest.portfolioValue : 0;
  const ytdInvested = latest ? latest.investedTotal : 0;
  const ytdProfitGross = latest ? latest.profitGross : 0;
  const ytdProfitNet = latest ? latest.profitNet : 0;
  const ytdReturnGrossPct =
    ytdInvested > 0 ? (ytdProfitGross / ytdInvested) * 100 : 0;
  const ytdReturnNetPct =
    ytdInvested > 0 ? (ytdProfitNet / ytdInvested) * 100 : 0;
  const recordedWithCpi = recordedOnly.filter((r) => r.cpiInflation != null);
  const avgCpi =
    recordedWithCpi.length > 0 ? Number((recordedWithCpi.reduce((acc, r) => acc + (r.cpiInflation ?? 0), 0) / recordedWithCpi.length).toFixed(1)) : null;
  const ytdRealReturnPct = latest?.realProfitPct ?? null;

  const monthlySubPeriods = [];
  for (let idx = 0; idx < recordedOnly.length; idx++) {
    const cur = recordedOnly[idx];
    const prev = idx > 0 ? recordedOnly[idx - 1] : null;
    const startValue = prev ? prev.portfolioValue : 0;
    const endValue = cur.portfolioValue;
    const netCashFlow = prev ? cur.investedTotal - prev.investedTotal : cur.investedTotal;
    monthlySubPeriods.push({ startValue, endValue, netCashFlow });
  }
  const ytdTwr = calculateTwr(monthlySubPeriods);

  return {
    year,
    records,
    ytdPortfolioValue,
    ytdInvested,
    ytdProfitGross,
    ytdReturnGrossPct: Number(ytdReturnGrossPct.toFixed(2)),
    ytdProfitNet,
    ytdReturnNetPct: Number(ytdReturnNetPct.toFixed(2)),
    ytdRealReturnPct: ytdRealReturnPct != null ? Number(ytdRealReturnPct.toFixed(2)) : null,
    ytdTwr: Number((ytdTwr * 100).toFixed(2)),
    avgCpi,
    baseCurrency,
  };
}
