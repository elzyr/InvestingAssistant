package pl.investassist.expense.analytics;

import java.math.BigDecimal;
import pl.investassist.expense.enums.ExpenseCategory;

public record CategoryBreakdownItem(
    ExpenseCategory category,
    BigDecimal amount,
    BigDecimal sharePct,
    int transactionCount,
    BigDecimal previousPeriodAmount,
    BigDecimal momChangePct) {}
