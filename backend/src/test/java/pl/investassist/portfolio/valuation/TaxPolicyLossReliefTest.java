package pl.investassist.portfolio.valuation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class TaxPolicyLossReliefTest {

  private static final List<TaxPolicy.CapitalDisposalEvent> LOSS = List.of(
      new TaxPolicy.CapitalDisposalEvent(LocalDateTime.of(2026, 3, 1, 12, 0), new BigDecimal("-10000")));

  private static BigDecimal openTax(int liquidationYear, String bondGain) {
    return TaxPolicy.computePortfolioTax(
            LOSS, List.of(), new BigDecimal("60000"), new BigDecimal("50000"),
            new BigDecimal(bondGain), liquidationYear, false)
        .hypotheticalOpenTax();
  }

  @Test
  void realizedLossesReduceHypotheticalTaxOnOpenCapitalGainsOnly() {
    assertThat(openTax(2026, "0")).isEqualByComparingTo("0");
    assertThat(openTax(2027, "0")).isEqualByComparingTo("950");
    assertThat(openTax(2032, "0")).isEqualByComparingTo("1900");
    assertThat(openTax(2026, "4000")).isEqualByComparingTo("760");
  }
}
