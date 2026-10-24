import type { TransactionResponse } from '../types/portfolio.ts';
import type { InstrumentResponse } from '../types/instrument.ts';
import type { PriceQuote } from '../services/market.service.ts';
import type {
  AnnualSummaryData,
  MonthlyCategoryBreakdown,
  MonthlyReportRecord,
} from '../types/reports.types.ts';
import { calculateBondCurrentPrice, getHistoricalPrice, isBondItem } from './portfolioUnifiedEngine.ts';



export interface RealReportsParams {
  transactions: TransactionResponse[];
  instruments: Record<string, InstrumentResponse>;
  quotes: Record<string, PriceQuote>;
  rates: Record<string, number>;
  historicalPrices?: Record<string, Record<string, number>>;
  historicalSortedDates?: Record<string, string[]>;
  cpi?: number;
  monthlyCpi?: Record<number, number>;
  baseCurrency: string;
  isIkeAccount: boolean;
  year: number;
  t?: (key: string, options?: any) => string;
  locale?: string;
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
  const inst = instruments[tx.instrumentId];
  if (isBondItem(tx, inst)) {
    return { name: t ? t('reports.catTreasuryBonds', 'Obligacje Skarbowe') : 'Obligacje Skarbowe', color: '#3b82f6' };
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
    return { name: t ? t('reports.catEmerging', 'ETF Rozwijające') : 'ETF Rozwijające', color: '#f59e0b' };
  }
  if (symbol.includes('PKO') || inst?.currency === 'PLN') {
    return { name: t ? t('reports.catPlStocks', 'Akcje PL') : 'Akcje PL', color: '#ec4899' };
  }
  if (inst?.currency === 'USD' || symbol.includes('AAPL')) {
    return { name: t ? t('reports.catUsStocks', 'Akcje US') : 'Akcje US', color: '#6366f1' };
  }

  return { name: inst?.name || symbol || (t ? t('reports.catOther', 'Inne aktywa') : 'Inne aktywa'), color: '#14b8a6' };
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

export function generateRealAnnualSummary({
  transactions,
  instruments,
  quotes,
  rates,
  historicalPrices = {},
  historicalSortedDates = {},
  cpi = 3.6,
  monthlyCpi = {},
  baseCurrency,
  isIkeAccount,
  year,
  t,
  locale = 'pl-PL',
}: RealReportsParams): AnnualSummaryData {
  const now = Date.now();
  const records: MonthlyReportRecord[] = [];

  const preparedTxs = transactions
    .map((tx) => {
      const timeMs = new Date(tx.executedAt).getTime();
      const fx = (tx.currency === baseCurrency ? 1 : rates[tx.currency]) || 1;
      const commission = tx.commission || 0;
      const txCost = tx.baseAmount ?? (
        tx.transactionType === 'SELL'
          ? (tx.quantity * tx.price - commission) * fx
          : (tx.quantity * tx.price + commission) * fx
      );
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
      };
    })
    .sort((a, b) => a.timeMs - b.timeMs);

  for (let m = 0; m < 12; m++) {
    const monthStartMs = new Date(year, m, 1, 0, 0, 0, 0).getTime();
    const monthEndMs = new Date(year, m + 1, 0, 23, 59, 59, 999).getTime();
    const hasMonthCpi = monthlyCpi[m] !== undefined;
    const monthInflation = hasMonthCpi ? monthlyCpi[m] : 0;

    if (monthStartMs > now) {
      records.push({
        monthIndex: m,
        monthName: new Date(year, m, 1).toLocaleDateString(locale, { month: 'long' }).replace(/^./, (str) => str.toUpperCase()),
        shortName: new Date(year, m, 1).toLocaleDateString(locale, { month: 'short' }).replace(/^./, (str) => str.toUpperCase()),
        year,
        portfolioValue: 0,
        investedTotal: 0,
        profitGross: 0,
        returnGrossPct: 0,
        profitNet: 0,
        returnNetPct: 0,
        profitNetMom: 0,
        returnNetMomPct: 0,
        cpiInflation: hasMonthCpi ? Number(monthInflation.toFixed(1)) : 0,
        realProfitPct: 0,
        status: 'UPCOMING',
        breakdown: [],
      });
      continue;
    }

    const tMs = Math.min(now, monthEndMs);
    if (preparedTxs.length === 0 || preparedTxs[0].timeMs > tMs) {
      records.push({
        monthIndex: m,
        monthName: new Date(year, m, 1).toLocaleDateString(locale, { month: 'long' }).replace(/^./, (str) => str.toUpperCase()),
        shortName: new Date(year, m, 1).toLocaleDateString(locale, { month: 'short' }).replace(/^./, (str) => str.toUpperCase()),
        year,
        portfolioValue: 0,
        investedTotal: 0,
        profitGross: 0,
        returnGrossPct: 0,
        profitNet: 0,
        returnNetPct: 0,
        profitNetMom: 0,
        returnNetMomPct: 0,
        cpiInflation: hasMonthCpi ? Number(monthInflation.toFixed(1)) : 0,
        realProfitPct: 0,
        status: 'UPCOMING',
        breakdown: [],
      });
      continue;
    }

    const dMonth = new Date(tMs);
    const monthEndStr = `${dMonth.getFullYear()}-${String(dMonth.getMonth() + 1).padStart(2, '0')}-${String(dMonth.getDate()).padStart(2, '0')}`;
    const holdings = new Map<string, {
      currentQty: number;
      costBasis: number;
      isBond: boolean;
      symbol: string;
      fallbackPrice: number;
      fx: number;
      cat: { name: string; color: string };
      lots: {
        quantity: number;
        executedAt: string | Date | number;
        bondRate?: number | null;
        bondMargin?: number | null;
        seriesName?: string | null;
        termMonths?: number | null;
        fx: number;
      }[];
    }>();

    for (let k = 0; k < preparedTxs.length; k++) {
      const tx = preparedTxs[k];
      if (tx.timeMs > tMs) break;

      const key = tx.isBond
        ? `${tx.instrumentId}_${tx.seriesName || ''}`
        : tx.instrumentId;

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
          lots: [],
        };
        holdings.set(key, h);
      }

      if (tx.transactionType === 'BUY') {
        h.currentQty += tx.quantity;
        h.costBasis += tx.txCost;
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
      } else if (tx.transactionType === 'SELL') {
        if (h.currentQty > 0) {
          const sellQty = Math.min(tx.quantity, h.currentQty);
          h.costBasis -= h.costBasis * (sellQty / h.currentQty);
          h.currentQty -= sellQty;
          if (h.currentQty <= 0) {
            h.costBasis = 0;
            h.currentQty = 0;
            h.lots = [];
          } else if (tx.isBond) {
            let toReduce = sellQty;
            while (toReduce > 0 && h.lots.length > 0) {
              if (h.lots[0].quantity <= toReduce) {
                toReduce -= h.lots[0].quantity;
                h.lots.shift();
              } else {
                h.lots[0].quantity -= toReduce;
                toReduce = 0;
              }
            }
          }
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

      if (h.isBond) {
        for (const lot of h.lots) {
          const unitPrice = calculateBondCurrentPrice({
            purchaseDate: lot.executedAt,
            bondRate: lot.bondRate,
            bondMargin: lot.bondMargin,
            cpi: hasMonthCpi ? monthInflation : (cpi ?? 3.6),
            targetDate: tMs,
            seriesName: lot.seriesName,
            termMonths: lot.termMonths,
          });
          categoryMap[h.cat.name].currentValue += lot.quantity * unitPrice * lot.fx;
        }
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
        const tax = isIkeAccount ? 0 : profitGross > 0 ? profitGross * 0.19 : 0;
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
    const realProfitPct = hasMonthCpi ? returnNetTotalPct - monthInflation : 0;

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
      cpiInflation: hasMonthCpi ? Number(monthInflation.toFixed(1)) : 0,
      realProfitPct: Number(realProfitPct.toFixed(2)),
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
  const recordedWithCpi = recordedOnly.filter((r) => r.cpiInflation > 0);
  const avgCpi =
    recordedWithCpi.length > 0
      ? recordedWithCpi.reduce((acc, r) => acc + r.cpiInflation, 0) / recordedWithCpi.length
      : 0;
  const ytdRealReturnPct = avgCpi > 0 ? ytdReturnNetPct - avgCpi : ytdReturnNetPct;

  return {
    year,
    records,
    ytdPortfolioValue,
    ytdInvested,
    ytdProfitGross,
    ytdReturnGrossPct: Number(ytdReturnGrossPct.toFixed(2)),
    ytdProfitNet,
    ytdReturnNetPct: Number(ytdReturnNetPct.toFixed(2)),
    ytdRealReturnPct: Number(ytdRealReturnPct.toFixed(2)),
    avgCpi: Number(avgCpi.toFixed(1)),
    baseCurrency,
  };
}
