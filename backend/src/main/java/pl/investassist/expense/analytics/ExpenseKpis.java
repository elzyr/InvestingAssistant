package pl.investassist.expense.analytics;

import java.math.BigDecimal;

public record ExpenseKpis(
    BigDecimal totalIncome,
    BigDecimal totalExpenses,
    BigDecimal baseLivingExpenses,
    BigDecimal oneOffOutliersTotal,
    BigDecimal totalInvestmentsSavings,
    BigDecimal totalInternalTransfers,
    BigDecimal totalRefundsNetted,
    BigDecimal netCashflow,
    BigDecimal savingsRatePct,
    BigDecimal fixedCostsTotal,
    BigDecimal variableCostsTotal,
    BigDecimal fixedSharePct,
    int needsReviewCount,
    int fxDegradedCount) {}
