export interface CashFlow {
  amount: number;
  date: string | Date | number;
}

export interface HoldingXirrOptions {
  cashFlows: CashFlow[];
  currentValue: number;
  terminalDate?: string | Date | number;
  now?: string | Date | number;
  minDays?: number;
}

export interface HoldingXirrResult {
  xirr: number | null;
  isSimpleFallback: boolean;
  daysElapsed: number;
}

export interface RealMetricsTransaction {
  cost: number;
  executedAt: string | Date | number;
}

export interface PortfolioRealMetricsOptions {
  transactions: RealMetricsTransaction[];
  portfolioValue: number;
  cpi: number;
  nominalXirr: number | null;
  currentDate?: string | Date | number;
}

export interface PortfolioRealMetricsResult {
  inflationHurdle: number;
  realProfitAmount: number;
  realProfitPercent: number;
  realXirr: number | null;
}

export interface FxDecompositionOptions {
  buyPrice: number;
  currentPrice: number;
  buyExchangeRate?: number;
  currentExchangeRate?: number;
  buyFx?: number;
  currentFx?: number;
}

export interface FxDecompositionResult {
  marketReturnPct: number;
  fxReturnPct: number;
  totalReturnPct: number;
}

const MS_PER_DAY = 86400000;

function parseDateMs(date: string | Date | number): number {
  if (typeof date === 'number') {
    return Number.isFinite(date) ? date : NaN;
  }
  if (date instanceof Date) {
    return date.getTime();
  }
  if (typeof date === 'string') {
    return Date.parse(date);
  }
  return NaN;
}

export function calculateXirr(cashFlows: CashFlow[], guess = 0.1): number | null {
  if (!Array.isArray(cashFlows) || cashFlows.length < 2) {
    return null;
  }

  let hasPositive = false;
  let hasNegative = false;
  const parsedFlows: { amount: number; time: number }[] = [];

  for (const cf of cashFlows) {
    if (typeof cf.amount !== 'number' || !Number.isFinite(cf.amount)) {
      return null;
    }
    const t = parseDateMs(cf.date);
    if (Number.isNaN(t)) {
      return null;
    }
    if (cf.amount > 0) hasPositive = true;
    if (cf.amount < 0) hasNegative = true;
    parsedFlows.push({ amount: cf.amount, time: t });
  }

  if (!hasPositive || !hasNegative) {
    return null;
  }

  parsedFlows.sort((a, b) => a.time - b.time);
  const minTime = parsedFlows[0].time;
  const maxTime = parsedFlows[parsedFlows.length - 1].time;

  if (maxTime === minTime) {
    return null;
  }

  const flows = parsedFlows.map((f) => ({
    amount: f.amount,
    years: (f.time - minTime) / (365 * MS_PER_DAY),
  }));

  const totalAmount = flows.reduce((sum, f) => sum + f.amount, 0);
  if (Math.abs(totalAmount) < 1e-9) {
    return 0.0;
  }

  const calcNpvAndDeriv = (r: number): [number, number] => {
    let npv = 0;
    let deriv = 0;
    const base = 1 + r;
    if (base <= 0) {
      return [Number.NaN, Number.NaN];
    }
    for (const f of flows) {
      if (f.years === 0) {
        npv += f.amount;
      } else {
        const factor = Math.pow(base, f.years);
        npv += f.amount / factor;
        deriv -= (f.years * f.amount) / (factor * base);
      }
    }
    return [npv, deriv];
  };

  const calcNpv = (r: number): number => {
    let npv = 0;
    const base = 1 + r;
    if (base <= 0) return Number.NaN;
    for (const f of flows) {
      if (f.years === 0) {
        npv += f.amount;
      } else {
        npv += f.amount / Math.pow(base, f.years);
      }
    }
    return npv;
  };

  let r = Number.isFinite(guess) && guess > -0.999 ? guess : 0.1;
  const maxNewtonIters = 60;
  let converged = false;

  for (let i = 0; i < maxNewtonIters; i++) {
    const [npv, deriv] = calcNpvAndDeriv(r);
    if (!Number.isFinite(npv) || !Number.isFinite(deriv)) {
      break;
    }
    if (Math.abs(npv) < 1e-8) {
      converged = true;
      break;
    }
    if (Math.abs(deriv) < 1e-12) {
      break;
    }
    const nextR = r - npv / deriv;
    if (nextR <= -0.9999 || nextR > 10000 || !Number.isFinite(nextR)) {
      break;
    }
    if (Math.abs(nextR - r) < 1e-8) {
      r = nextR;
      converged = true;
      break;
    }
    r = nextR;
  }

  if (converged && Number.isFinite(r)) {
    return r;
  }

  let lower = -0.9999;
  let upper = 10.0;
  let npvLower = calcNpv(lower);
  let npvUpper = calcNpv(upper);

  if (Number.isFinite(npvLower) && Number.isFinite(npvUpper) && npvLower * npvUpper > 0) {
    const candidates = [0.0, 0.5, 1.0, 5.0, 50.0, 500.0, 5000.0];
    for (const cand of candidates) {
      const npvCand = calcNpv(cand);
      if (Number.isFinite(npvCand) && npvLower * npvCand <= 0) {
        upper = cand;
        npvUpper = npvCand;
        break;
      }
    }
  }

  if (!Number.isFinite(npvLower) || !Number.isFinite(npvUpper) || npvLower * npvUpper > 0) {
    return null;
  }

  for (let i = 0; i < 100; i++) {
    const mid = (lower + upper) / 2;
    const npvMid = calcNpv(mid);
    if (Math.abs(npvMid) < 1e-8 || (upper - lower) / 2 < 1e-8) {
      return mid;
    }
    if (npvLower * npvMid <= 0) {
      upper = mid;
    } else {
      lower = mid;
      npvLower = npvMid;
    }
  }

  return (lower + upper) / 2;
}

export function calculateHoldingXirr(
  cashFlowsOrOptions: CashFlow[] | HoldingXirrOptions,
  currentValue?: number,
  terminalDate?: string | Date | number,
  minDays = 30
): HoldingXirrResult {
  let flows: CashFlow[];
  let val: number;
  let termDate: string | Date | number;
  let thresholdDays = minDays;

  if (Array.isArray(cashFlowsOrOptions)) {
    flows = cashFlowsOrOptions;
    val = currentValue ?? 0;
    termDate = terminalDate ?? new Date();
  } else {
    flows = cashFlowsOrOptions.cashFlows || [];
    val = cashFlowsOrOptions.currentValue ?? 0;
    termDate = cashFlowsOrOptions.terminalDate ?? cashFlowsOrOptions.now ?? new Date();
    thresholdDays = cashFlowsOrOptions.minDays ?? 30;
  }

  if (!flows.length) {
    return { xirr: null, isSimpleFallback: true, daysElapsed: 0 };
  }

  const termMs = parseDateMs(termDate);
  if (Number.isNaN(termMs)) {
    return { xirr: null, isSimpleFallback: true, daysElapsed: 0 };
  }

  let earliestMs = Infinity;
  for (const cf of flows) {
    const t = parseDateMs(cf.date);
    if (!Number.isNaN(t) && t < earliestMs) {
      earliestMs = t;
    }
  }

  if (!Number.isFinite(earliestMs)) {
    return { xirr: null, isSimpleFallback: true, daysElapsed: 0 };
  }

  const daysElapsed = Math.max(0, Math.round((termMs - earliestMs) / MS_PER_DAY));

  if (daysElapsed < thresholdDays) {
    return {
      xirr: null,
      isSimpleFallback: true,
      daysElapsed,
    };
  }

  const fullCashFlows: CashFlow[] = [
    ...flows,
    { amount: val, date: termDate },
  ];

  const xirr = calculateXirr(fullCashFlows);
  return {
    xirr,
    isSimpleFallback: false,
    daysElapsed,
  };
}

export function calculatePortfolioRealMetrics(
  options: PortfolioRealMetricsOptions
): PortfolioRealMetricsResult {
  const { transactions, portfolioValue, cpi, nominalXirr, currentDate } = options;

  if (!transactions || transactions.length === 0) {
    return {
      inflationHurdle: 0,
      realProfitAmount: 0,
      realProfitPercent: 0,
      realXirr: null,
    };
  }

  const normalizedCpi = cpi > 0 && cpi <= 0.2 ? cpi : cpi / 100;
  const currentMs = currentDate ? parseDateMs(currentDate) : Date.now();

  let totalHurdle = 0;

  for (const tx of transactions) {
    const txMs = parseDateMs(tx.executedAt);
    if (Number.isNaN(txMs)) continue;
    const deltaYears = Math.max(0, (currentMs - txMs) / (365 * MS_PER_DAY));
    const hurdle_i = tx.cost * Math.pow(1 + normalizedCpi, deltaYears);
    totalHurdle += hurdle_i;
  }

  const realProfitAmount = portfolioValue - totalHurdle;
  const realProfitPercent = totalHurdle > 0 ? (realProfitAmount / totalHurdle) * 100 : 0;

  let realXirr: number | null = null;
  if (nominalXirr !== null && nominalXirr !== undefined && Number.isFinite(nominalXirr)) {
    realXirr = (1 + nominalXirr) / (1 + normalizedCpi) - 1;
  }

  return {
    inflationHurdle: totalHurdle,
    realProfitAmount,
    realProfitPercent,
    realXirr,
  };
}

export function calculateFxDecomposition(
  buyPriceOrOptions: number | FxDecompositionOptions,
  currentPrice?: number,
  buyExchangeRate?: number,
  currentExchangeRate?: number
): FxDecompositionResult | null {
  let bPrice: number;
  let cPrice: number;
  let bFx: number;
  let cFx: number;

  if (typeof buyPriceOrOptions === 'object' && buyPriceOrOptions !== null) {
    bPrice = buyPriceOrOptions.buyPrice;
    cPrice = buyPriceOrOptions.currentPrice;
    bFx = buyPriceOrOptions.buyExchangeRate ?? buyPriceOrOptions.buyFx ?? 0;
    cFx = buyPriceOrOptions.currentExchangeRate ?? buyPriceOrOptions.currentFx ?? 0;
  } else {
    bPrice = buyPriceOrOptions;
    cPrice = currentPrice ?? 0;
    bFx = buyExchangeRate ?? 0;
    cFx = currentExchangeRate ?? 0;
  }

  if (
    !Number.isFinite(bPrice) ||
    !Number.isFinite(cPrice) ||
    !Number.isFinite(bFx) ||
    !Number.isFinite(cFx) ||
    bPrice <= 0 ||
    bFx <= 0 ||
    cPrice < 0 ||
    cFx < 0
  ) {
    return null;
  }

  const marketReturnPct = ((cPrice - bPrice) / bPrice) * 100;
  const fxReturnPct = ((cFx - bFx) / bFx) * 100;
  const totalReturnPct = ((1 + marketReturnPct / 100) * (1 + fxReturnPct / 100) - 1) * 100;

  return {
    marketReturnPct,
    fxReturnPct,
    totalReturnPct,
  };
}
