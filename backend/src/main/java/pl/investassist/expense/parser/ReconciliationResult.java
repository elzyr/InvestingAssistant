package pl.investassist.expense.parser;

import java.math.BigDecimal;
import pl.investassist.expense.enums.ReconciliationStatus;

public record ReconciliationResult(
    ReconciliationStatus status,
    BigDecimal openingBalance,
    BigDecimal closingBalance,
    BigDecimal calculatedInflows,
    BigDecimal calculatedOutflows,
    BigDecimal discrepancy) {}
