package pl.investassist.portfolio.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class TaxPolicy {

  public static final BigDecimal BELKA_RATE_BD = new BigDecimal("0.19");
  private static final BigDecimal HALF = new BigDecimal("0.50");
  private static final BigDecimal HUNDRED = new BigDecimal("100");

  private TaxPolicy() {}

  public static boolean shouldApplyTax(TaxMode taxMode) {
    return taxMode == null || taxMode == TaxMode.NETTO;
  }

  public record CapitalDisposalEvent(LocalDateTime executedAt, BigDecimal profitBase) {}

  public record Art30aPayoutEvent(
      LocalDateTime executedAt, BigDecimal grossBase, BigDecimal withholdingTaxPct) {}

  public record PortfolioTaxResult(
      BigDecimal settledCapitalTax,
      BigDecimal settledArt30aTax,
      BigDecimal settledTax,
      BigDecimal hypotheticalOpenTax,
      BigDecimal totalTax,
      List<MetricsCalculator.CashFlow> settledTaxFlows) {}

  private static final class LossBucket {
    final int year;
    final BigDecimal originalLoss;
    BigDecimal remainingLoss;

    LossBucket(int year, BigDecimal originalLoss) {
      this.year = year;
      this.originalLoss = originalLoss;
      this.remainingLoss = originalLoss;
    }
  }

  public static BigDecimal computeBelka(BigDecimal grossProfit, boolean skipTax) {
    if (skipTax || grossProfit == null || grossProfit.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    return grossProfit.multiply(BELKA_RATE_BD);
  }

  public static BigDecimal computePositionOpenTax(
      BigDecimal grossValue, BigDecimal costBase, boolean skipTax) {
    if (skipTax || grossValue == null || grossValue.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal unrealized = grossValue.subtract(costBase != null ? costBase : BigDecimal.ZERO);
    return computeBelka(unrealized, false).min(grossValue);
  }

  public static BigDecimal computeArt30aTopUp(
      BigDecimal grossBase, BigDecimal withholdingTaxPct, boolean skipTax) {
    if (skipTax || grossBase == null || grossBase.compareTo(BigDecimal.ZERO) <= 0) {
      return BigDecimal.ZERO;
    }
    BigDecimal whtRate =
        withholdingTaxPct != null
            ? withholdingTaxPct
                .divide(HUNDRED, 6, RoundingMode.HALF_UP)
                .max(BigDecimal.ZERO)
                .min(BELKA_RATE_BD)
            : BigDecimal.ZERO;
    return grossBase.multiply(BELKA_RATE_BD.subtract(whtRate));
  }

  public static PortfolioTaxResult computePortfolioTax(
      List<CapitalDisposalEvent> disposals,
      List<Art30aPayoutEvent> payouts,
      BigDecimal openGrossTotal,
      BigDecimal openCostTotal,
      boolean skipTax) {
    if (skipTax) {
      return new PortfolioTaxResult(
          BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, List.of());
    }

    TreeMap<Integer, BigDecimal> capitalByYear = new TreeMap<>();
    TreeMap<Integer, LocalDateTime> latestDisposalByYear = new TreeMap<>();
    if (disposals != null) {
      for (CapitalDisposalEvent d : disposals) {
        if (d.profitBase() == null) continue;
        int year = d.executedAt() != null ? d.executedAt().getYear() : 1970;
        capitalByYear.merge(year, d.profitBase(), BigDecimal::add);
        if (d.executedAt() != null) {
          latestDisposalByYear.merge(year, d.executedAt(), (a, b) -> a.isAfter(b) ? a : b);
        }
      }
    }

    List<LossBucket> losses = new ArrayList<>();
    List<MetricsCalculator.CashFlow> settledTaxFlows = new ArrayList<>();
    BigDecimal settledCapitalTax = BigDecimal.ZERO;
    for (Map.Entry<Integer, BigDecimal> entry : capitalByYear.entrySet()) {
      int year = entry.getKey();
      BigDecimal netGain = entry.getValue();
      int cmp = netGain.compareTo(BigDecimal.ZERO);
      if (cmp < 0) {
        losses.add(new LossBucket(year, netGain.abs()));
      } else if (cmp > 0) {
        BigDecimal remainingGain = netGain;
        for (LossBucket bucket : losses) {
          if (bucket.year >= year - 5
              && bucket.year < year
              && bucket.remainingLoss.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal maxAnnual = bucket.originalLoss.multiply(HALF);
            BigDecimal available = bucket.remainingLoss.min(maxAnnual).min(remainingGain);
            if (available.compareTo(BigDecimal.ZERO) > 0) {
              bucket.remainingLoss = bucket.remainingLoss.subtract(available);
              remainingGain = remainingGain.subtract(available);
              if (remainingGain.compareTo(BigDecimal.ZERO) <= 0) {
                break;
              }
            }
          }
        }
        BigDecimal yearCapitalTax = computeBelka(remainingGain, false);
        settledCapitalTax = settledCapitalTax.add(yearCapitalTax);
        if (yearCapitalTax.compareTo(BigDecimal.ZERO) > 0) {
          LocalDateTime taxDate = latestDisposalByYear.getOrDefault(year, LocalDateTime.of(year, 12, 31, 23, 59));
          settledTaxFlows.add(new MetricsCalculator.CashFlow(-yearCapitalTax.doubleValue(), taxDate));
        }
      }
    }

    BigDecimal settledArt30aTax = BigDecimal.ZERO;
    if (payouts != null) {
      for (Art30aPayoutEvent p : payouts) {
        BigDecimal payoutTax = computeArt30aTopUp(p.grossBase(), p.withholdingTaxPct(), false);
        settledArt30aTax = settledArt30aTax.add(payoutTax);
        if (payoutTax.compareTo(BigDecimal.ZERO) > 0 && p.executedAt() != null) {
          settledTaxFlows.add(new MetricsCalculator.CashFlow(-payoutTax.doubleValue(), p.executedAt()));
        }
      }
    }

    BigDecimal settledTax = settledCapitalTax.add(settledArt30aTax);
    BigDecimal safeGross = openGrossTotal != null ? openGrossTotal.max(BigDecimal.ZERO) : BigDecimal.ZERO;
    BigDecimal safeCost = openCostTotal != null ? openCostTotal : BigDecimal.ZERO;
    BigDecimal hypotheticalOpenTax =
        safeGross.compareTo(BigDecimal.ZERO) > 0
            ? computeBelka(safeGross.subtract(safeCost), false).min(safeGross)
            : BigDecimal.ZERO;
    BigDecimal totalTax = settledTax.add(hypotheticalOpenTax);

    return new PortfolioTaxResult(
        settledCapitalTax, settledArt30aTax, settledTax, hypotheticalOpenTax, totalTax, List.copyOf(settledTaxFlows));
  }

  public record PositionTaxBreakdown(
      BigDecimal grossProfit,
      BigDecimal openTax,
      BigDecimal settledTax,
      BigDecimal totalTax,
      BigDecimal netProfit,
      BigDecimal netValue) {}

  public static PositionTaxBreakdown computePositionTax(
      BigDecimal valueBase,
      BigDecimal investedBase,
      BigDecimal realizedProfitBase,
      BigDecimal dividendsBase,
      boolean taxExempt) {
    BigDecimal val = valueBase != null ? valueBase : BigDecimal.ZERO;
    BigDecimal inv = investedBase != null ? investedBase : BigDecimal.ZERO;
    BigDecimal real = realizedProfitBase != null ? realizedProfitBase : BigDecimal.ZERO;
    BigDecimal divs = dividendsBase != null ? dividendsBase : BigDecimal.ZERO;
    BigDecimal grossProfit = val.subtract(inv).add(real).add(divs);
    BigDecimal openTax = computePositionOpenTax(val, inv, taxExempt);
    BigDecimal settledTax = computeBelka(real, taxExempt).add(computeBelka(divs, taxExempt));
    BigDecimal totalTax = openTax.add(settledTax);
    BigDecimal netProfit = grossProfit.subtract(totalTax);
    BigDecimal netValue = val.subtract(openTax).max(BigDecimal.ZERO);
    return new PositionTaxBreakdown(grossProfit, openTax, settledTax, totalTax, netProfit, netValue);
  }
}
