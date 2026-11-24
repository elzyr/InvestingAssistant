package pl.investassist.expense.analytics;

import java.math.BigDecimal;
import pl.investassist.expense.enums.ExpenseCategory;

public record MerchantSummaryItem(
    String merchantKey,
    String merchantDisplay,
    ExpenseCategory category,
    BigDecimal totalAmount,
    int transactionCount,
    BigDecimal averageAmount) {}
