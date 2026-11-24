package pl.investassist.expense.analytics;

import java.math.BigDecimal;
import pl.investassist.expense.enums.ExpenseCategory;

public record MonthlyTrendPoint(
    String month,
    BigDecimal income,
    BigDecimal expenses,
    BigDecimal investmentsSavings,
    BigDecimal netCashflow,
    int transactionCount,
    ExpenseCategory topCategory,
    BigDecimal topCategoryAmount,
    BigDecimal savingsRatePct,
    BigDecimal momExpensesChangePct) {}
