package pl.investassist.portfolio.valuation;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class MetricsCalculator {

  private static final long MS_PER_DAY = 86_400_000L;

  private MetricsCalculator() {}

  public record CashFlow(double amount, LocalDateTime date) {}

  public record XirrResult(Double xirr, long daysElapsed) {}

  public record RealMetrics(
      double realProfitAmount,
      double realProfitPercent,
      Double realXirr) {}

  public record FxDecomposition(double marketReturnPct, double fxReturnPct) {}

  public record CostLot(double cost, LocalDateTime executedAt) {}

  public record TwrPeriod(double startValue, double endValue, double netCashFlow) {}

  public static long toEpochMillisUtc(LocalDateTime dt) {
    return dt.toInstant(ZoneOffset.UTC).toEpochMilli();
  }

  public static XirrResult calculateHoldingXirr(
      List<CashFlow> flows, double terminalValue, LocalDateTime terminalDate, int minDays) {
    if (flows == null || flows.isEmpty() || terminalDate == null) {
      return new XirrResult(null, 0);
    }
    long termMs = toEpochMillisUtc(terminalDate);
    long earliestMs = Long.MAX_VALUE;
    for (CashFlow cf : flows) {
      if (cf != null && cf.date() != null) {
        long t = toEpochMillisUtc(cf.date());
        if (t < earliestMs) {
          earliestMs = t;
        }
      }
    }
    if (earliestMs == Long.MAX_VALUE) {
      return new XirrResult(null, 0);
    }
    long daysElapsed = Math.max(0L, Math.round((termMs - earliestMs) / (double) MS_PER_DAY));
    if (daysElapsed < minDays) {
      return new XirrResult(null, daysElapsed);
    }

    List<CashFlow> full = new ArrayList<>(flows.size() + 1);
    full.addAll(flows);
    full.add(new CashFlow(terminalValue, terminalDate));
    Double xirr = solveXirr(full, 0.1);
    return new XirrResult(xirr, daysElapsed);
  }

  private static <T> double calcNpvGeneric(
      List<T> flows,
      java.util.function.ToDoubleFunction<T> amountFn,
      java.util.function.ToDoubleFunction<T> yearsFn,
      double r) {
    double base = 1.0 + r;
    if (base <= 0.0) {
      return Double.NaN;
    }
    double npv = 0.0;
    for (T f : flows) {
      double years = yearsFn.applyAsDouble(f);
      double amt = amountFn.applyAsDouble(f);
      if (years == 0.0) {
        npv += amt;
      } else {
        npv += amt / Math.pow(base, years);
      }
    }
    return npv;
  }

  public static Double solveXirr(List<CashFlow> cashFlows, double guess) {
    if (cashFlows == null || cashFlows.size() < 2) {
      return null;
    }
    boolean hasPositive = false;
    boolean hasNegative = false;
    record TimedAmount(double amount, long timeMs) {}
    List<TimedAmount> parsed = new ArrayList<>(cashFlows.size());
    for (CashFlow cf : cashFlows) {
      if (cf == null || cf.date() == null || !Double.isFinite(cf.amount())) {
        return null;
      }
      if (cf.amount() > 0) hasPositive = true;
      if (cf.amount() < 0) hasNegative = true;
      parsed.add(new TimedAmount(cf.amount(), toEpochMillisUtc(cf.date())));
    }
    if (!hasPositive || !hasNegative) {
      return null;
    }

    parsed.sort(Comparator.comparingLong(TimedAmount::timeMs));
    long minTime = parsed.get(0).timeMs();
    long maxTime = parsed.get(parsed.size() - 1).timeMs();
    if (maxTime == minTime) {
      return null;
    }

    record YearAmount(double amount, double years) {}
    List<YearAmount> flows = new ArrayList<>(parsed.size());
    double totalAmount = 0.0;
    for (TimedAmount p : parsed) {
      double years = (p.timeMs() - minTime) / (365.0 * MS_PER_DAY);
      flows.add(new YearAmount(p.amount(), years));
      totalAmount += p.amount();
    }
    if (Math.abs(totalAmount) < 1e-9) {
      return 0.0;
    }

    double r = (Double.isFinite(guess) && guess > -0.999) ? guess : 0.1;
    boolean converged = false;
    for (int i = 0; i < 60; i++) {
      double base = 1.0 + r;
      if (base <= 0.0) {
        break;
      }
      double npv = 0.0;
      double deriv = 0.0;
      for (YearAmount f : flows) {
        if (f.years() == 0.0) {
          npv += f.amount();
        } else {
          double factor = Math.pow(base, f.years());
          npv += f.amount() / factor;
          deriv -= (f.years() * f.amount()) / (factor * base);
        }
      }
      if (!Double.isFinite(npv) || !Double.isFinite(deriv)) {
        break;
      }
      if (Math.abs(npv) < 1e-8) {
        converged = true;
        break;
      }
      if (Math.abs(deriv) < 1e-12) {
        break;
      }
      double nextR = r - npv / deriv;
      if (!Double.isFinite(nextR) || nextR <= -0.9999 || nextR > 10_000.0) {
        break;
      }
      if (Math.abs(nextR - r) < 1e-8) {
        r = nextR;
        converged = true;
        break;
      }
      r = nextR;
    }
    if (converged && Double.isFinite(r)) {
      return r;
    }

    double lower = -0.9999;
    double upper = 10.0;
    double npvLower = calcNpvGeneric(flows, YearAmount::amount, YearAmount::years, lower);
    double npvUpper = calcNpvGeneric(flows, YearAmount::amount, YearAmount::years, upper);

    if (!Double.isFinite(npvLower) || !Double.isFinite(npvUpper) || npvLower * npvUpper > 0.0) {
      double[] candidates = {-0.9, -0.7, -0.5, -0.3, -0.1, 0.0, 0.5, 1.0, 5.0, 50.0, 500.0};
      double prevC = candidates[0];
      double prevNpv = calcNpvGeneric(flows, YearAmount::amount, YearAmount::years, prevC);
      for (int i = 1; i < candidates.length; i++) {
        double cand = candidates[i];
        double npvCand = calcNpvGeneric(flows, YearAmount::amount, YearAmount::years, cand);
        if (Double.isFinite(prevNpv) && Double.isFinite(npvCand) && prevNpv * npvCand <= 0.0) {
          lower = prevC;
          upper = cand;
          npvLower = prevNpv;
          npvUpper = npvCand;
          break;
        }
        prevC = cand;
        prevNpv = npvCand;
      }
    }

    if (!Double.isFinite(npvLower) || !Double.isFinite(npvUpper) || npvLower * npvUpper > 0.0) {
      return null;
    }

    for (int i = 0; i < 100; i++) {
      double mid = (lower + upper) / 2.0;
      double npvMid = calcNpvGeneric(flows, YearAmount::amount, YearAmount::years, mid);
      if (Math.abs(npvMid) < 1e-8 || (upper - lower) / 2.0 < 1e-8) {
        return mid;
      }
      if (npvLower * npvMid <= 0.0) {
        upper = mid;
      } else {
        lower = mid;
        npvLower = npvMid;
      }
    }
    return (lower + upper) / 2.0;
  }

  public static double compoundHurdle(List<CostLot> lots, double cpiPercent, LocalDateTime atDate) {
    if (lots == null || lots.isEmpty() || atDate == null) {
      return 0.0;
    }
    double normalizedCpi = cpiPercent / 100.0;
    long atMs = toEpochMillisUtc(atDate);
    double totalHurdle = 0.0;
    for (CostLot lot : lots) {
      if (lot == null || lot.executedAt() == null || !Double.isFinite(lot.cost())) {
        continue;
      }
      long txMs = toEpochMillisUtc(lot.executedAt());
      double deltaYears = Math.max(0.0, (atMs - txMs) / (365.0 * MS_PER_DAY));
      totalHurdle += lot.cost() * Math.pow(1.0 + normalizedCpi, deltaYears);
    }
    return totalHurdle;
  }

  public static Double compoundCpiPathHurdle(
      List<CashFlow> portfolioFlows,
      java.util.Map<Integer, java.util.Map<Integer, BigDecimal>> monthlyCpiByYear,
      LocalDateTime atDate) {
    if (portfolioFlows == null
        || portfolioFlows.isEmpty()
        || atDate == null
        || monthlyCpiByYear == null
        || monthlyCpiByYear.isEmpty()) {
      return null;
    }
    java.time.LocalDate targetDate = atDate.toLocalDate();
    double totalHurdle = 0.0;
    for (CashFlow cf : portfolioFlows) {
      if (cf == null || cf.date() == null || !Double.isFinite(cf.amount())) {
        continue;
      }
      java.time.LocalDate flowDate = cf.date().toLocalDate();
      if (flowDate.isAfter(targetDate)) {
        continue;
      }
      Double factor = computeCpiFactor(flowDate, targetDate, monthlyCpiByYear);
      if (factor == null) {
        return null;
      }
      double externalNetContribution = -cf.amount();
      totalHurdle += externalNetContribution * factor;
    }
    return totalHurdle;
  }

  private static Double computeCpiFactor(
      java.time.LocalDate fromDate,
      java.time.LocalDate toDate,
      java.util.Map<Integer, java.util.Map<Integer, BigDecimal>> monthlyCpiByYear) {
    if (!toDate.isAfter(fromDate)) {
      return 1.0;
    }
    java.time.YearMonth startYm = java.time.YearMonth.from(fromDate);
    java.time.YearMonth endYm = java.time.YearMonth.from(toDate);
    double factor = 1.0;
    for (java.time.YearMonth ym = startYm; !ym.isAfter(endYm); ym = ym.plusMonths(1)) {
      java.util.Map<Integer, BigDecimal> byMonth = monthlyCpiByYear.get(ym.getYear());
      BigDecimal cpi = byMonth != null ? byMonth.get(ym.getMonthValue()) : null;
      if (cpi == null) {
        return null;
      }
      double monthLen = ym.lengthOfMonth();
      double fraction;
      if (startYm.equals(endYm)) {
        fraction = java.time.temporal.ChronoUnit.DAYS.between(fromDate, toDate) / monthLen;
      } else if (ym.equals(startYm)) {
        fraction = (monthLen - fromDate.getDayOfMonth() + 1.0) / monthLen;
      } else if (ym.equals(endYm)) {
        fraction = Math.max(0.0, toDate.getDayOfMonth() - 1.0) / monthLen;
      } else {
        fraction = 1.0;
      }
      if (fraction > 0.0) {
        double annualRate = Math.max(-0.99, cpi.doubleValue() / 100.0);
        factor *= Math.pow(1.0 + annualRate, fraction / 12.0);
      }
    }
    return factor;
  }

  public static RealMetrics calculatePortfolioRealMetrics(
      List<CostLot> lots,
      List<CashFlow> portfolioFlows,
      java.util.Map<Integer, java.util.Map<Integer, BigDecimal>> monthlyCpiByYear,
      double portfolioValue,
      Double cpiPercent,
      Double nominalXirr,
      LocalDateTime atDate) {
    Double hurdle = null;
    if (monthlyCpiByYear != null && !monthlyCpiByYear.isEmpty() && portfolioFlows != null && !portfolioFlows.isEmpty()) {
      hurdle = compoundCpiPathHurdle(portfolioFlows, monthlyCpiByYear, atDate);
    }
    if (hurdle == null && cpiPercent != null && lots != null && !lots.isEmpty()) {
      hurdle = compoundHurdle(lots, cpiPercent, atDate);
    }
    if (hurdle == null) {
      return null;
    }
    double realProfitAmount = portfolioValue - hurdle;
    double realProfitPercent = hurdle > 0.0 ? (realProfitAmount / hurdle) * 100.0 : 0.0;
    Double realXirr = null;
    if (nominalXirr != null && Double.isFinite(nominalXirr) && cpiPercent != null) {
      double normCpi = cpiPercent / 100.0;
      if (normCpi > -1.0) {
        realXirr = (1.0 + nominalXirr) / (1.0 + normCpi) - 1.0;
      }
    }
    return new RealMetrics(realProfitAmount, realProfitPercent, realXirr);
  }

  public static RealMetrics calculatePortfolioRealMetrics(
      List<CostLot> lots,
      double portfolioValue,
      Double cpiPercent,
      Double nominalXirr,
      LocalDateTime atDate) {
    return calculatePortfolioRealMetrics(lots, null, null, portfolioValue, cpiPercent, nominalXirr, atDate);
  }

  public static TwrPeriod stepTwrPeriod(
      BigDecimal prevVal,
      BigDecimal curVal,
      BigDecimal prevExtFlow,
      BigDecimal curExtFlow,
      BigDecimal prevSettledTax,
      BigDecimal curSettledTax) {
    BigDecimal netFlow = curExtFlow.subtract(prevExtFlow).add(curSettledTax.subtract(prevSettledTax));
    return new TwrPeriod(prevVal.doubleValue(), curVal.doubleValue(), netFlow.doubleValue());
  }

  public static double calculateTwr(List<TwrPeriod> subPeriods) {
    if (subPeriods == null || subPeriods.isEmpty()) {
      return 0.0;
    }
    double compoundProduct = 1.0;
    boolean hasValidPeriod = false;
    for (TwrPeriod p : subPeriods) {
      double start = p.startValue();
      double end = p.endValue();
      double flow = p.netCashFlow();
      double base = start > 0.0 ? start : (start + flow);
      if (base <= 0.0) {
        continue;
      }
      double subReturn = (end - (start > 0.0 ? flow : 0.0)) / base;
      if (Double.isFinite(subReturn)) {
        compoundProduct *= Math.max(0.0, subReturn);
        hasValidPeriod = true;
      }
    }
    if (!hasValidPeriod) {
      return 0.0;
    }
    double result = compoundProduct - 1.0;
    return Math.abs(result) < 1e-12 ? 0.0 : result;
  }

  public static FxDecomposition calculateFxDecomposition(
      double buyPrice, double currentPrice, double buyFx, double currentFx) {
    if (!Double.isFinite(buyPrice)
        || !Double.isFinite(currentPrice)
        || !Double.isFinite(buyFx)
        || !Double.isFinite(currentFx)
        || buyPrice <= 0.0
        || buyFx <= 0.0
        || currentPrice < 0.0
        || currentFx < 0.0) {
      return null;
    }
    double marketReturnPct = ((currentPrice - buyPrice) / buyPrice) * 100.0;
    double fxReturnPct = ((currentFx - buyFx) / buyFx) * 100.0;
    return new FxDecomposition(marketReturnPct, fxReturnPct);
  }
}
