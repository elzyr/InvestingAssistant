import type { TFunction } from 'i18next';
import type {
  ChartPoint,
  PortfolioSummary,
  Position,
  ProfitContributor,
  SubPosition,
} from '../types/dashboard.ts';
import type {
  AnnualSummaryData,
  MonthlyCategoryBreakdown,
  MonthlyReportRecord,
} from '../types/reports.types.ts';
import type {
  AnnualReportResponse,
  PortfolioTimelineResponse,
  PortfolioValuationResponse,
  ValuationPositionDto,
} from '../types/valuation.types.ts';
import { formatMoney, formatSignedFixedPercent, normalizeZero } from './reportsFormatters.ts';
import { BOND_SPEC_BY_FAMILY, isInflationBondFamily } from './domain/assets.ts';
import { formatUtcDate, parseUtcNoonMs } from './domain/dates.ts';

const CATEGORY_COLORS: Record<string, string> = {
  TREASURY_BONDS: '#3b82f6',
  SP500: '#10b981',
  ETF_ALL_WORLD: '#8b5cf6',
  ETF_WORLD: '#06b6d4',
  EMERGING_MARKETS: '#f59e0b',
  PL_STOCKS: '#ec4899',
  US_STOCKS: '#6366f1',
  OTHER: '#14b8a6',
};

const CATEGORY_LABELS: Record<string, { key?: string; literal?: string }> = {
  TREASURY_BONDS: { key: 'reports.catTreasuryBonds' },
  SP500: { literal: 'S&P 500' },
  ETF_ALL_WORLD: { literal: 'ETF All-World' },
  ETF_WORLD: { literal: 'ETF World' },
  EMERGING_MARKETS: { key: 'reports.catEmerging' },
  PL_STOCKS: { key: 'reports.catPlStocks' },
  US_STOCKS: { key: 'reports.catUsStocks' },
};

function resolveBondFamilyName(family: string, t: TFunction): string {
  const spec = BOND_SPEC_BY_FAMILY[family.toUpperCase()];
  return spec ? t(spec.nameKey) : t('dashboard.bondsFamily', { family });
}

function resolveBondDescription(pos: ValuationPositionDto, t: TFunction): string {
  const family = (pos.bondFamily || pos.symbol).toUpperCase();
  const spec = BOND_SPEC_BY_FAMILY[family];
  if (isInflationBondFamily(family) && spec) {
    return t(spec.descriptionKey);
  }
  if (pos.bondRateMin != null && pos.bondRateMax != null) {
    if (pos.bondRateMin === pos.bondRateMax) {
      return t('dashboard.fixedRateLabel', { rate: pos.bondRateMin.toFixed(2) });
    }
    return t('dashboard.fixedRateRange', {
      min: pos.bondRateMin.toFixed(2),
      max: pos.bondRateMax.toFixed(2),
    });
  }
  return t(spec ? spec.descriptionKey : 'dashboard.inflationIndexed');
}

function formatCategoryName(categoryCode: string, t: TFunction): string {
  const label = CATEGORY_LABELS[categoryCode];
  if (label?.literal) return label.literal;
  return t(label?.key ?? 'reports.catOther');
}

function toDecimalFraction(pct: number | null | undefined): number | null {
  return pct != null ? pct / 100 : null;
}

function mapFxDecomposition(
  fx: { marketReturnPct: number; fxReturnPct: number } | null | undefined
): { marketReturnPct: number; fxReturnPct: number } | null {
  return fx ? { marketReturnPct: fx.marketReturnPct, fxReturnPct: fx.fxReturnPct } : null;
}

function formatUnitPrice(price: number, currency: string, locale: string): string {
  return `${price.toLocaleString(locale, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${currency}`;
}

export function formatValuationSummary(
  resp: PortfolioValuationResponse | null | undefined,
  t: TFunction
): PortfolioSummary {
  const currency = resp?.baseCurrency || 'PLN';
  const taxMode = resp?.taxMode || 'NETTO';
  const shouldApplyTax = taxMode === 'NETTO';

  if (!resp) {
    return {
      portfolioValueFormatted: formatMoney(0, currency),
      dailyChangeAmountFormatted: formatMoney(0, currency, true),
      dailyChangePercentFormatted: `+0.00% ${t('dashboard.today')}`,
      totalCostFormatted: formatMoney(0, currency),
      totalProfitAmountFormatted: formatMoney(0, currency, true),
      totalProfitPercentFormatted: `+0.0% ${t('dashboard.afterTax')}`,
      realProfitAmountFormatted: '—',
      realProfitPercentFormatted: '—',
      positionCount: 0,
      taxMode,
      profitLabel: t('dashboard.netProfit'),
      portfolioValueLabel: t('dashboard.netPortfolioValue'),
    };
  }

  const s = resp.summary;
  const annualizedUnit = t('dashboard.annualizedReturn');
  const aboveCpiUnit = t('dashboard.aboveCpi');
  const taxNoteSuffix = shouldApplyTax ? t('dashboard.afterTax') : t('dashboard.return');

  const totalProfitPctStr = formatSignedFixedPercent(s.netProfitPct, 1);
  const totalProfitPercentFormatted =
    s.xirrPct != null
      ? `${totalProfitPctStr} · ${formatSignedFixedPercent(s.xirrPct, 1, false)} ${annualizedUnit}`
      : `${totalProfitPctStr} ${taxNoteSuffix}`;

  let realProfitAmountFormatted = '—';
  let realProfitPercentFormatted = '—';
  if (s.realProfitAmount != null && s.realReturnPct != null) {
    const realPctStr = formatSignedFixedPercent(s.realReturnPct, 1);
    realProfitAmountFormatted = formatMoney(s.realProfitAmount, currency, true);
    realProfitPercentFormatted =
      s.realXirrPct != null
        ? `${realPctStr} ${aboveCpiUnit} · ${formatSignedFixedPercent(s.realXirrPct, 1, false)} ${annualizedUnit}`
        : `${realPctStr} ${aboveCpiUnit}`;
  }

  const taxNoteFormatted = shouldApplyTax
    ? t('dashboard.belkaTaxNote', { amount: formatMoney(s.taxPaid, currency) })
    : undefined;

  return {
    portfolioValueFormatted: formatMoney(s.totalValue, currency),
    dailyChangeAmountFormatted: formatMoney(s.dailyChange, currency, true),
    dailyChangePercentFormatted: `${formatSignedFixedPercent(s.dailyChangePct, 2)} ${t('dashboard.today')}`,
    totalCostFormatted: formatMoney(s.totalInvested, currency),
    totalProfitAmountFormatted: formatMoney(s.netProfit, currency, true),
    totalProfitPercentFormatted,
    realProfitAmountFormatted,
    realProfitPercentFormatted,
    positionCount: s.positionsCount,
    taxMode,
    profitLabel: shouldApplyTax ? t('dashboard.netProfit') : t('dashboard.grossProfit'),
    portfolioValueLabel: shouldApplyTax
      ? t('dashboard.netPortfolioValue')
      : t('dashboard.grossPortfolioValue'),
    taxNoteFormatted,
  };
}

export function formatValuationPositions(
  resp: PortfolioValuationResponse | null | undefined,
  t: TFunction,
  locale: string
): Position[] {
  if (!resp) return [];
  const baseCurrency = resp.baseCurrency || 'PLN';
  const units = t('common.units');
  const atPrice = t('common.atPrice');

  return resp.positions.map((pos) => {
    const isBond = pos.category === 'BOND';
    const subPositions: SubPosition[] = pos.subPositions.map((sub) => {
      if (isBond) {
        return {
          id: sub.id,
          name: `${t('dashboard.vintage')} ${sub.label}`,
          subName: `${sub.quantity.toLocaleString(locale)} ${units} (${t('common.costBasis')} ${sub.investedBase.toLocaleString(locale, { maximumFractionDigits: 0 })} ${baseCurrency})`,
          sharePercent: sub.sharePercent,
          shareNote: t('dashboard.ofPortfolio'),
          priceFormatted: formatUnitPrice(sub.currentPriceNative, baseCurrency, locale),
          valueFormatted: formatMoney(sub.valueBase, baseCurrency),
          profitFormatted: formatMoney(sub.profitBase, baseCurrency, true),
          profitPercentFormatted: formatSignedFixedPercent(sub.profitPct, 1),
          isProfit: sub.profitBase >= 0,
          transactionId: sub.transactionId || undefined,
          xirr: toDecimalFraction(sub.xirrPct),
          fxDecomposition: null,
        };
      }

      const dateLabel = sub.date ? formatUtcDate(sub.date, locale) : sub.label;
      const unitQuotePrice = sub.currentPriceNative ?? sub.txPrice;
      const unitQuoteCurrency = pos.quoteCurrency || sub.txCurrency;
      return {
        id: sub.id,
        name: dateLabel,
        subName: `${sub.quantity} ${units} ${atPrice} ${sub.txPrice.toFixed(2)} ${sub.txCurrency}`,
        sharePercent: 0,
        priceFormatted: formatUnitPrice(unitQuotePrice, unitQuoteCurrency, locale),
        valueFormatted: formatMoney(sub.valueBase, baseCurrency),
        profitFormatted: formatMoney(sub.profitBase, baseCurrency, true),
        profitPercentFormatted: formatSignedFixedPercent(sub.profitPct, 1),
        isProfit: sub.profitBase >= 0,
        transactionId: sub.transactionId || undefined,
        xirr: toDecimalFraction(sub.xirrPct),
        fxDecomposition: mapFxDecomposition(sub.fxDecomposition),
      };
    });

    if (isBond) {
      return {
        id: pos.id,
        ticker: resolveBondFamilyName(pos.bondFamily || pos.symbol, t),
        name: resolveBondDescription(pos, t),
        type: 'BOND',
        quantityFormatted: `${pos.quantity.toLocaleString(locale)} ${units}`,
        sharePercent: pos.allocationPct,
        valueBase: pos.valueBase,
        profitBase: pos.profitBase,
        profitPct: pos.profitPct,
        priceFormatted: formatUnitPrice(pos.currentPriceNative, baseCurrency, locale),
        valueFormatted: formatMoney(pos.valueBase, baseCurrency),
        profitFormatted: formatMoney(pos.profitBase, baseCurrency, true),
        profitPercentFormatted: `${formatSignedFixedPercent(pos.profitPct, 1)} (${t('dashboard.interestAccrued')})`,
        isProfit: pos.profitBase >= 0,
        subPositionsType: 'bonds_yearly',
        subPositions,
        xirr: toDecimalFraction(pos.xirrPct),
        fxDecomposition: null,
        isCpiUnavailable: pos.isCpiUnavailable,
      };
    }

    return {
      id: pos.id,
      ticker: pos.symbol,
      name: pos.name,
      type: pos.category,
      quantityFormatted: `${pos.quantity.toLocaleString(locale)} ${units}`,
      sharePercent: pos.allocationPct,
      valueBase: pos.valueBase,
      profitBase: pos.profitBase,
      profitPct: pos.profitPct,
      priceFormatted: formatUnitPrice(pos.currentPriceNative, pos.quoteCurrency, locale),
      valueFormatted: formatMoney(pos.valueBase, baseCurrency),
      profitFormatted: formatMoney(pos.profitBase, baseCurrency, true),
      profitPercentFormatted: formatSignedFixedPercent(pos.profitPct, 1),
      isProfit: pos.profitBase >= 0,
      subPositionsType: 'purchases',
      subPositions,
      xirr: toDecimalFraction(pos.xirrPct),
      fxDecomposition: mapFxDecomposition(pos.fxDecomposition),
      isPriceUnavailable: pos.isPriceUnavailable,
      isCpiUnavailable: pos.isCpiUnavailable,
    };
  });
}

export function formatTimelineChartPoints(
  timeline: PortfolioTimelineResponse | null | undefined,
  t: TFunction,
  locale: string
): ChartPoint[] {
  if (!timeline?.points?.length) return [];

  return timeline.points.map((pt) => {
    const utcMidnightMs = parseUtcNoonMs(pt.date);
    const dateLabel = new Date(utcMidnightMs).toLocaleDateString(locale, {
      day: 'numeric',
      month: 'short',
      year: 'numeric',
      timeZone: 'UTC',
    });

    const contributors: ProfitContributor[] = (pt.contributors || []).map((c) => ({
      key: c.id,
      label: c.isOther ? t('chart.otherContributors') : c.symbol,
      assetType: c.category,
      valuation: normalizeZero(Math.round(c.valuationBase)),
      cost: normalizeZero(Math.round(c.costBase)),
      profit: normalizeZero(Math.round(c.gainBase)),
      returnContributionPct: c.returnContributionPct,
      sharePct: c.sharePct,
    }));

    return {
      timestamp: utcMidnightMs,
      dateLabel,
      valuation: normalizeZero(Math.round(pt.value)),
      cost: normalizeZero(Math.round(pt.invested)),
      profit: normalizeZero(Math.round(pt.netProfit)),
      returnPct: pt.returnPct,
      isFuture: false,
      netP90: normalizeZero(Math.round(pt.value)),
      netP10: normalizeZero(Math.round(pt.value)),
      contributors,
    };
  });
}

export function formatAnnualReportSummary(
  report: AnnualReportResponse | null | undefined,
  t: TFunction,
  locale: string
): AnnualSummaryData {
  const year = report?.year ?? new Date().getFullYear();
  const baseCurrency = report?.baseCurrency || 'PLN';

  if (!report) {
    return {
      year,
      records: [],
      ytdPortfolioValue: 0,
      ytdInvested: 0,
      ytdProfitGross: 0,
      ytdReturnGrossPct: 0,
      ytdProfitNet: 0,
      ytdReturnNetPct: 0,
      ytdRealReturnPct: null,
      ytdTwr: 0,
      avgCpi: null,
      baseCurrency,
    };
  }

  const records: MonthlyReportRecord[] = report.months.map((m) => {
    const monthDate = new Date(Date.UTC(year, m.monthIndex, 1));
    const monthName = monthDate
      .toLocaleDateString(locale, { month: 'long', timeZone: 'UTC' })
      .replace(/^./, (c) => c.toUpperCase());
    const shortName = monthDate
      .toLocaleDateString(locale, { month: 'short', timeZone: 'UTC' })
      .replace(/^./, (c) => c.toUpperCase());

    const breakdown: MonthlyCategoryBreakdown[] = (m.breakdown || []).map((b) => ({
      category: formatCategoryName(b.categoryCode, t),
      color: CATEGORY_COLORS[b.categoryCode] || CATEGORY_COLORS.OTHER,
      invested: b.invested,
      currentValue: b.currentValue,
      profitGross: b.profitGross,
      profitNet: b.profitNet,
      returnGrossPct: b.returnGrossPct,
      returnNetPct: b.returnNetPct,
      sharePct: b.sharePct,
    }));

    const positions = (m.positions ?? []).map((p) => ({
      key: p.key,
      symbol: p.symbol,
      name: p.name,
      categoryCode: p.categoryCode,
      color: CATEGORY_COLORS[p.categoryCode] || CATEGORY_COLORS.OTHER,
      invested: p.invested,
      currentValue: p.currentValue,
      profitGross: p.profitGross,
      profitNet: p.profitNet,
      profitNetMom: p.profitNetMom,
      returnGrossPct: p.returnGrossPct,
      returnNetPct: p.returnNetPct,
      sharePct: p.sharePct,
    }));

    return {
      monthIndex: m.monthIndex,
      monthName,
      shortName,
      year,
      portfolioValue: m.portfolioValue,
      investedTotal: m.investedTotal,
      profitGross: m.profitGross,
      returnGrossPct: m.returnGrossPct,
      profitNet: m.profitNet,
      returnNetPct: m.returnNetPct,
      profitNetMom: m.profitNetMom,
      returnNetMomPct: m.returnNetMomPct,
      cpiInflation: m.cpiInflation,
      realProfitPct: m.realProfitPct,
      status: m.status,
      breakdown,
      positions,
      baseCurrency,
    };
  });

  return {
    year,
    records,
    ytdPortfolioValue: report.ytdPortfolioValue,
    ytdInvested: report.ytdInvested,
    ytdProfitGross: report.ytdProfitGross,
    ytdReturnGrossPct: report.ytdReturnGrossPct,
    ytdProfitNet: report.ytdProfitNet,
    ytdReturnNetPct: report.ytdReturnNetPct,
    ytdRealReturnPct: report.ytdRealReturnPct,
    ytdTwr: report.ytdTwrPct,
    avgCpi: report.avgCpi,
    baseCurrency,
  };
}
