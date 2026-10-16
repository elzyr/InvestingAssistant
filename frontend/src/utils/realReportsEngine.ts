import type { TransactionResponse } from '../types/portfolio.ts';
import type { InstrumentResponse } from '../types/instrument.ts';
import type { PriceQuote } from '../services/market.service.ts';
import type {
  AnnualSummaryData,
  MonthlyCategoryBreakdown,
  MonthlyReportRecord,
} from '../types/reports.types.ts';
import { calculateBondCurrentPrice, getHistoricalPrice } from './portfolioUnifiedEngine.ts';

const MONTH_NAMES = [
  'Styczeń',
  'Luty',
  'Marzec',
  'Kwiecień',
  'Maj',
  'Czerwiec',
  'Lipiec',
  'Sierpień',
  'Wrzesień',
  'Październik',
  'Listopad',
  'Grudzień',
];

const MONTH_SHORT = [
  'Sty',
  'Lut',
  'Mar',
  'Kwi',
  'Maj',
  'Cze',
  'Lip',
  'Sie',
  'Wrz',
  'Paź',
  'Lis',
  'Gru',
];

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
}

interface CategoryInfo {
  name: string;
  color: string;
}

function resolveCategory(
  tx: TransactionResponse,
  instruments: Record<string, InstrumentResponse>
): CategoryInfo {
  const isBond =
    tx.bondRate != null ||
    (tx.seriesName && tx.seriesName.length >= 3) ||
    (tx.currency === 'PLN' && tx.price === 100);

  if (isBond) {
    return { name: 'Obligacje Skarbowe', color: '#3b82f6' };
  }

  const inst = instruments[tx.instrumentId];
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
    return { name: 'ETF Rozwijające', color: '#f59e0b' };
  }
  if (symbol.includes('PKO') || inst?.currency === 'PLN') {
    return { name: 'Akcje PL', color: '#ec4899' };
  }
  if (inst?.currency === 'USD' || symbol.includes('AAPL')) {
    return { name: 'Akcje US', color: '#6366f1' };
  }

  return { name: inst?.name || symbol || 'Inne aktywa', color: '#14b8a6' };
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
}: RealReportsParams): AnnualSummaryData {
  const now = Date.now();
  const records: MonthlyReportRecord[] = [];

  const preparedTxs = transactions
    .map((tx) => {
      const timeMs = new Date(tx.executedAt).getTime();
      const fx = (tx.currency === baseCurrency ? 1 : rates[tx.currency]) || 1;
      const txCost = tx.baseAmount ?? (tx.quantity * tx.price + (tx.commission || 0)) * fx;
      const inst = instruments[tx.instrumentId];
      const isBond =
        tx.bondRate != null ||
        (tx.seriesName && tx.seriesName.length >= 3) ||
        (inst && (inst.type === 'BOND' || inst.symbol.startsWith('PL_BOND'))) ||
        (tx.currency === 'PLN' && tx.price === 100);
      const cat = resolveCategory(tx, instruments);
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
        symbol,
        cat,
        fallbackPrice,
        transactionType: tx.transactionType || 'BUY',
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
        monthName: MONTH_NAMES[m],
        shortName: MONTH_SHORT[m],
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
        monthName: MONTH_NAMES[m],
        shortName: MONTH_SHORT[m],
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

    const monthEndStr = new Date(tMs).toISOString().slice(0, 10);
    const categoryMap: Record<
      string,
      {
        name: string;
        color: string;
        invested: number;
        currentValue: number;
      }
    > = {};

    for (let k = 0; k < preparedTxs.length; k++) {
      const tx = preparedTxs[k];
      if (tx.timeMs > tMs) break;

      if (!categoryMap[tx.cat.name]) {
        categoryMap[tx.cat.name] = {
          name: tx.cat.name,
          color: tx.cat.color,
          invested: 0,
          currentValue: 0,
        };
      }

      const txVal = tx.isBond
        ? tx.quantity *
          calculateBondCurrentPrice({
            purchaseDate: tx.executedAt,
            bondRate: tx.bondRate,
            bondMargin: tx.bondMargin,
            cpi: monthInflation ?? cpi ?? 3.6,
            targetDate: tMs,
          }) *
          tx.fx
        : tx.quantity *
          getHistoricalPrice(
            historicalPrices[tx.symbol],
            historicalSortedDates[tx.symbol],
            monthEndStr,
            tx.fallbackPrice
          ) *
          tx.fx;
      const sign = tx.transactionType === 'SELL' ? -1 : 1;
      categoryMap[tx.cat.name].invested += sign * tx.txCost;
      categoryMap[tx.cat.name].currentValue += sign * txVal;
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
    const totalTax = isIkeAccount
      ? 0
      : profitGrossTotal > 0
      ? profitGrossTotal * 0.19
      : 0;
    const profitNetTotal = profitGrossTotal - totalTax;
    const returnGrossTotalPct =
      totalInvested > 0 ? (profitGrossTotal / totalInvested) * 100 : 0;
    const returnNetTotalPct =
      totalInvested > 0 ? (profitNetTotal / totalInvested) * 100 : 0;
    const realProfitPct = hasMonthCpi ? returnNetTotalPct - monthInflation : 0;

    records.push({
      monthIndex: m,
      monthName: MONTH_NAMES[m],
      shortName: MONTH_SHORT[m],
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
    });
  }

  // Calculate Month-over-Month (MoM) deltas
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
  };
}
