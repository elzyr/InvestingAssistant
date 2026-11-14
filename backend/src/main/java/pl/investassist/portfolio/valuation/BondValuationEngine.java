package pl.investassist.portfolio.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Optional;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.entity.Transaction;

public final class BondValuationEngine {

  public enum RateType {
    FIXED,
    CPI,
    NBP_REF
  }

  public enum BondFamily {
    OTS(3, false, 3, RateType.FIXED),
    ROR(12, false, 1, RateType.NBP_REF),
    DOR(24, false, 1, RateType.NBP_REF),
    TOS(36, true, 12, RateType.FIXED),
    COI(48, false, 12, RateType.CPI),
    ROS(72, true, 12, RateType.CPI),
    EDO(120, true, 12, RateType.CPI),
    ROD(144, true, 12, RateType.CPI);

    private final int termMonths;
    private final boolean compounding;
    private final int periodMonths;
    private final RateType rateType;

    BondFamily(int termMonths, boolean compounding, int periodMonths, RateType rateType) {
      this.termMonths = termMonths;
      this.compounding = compounding;
      this.periodMonths = periodMonths;
      this.rateType = rateType;
    }

    public int termMonths() {
      return termMonths;
    }

    public boolean compounding() {
      return compounding;
    }

    public int periodMonths() {
      return periodMonths;
    }

    public RateType rateType() {
      return rateType;
    }

    public int totalPeriods() {
      return termMonths / periodMonths;
    }

    public boolean isInflationIndexed() {
      return rateType == RateType.CPI;
    }
  }

  public record CompletedCoupon(LocalDate couponDate, double grossCouponNative) {}

  public record LotValuation(
      double unitPriceNative,
      double currentAnnualRatePct,
      boolean rateUnavailable,
      List<CompletedCoupon> completedCoupons) {}

  private BondValuationEngine() {}

  public static String normalizeSeries(String seriesName) {
    return (seriesName != null && !seriesName.trim().isBlank())
        ? seriesName.trim().toUpperCase(Locale.ROOT)
        : null;
  }

  public static boolean isBond(InstrumentType type, String seriesName, boolean hasBondMetadata) {
    if (type == InstrumentType.ETF || type == InstrumentType.STOCK) {
      return false;
    }
    return hasBondMetadata || type == InstrumentType.BOND || extractBondPrefix(seriesName) != null;
  }

  public static boolean isBondPosition(Instrument inst, String seriesName, boolean hasBondMetadata) {
    if (inst != null && (inst.getType() == InstrumentType.ETF || inst.getType() == InstrumentType.STOCK)) {
      return false;
    }
    if (isBond(inst != null ? inst.getType() : null, seriesName, hasBondMetadata)) {
      return true;
    }
    if (inst == null) {
      return false;
    }
    String sym = inst.getSymbol() != null ? inst.getSymbol().trim().toUpperCase(Locale.ROOT) : "";
    return sym.equals("PL_BOND") || extractBondPrefix(sym) != null;
  }

  public static boolean hasBondSeriesPrefix(String rawValue) {
    return extractBondPrefix(rawValue) != null;
  }

  public static boolean isBond(Instrument inst, String seriesName) {
    return isBondPosition(inst, seriesName, false);
  }

  public static boolean isBondTransaction(Transaction tx, Instrument inst) {
    return isBondPosition(
        inst,
        tx != null ? tx.getSeriesName() : null,
        hasBondMetadata(tx));
  }

  public static Optional<BondFamily> findBondFamily(String seriesOrSymbol) {
    String prefix = extractBondPrefix(seriesOrSymbol);
    if (prefix == null) {
      return Optional.empty();
    }
    return Optional.of(BondFamily.valueOf(prefix));
  }

  public static String resolveBondFamily(String seriesOrSymbol, Integer termMonths) {
    String prefix = extractBondPrefix(seriesOrSymbol);
    if (prefix != null) {
      return prefix;
    }
    if (seriesOrSymbol != null && !seriesOrSymbol.isBlank()
        && !"PL_BOND".equalsIgnoreCase(seriesOrSymbol.trim())) {
      return null;
    }
    if (termMonths != null) {
      for (BondFamily family : BondFamily.values()) {
        if (family.termMonths() == termMonths) {
          return family.name();
        }
      }
    }
    return null;
  }

  private static String extractBondPrefix(String rawValue) {
    if (rawValue == null || rawValue.isBlank()) {
      return null;
    }
    String upper = rawValue.trim().toUpperCase(Locale.ROOT);
    if (upper.startsWith("PL_BOND_")) {
      upper = upper.substring("PL_BOND_".length());
    }
    for (BondFamily family : BondFamily.values()) {
      if (upper.startsWith(family.name())) {
        return family.name();
      }
    }
    return null;
  }

  public static LotValuation evaluateLot(
      LocalDateTime purchaseDate,
      BigDecimal bondRate,
      BigDecimal bondMargin,
      Map<Integer, Map<Integer, BigDecimal>> monthlyCpiByYear,
      NavigableMap<LocalDate, BigDecimal> nbpReferenceRates,
      LocalDateTime targetDate,
      String seriesName,
      Integer termMonths) {
    if (purchaseDate == null) {
      return new LotValuation(100.0, 0.0, false, List.of());
    }
    String familyCode = resolveBondFamily(seriesName, termMonths);
    if (familyCode == null) {
      return new LotValuation(100.0, 0.0, true, List.of());
    }
    BondFamily family = BondFamily.valueOf(familyCode);
    LocalDate startDay = purchaseDate.toLocalDate();
    LocalDate endDay = (targetDate != null ? targetDate : LocalDateTime.now()).toLocalDate();
    if (!endDay.isAfter(startDay)) {
      double r1 = bondRate != null ? Math.max(0.0, bondRate.doubleValue()) : 0.0;
      return new LotValuation(100.0, r1, bondRate == null, List.of());
    }

    double principal = 100.0;
    double carryingValue = 100.0;
    double currentRatePct = bondRate != null ? Math.max(0.0, bondRate.doubleValue()) : 0.0;
    boolean rateUnavailable = false;
    List<CompletedCoupon> completedCoupons = new ArrayList<>();
    int totalPeriods = family.totalPeriods();

    for (int period = 1; period <= totalPeriods; period++) {
      LocalDate periodStart = startDay.plusMonths((long) (period - 1) * family.periodMonths());
      LocalDate periodEnd = startDay.plusMonths((long) period * family.periodMonths());
      if (!endDay.isAfter(periodStart)) {
        carryingValue = principal;
        break;
      }

      double periodRatePct;
      if (period == 1 || family.rateType() == RateType.FIXED) {
        if (bondRate == null) {
          rateUnavailable = true;
          periodRatePct = 0.0;
        } else {
          periodRatePct = Math.max(0.0, bondRate.doubleValue());
        }
      } else if (family.rateType() == RateType.CPI) {
        LocalDate lagDate = periodStart.minusMonths(1);
        BigDecimal cpi = lookupMonthlyCpi(monthlyCpiByYear, lagDate.getYear(), lagDate.getMonthValue());
        if (cpi == null) {
          rateUnavailable = true;
          periodRatePct = 0.0;
        } else {
          double margin = bondMargin != null ? Math.max(0.0, bondMargin.doubleValue()) : 0.0;
          periodRatePct = Math.max(0.0, cpi.doubleValue()) + margin;
        }
      } else {
        Map.Entry<LocalDate, BigDecimal> refEntry =
            nbpReferenceRates != null ? nbpReferenceRates.floorEntry(periodStart) : null;
        if (refEntry == null || refEntry.getValue() == null) {
          rateUnavailable = true;
          periodRatePct = 0.0;
        } else {
          double margin = bondMargin != null ? bondMargin.doubleValue() : 0.0;
          periodRatePct = Math.max(0.0, refEntry.getValue().doubleValue() + margin);
        }
      }
      currentRatePct = periodRatePct;

      long periodDays = Math.max(1L, ChronoUnit.DAYS.between(periodStart, periodEnd));
      double fullPeriodInterest;
      if (family == BondFamily.OTS) {
        fullPeriodInterest = principal * (periodRatePct / 100.0) * (3.0 / 12.0);
      } else if (family.periodMonths() == 1) {
        fullPeriodInterest = principal * (periodRatePct / 100.0) * (periodDays / 365.0);
      } else {
        fullPeriodInterest = principal * (periodRatePct / 100.0);
      }

      if (!endDay.isBefore(periodEnd)) {
        if (family.compounding() || family == BondFamily.OTS) {
          principal += fullPeriodInterest;
        } else {
          completedCoupons.add(new CompletedCoupon(periodEnd, fullPeriodInterest));
        }
        carryingValue = principal;
      } else {
        long elapsedDays = Math.max(0L, ChronoUnit.DAYS.between(periodStart, endDay));
        double accrued = fullPeriodInterest * (elapsedDays / (double) periodDays);
        carryingValue = principal + accrued;
        break;
      }
    }

    return new LotValuation(carryingValue, currentRatePct, rateUnavailable, completedCoupons);
  }

  private static BigDecimal lookupMonthlyCpi(
      Map<Integer, Map<Integer, BigDecimal>> monthlyCpiByYear, int year, int month) {
    if (monthlyCpiByYear == null) {
      return null;
    }
    Map<Integer, BigDecimal> byMonth = monthlyCpiByYear.get(year);
    return byMonth != null ? byMonth.get(month) : null;
  }

  public static double calculateUnitPrice(
      LocalDateTime purchaseDate,
      BigDecimal bondRate,
      BigDecimal bondMargin,
      BigDecimal cpi,
      LocalDateTime targetDate,
      String seriesName,
      Integer termMonths) {
    Map<Integer, Map<Integer, BigDecimal>> singleCpiMap = Map.of();
    if (cpi != null && purchaseDate != null) {
      singleCpiMap = new java.util.HashMap<>();
      LocalDate start = purchaseDate.toLocalDate();
      LocalDate end = (targetDate != null ? targetDate : LocalDateTime.now()).toLocalDate();
      for (LocalDate d = start; !d.isAfter(end.plusYears(1)); d = d.plusMonths(1)) {
        singleCpiMap.computeIfAbsent(d.getYear(), ignored -> new java.util.HashMap<>()).put(d.getMonthValue(), cpi);
      }
    }
    return evaluateLot(
            purchaseDate,
            bondRate,
            bondMargin,
            singleCpiMap,
            null,
            targetDate,
            seriesName,
            termMonths)
        .unitPriceNative();
  }

  public static boolean hasBondMetadata(Transaction tx) {
    return tx != null
        && (tx.getBondRate() != null
            || tx.getBondMargin() != null
            || tx.getTermMonths() != null
            || hasBondSeriesPrefix(tx.getSeriesName()));
  }
}
