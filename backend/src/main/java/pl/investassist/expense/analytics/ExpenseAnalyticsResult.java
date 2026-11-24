package pl.investassist.expense.analytics;

import java.time.LocalDate;
import java.util.List;

public record ExpenseAnalyticsResult(
    String baseCurrency,
    LocalDate periodStart,
    LocalDate periodEnd,
    ExpenseKpis kpis,
    List<CategoryBreakdownItem> categoryBreakdown,
    List<MerchantSummaryItem> topMerchants,
    List<MonthlyTrendPoint> monthlyTrend,
    List<RecurringChargeItem> recurringCharges,
    List<OutlierExpenseItem> outliers,
    List<ExpenseInsightDto> insights) {}
