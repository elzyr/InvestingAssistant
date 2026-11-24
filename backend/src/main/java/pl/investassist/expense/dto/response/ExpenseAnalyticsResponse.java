package pl.investassist.expense.dto.response;

import java.time.LocalDate;
import java.util.List;
import pl.investassist.expense.analytics.CategoryBreakdownItem;
import pl.investassist.expense.analytics.ExpenseInsightDto;
import pl.investassist.expense.analytics.ExpenseKpis;
import pl.investassist.expense.analytics.MerchantSummaryItem;
import pl.investassist.expense.analytics.MonthlyTrendPoint;
import pl.investassist.expense.analytics.OutlierExpenseItem;
import pl.investassist.expense.analytics.RecurringChargeItem;

public record ExpenseAnalyticsResponse(
    String baseCurrency,
    LocalDate periodStart,
    LocalDate periodEnd,
    ExpenseKpis kpis,
    List<CategoryBreakdownItem> categoryBreakdown,
    List<MerchantSummaryItem> topMerchants,
    List<MonthlyTrendPoint> monthlyTrend,
    List<RecurringChargeItem> recurringCharges,
    List<OutlierExpenseItem> outliers,
    List<ExpenseInsightDto> insights,
    List<ExpenseTransactionResponse> transactions) {}
