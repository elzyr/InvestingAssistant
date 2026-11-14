package pl.investassist.portfolio.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;
import pl.investassist.portfolio.valuation.TaxMode;

public record ProjectionRequest(
    @Min(1) @Max(40) Integer horizonYears,
    @DecimalMin("0.0") BigDecimal monthlyDca,
    @DecimalMin("0.0") @DecimalMax("1.0") Double dcaEquityShare,
    String taxMode,
    Boolean realMode,
    Boolean bondRollover,
    @DecimalMin("0.01") BigDecimal targetGoal,
    @DecimalMin("-50.0") @DecimalMax("100.0") BigDecimal etfReturnOverridePct) {

  public int effectiveHorizonYears() {
    return horizonYears != null ? horizonYears : 10;
  }

  public BigDecimal effectiveMonthlyDca() {
    return monthlyDca != null ? monthlyDca : BigDecimal.ZERO;
  }

  public double effectiveDcaEquityShare() {
    return dcaEquityShare != null ? dcaEquityShare : 0.5d;
  }

  public TaxMode effectiveTaxMode() {
    return TaxMode.fromParam(taxMode);
  }

  public boolean effectiveRealMode() {
    return Boolean.TRUE.equals(realMode);
  }

  public boolean effectiveBondRollover() {
    return bondRollover == null || bondRollover;
  }

  public String cacheKeySuffix() {
    return effectiveHorizonYears()
        + ":" + effectiveMonthlyDca().stripTrailingZeros().toPlainString()
        + ":" + effectiveDcaEquityShare()
        + ":" + effectiveTaxMode().name()
        + ":" + effectiveRealMode()
        + ":" + effectiveBondRollover()
        + ":" + (targetGoal != null ? targetGoal.stripTrailingZeros().toPlainString() : "none")
        + ":" + (etfReturnOverridePct != null ? etfReturnOverridePct.stripTrailingZeros().toPlainString() : "auto");
  }
}
