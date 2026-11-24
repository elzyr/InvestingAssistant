package pl.investassist.expense.parser;

import java.math.BigDecimal;
import java.util.List;
import org.springframework.stereotype.Component;
import pl.investassist.expense.ExpenseSupport;
import pl.investassist.expense.enums.ReconciliationStatus;

@Component
public class BalanceReconciler {

  private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

  public ReconciliationResult reconcile(
      BigDecimal openingBalance,
      BigDecimal closingBalance,
      List<BigDecimal> signedAmounts) {

    BigDecimal inflows = BigDecimal.ZERO;
    BigDecimal outflows = BigDecimal.ZERO;
    if (signedAmounts != null) {
      for (BigDecimal amount : signedAmounts) {
        if (amount == null) {
          continue;
        }
        int cmp = amount.compareTo(BigDecimal.ZERO);
        if (cmp > 0) {
          inflows = inflows.add(amount);
        } else if (cmp < 0) {
          outflows = outflows.add(amount.abs());
        }
      }
    }

    BigDecimal scaledInflows = ExpenseSupport.scale2(inflows);
    BigDecimal scaledOutflows = ExpenseSupport.scale2(outflows);
    BigDecimal scaledOpening = openingBalance != null ? ExpenseSupport.scale2(openingBalance) : null;
    BigDecimal scaledClosing = closingBalance != null ? ExpenseSupport.scale2(closingBalance) : null;

    if (scaledOpening == null || scaledClosing == null) {
      return new ReconciliationResult(
          ReconciliationStatus.UNAVAILABLE,
          scaledOpening,
          scaledClosing,
          scaledInflows,
          scaledOutflows,
          null);
    }

    BigDecimal expectedClosing = scaledOpening.add(scaledInflows).subtract(scaledOutflows);
    BigDecimal discrepancy = ExpenseSupport.scale2(scaledClosing.subtract(expectedClosing));
    ReconciliationStatus status = discrepancy.abs().compareTo(TOLERANCE) <= 0
        ? ReconciliationStatus.VERIFIED
        : ReconciliationStatus.MISMATCH;

    return new ReconciliationResult(
        status,
        scaledOpening,
        scaledClosing,
        scaledInflows,
        scaledOutflows,
        discrepancy);
  }
}
