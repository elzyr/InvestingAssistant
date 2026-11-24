package pl.investassist.expense.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import pl.investassist.expense.enums.ExpenseCategory;

public record RecurringChargeItem(
    String merchantKey,
    String merchantDisplay,
    ExpenseCategory category,
    BigDecimal averageMonthlyAmount,
    BigDecimal latestAmount,
    BigDecimal annualizedCost,
    int occurrences,
    BigDecimal priceIncreasePct,
    LocalDate lastChargeDate) {}
