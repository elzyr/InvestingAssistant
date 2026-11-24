package pl.investassist.expense.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.springframework.stereotype.Component;
import pl.investassist.expense.ExpenseSupport;
import pl.investassist.expense.classifier.MerchantNormalizer;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

@Component
public class ExpenseAnalyticsEngine {

  private static final BigDecimal HUNDRED = new BigDecimal("100");
  private static final BigDecimal OUTLIER_MIN_FLOOR = new BigDecimal("500.00");
  private static final BigDecimal OUTLIER_MEDIAN_MULTIPLIER = new BigDecimal("2.5");
  private static final BigDecimal SMALL_PURCHASE_LIMIT = new BigDecimal("50.00");
  private static final int SMALL_PURCHASE_MIN_COUNT = 5;
  private static final BigDecimal MOM_SPIKE_MIN_PCT = new BigDecimal("20.00");
  private static final BigDecimal MOM_SPIKE_MIN_DELTA = new BigDecimal("100.00");
  private static final BigDecimal PRICE_HIKE_MIN_PCT = new BigDecimal("5.00");
  private static final BigDecimal RECURRING_TOLERANCE = new BigDecimal("0.12");
  private static final BigDecimal RECURRING_FIXED_TOLERANCE = new BigDecimal("0.35");
  private static final BigDecimal RECURRING_HIKE_TOLERANCE = new BigDecimal("0.25");
  private static final long REFUND_WINDOW_DAYS = 30L;

  public ExpenseAnalyticsResult analyze(
      List<AnalyticsTransaction> periodTransactions,
      List<AnalyticsTransaction> allHistoryTransactions,
      String baseCurrency,
      LocalDate periodStart,
      LocalDate periodEnd) {

    String currency = baseCurrency != null && !baseCurrency.isBlank() ? baseCurrency : "PLN";
    List<AnalyticsTransaction> activePeriod = filterActive(periodTransactions);
    List<AnalyticsTransaction> activeHistory =
        allHistoryTransactions != null && !allHistoryTransactions.isEmpty()
            ? filterActive(allHistoryTransactions)
            : activePeriod;

    int needsReviewCount = 0;
    int fxDegradedCount = 0;
    BigDecimal totalIncome = BigDecimal.ZERO;
    BigDecimal netInvestmentsSavings = BigDecimal.ZERO;
    BigDecimal totalInternalTransfers = BigDecimal.ZERO;

    List<NettedExpense> periodExpenses = new ArrayList<>();
    List<AnalyticsTransaction> periodRefunds = new ArrayList<>();

    for (AnalyticsTransaction tx : activePeriod) {
      if (tx.needsReview()) {
        needsReviewCount++;
      }
      if (tx.fxDegraded()) {
        fxDegradedCount++;
      }
      BigDecimal signedAmount = tx.baseAmount() != null ? tx.baseAmount() : BigDecimal.ZERO;
      BigDecimal absAmount = signedAmount.abs();
      switch (tx.flowKind()) {
        case INCOME -> totalIncome = totalIncome.add(absAmount);
        case INVESTMENT_SAVING -> netInvestmentsSavings = netInvestmentsSavings.add(signedAmount.negate());
        case INTERNAL_TRANSFER -> totalInternalTransfers = totalInternalTransfers.add(absAmount);
        case EXPENSE -> periodExpenses.add(new NettedExpense(tx, absAmount));
        case REFUND -> periodRefunds.add(tx);
      }
    }

    BigDecimal totalInvestmentsSavings = netInvestmentsSavings.max(BigDecimal.ZERO);
    Set<AnalyticsTransaction> pairedPeriodRefunds = new HashSet<>();
    BigDecimal totalRefundsNetted = applyRefundNetting(periodExpenses, periodRefunds, pairedPeriodRefunds);

    BigDecimal totalExpenses = BigDecimal.ZERO;
    for (NettedExpense ne : periodExpenses) {
      totalExpenses = totalExpenses.add(ne.netAmount);
    }

    LocalDate effectiveStart = resolveStart(periodStart, activePeriod);
    LocalDate effectiveEnd = resolveEnd(periodEnd, activePeriod);
    int periodMonths = countPeriodMonths(effectiveStart, effectiveEnd);

    Set<String> periodMerchantKeys = new HashSet<>();
    for (NettedExpense ne : periodExpenses) {
      if (ne.netAmount.compareTo(BigDecimal.ZERO) > 0
          && ne.tx.merchantKey() != null
          && !ne.tx.merchantKey().isBlank()) {
        periodMerchantKeys.add(ne.tx.merchantKey().toLowerCase(Locale.ROOT));
      }
    }

    List<RecurringChargeItem> recurringCharges =
        detectRecurringCharges(activeHistory, periodMerchantKeys, effectiveEnd);
    Set<String> recurringKeys = new HashSet<>();
    for (RecurringChargeItem item : recurringCharges) {
      recurringKeys.add(item.merchantKey().toLowerCase(Locale.ROOT));
    }

    BigDecimal fixedCostsTotal = BigDecimal.ZERO;
    for (NettedExpense ne : periodExpenses) {
      if (ne.netAmount.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      String key = safeLower(ne.tx.merchantKey());
      boolean isFixed = recurringKeys.contains(key) || isFixedCategory(ne.tx.category());
      if (isFixed) {
        fixedCostsTotal = fixedCostsTotal.add(ne.netAmount);
      }
    }
    BigDecimal variableCostsTotal = totalExpenses.subtract(fixedCostsTotal).max(BigDecimal.ZERO);
    BigDecimal fixedSharePct = totalExpenses.compareTo(BigDecimal.ZERO) > 0
        ? pct(fixedCostsTotal, totalExpenses)
        : ExpenseSupport.ZERO;

    List<OutlierExpenseItem> outliers = detectOutliers(periodExpenses, recurringKeys);
    BigDecimal oneOffOutliersTotal = BigDecimal.ZERO;
    for (OutlierExpenseItem o : outliers) {
      oneOffOutliersTotal = oneOffOutliersTotal.add(o.amount());
    }
    BigDecimal baseLivingExpenses = totalExpenses.subtract(oneOffOutliersTotal).max(BigDecimal.ZERO);

    BigDecimal netCashflow = totalIncome.subtract(totalExpenses);
    BigDecimal savingsRatePct = totalIncome.compareTo(BigDecimal.ZERO) > 0
        ? pct(netCashflow, totalIncome)
        : null;

    Map<ExpenseCategory, BigDecimal> priorCategoryMonthlyAvg =
        computePriorCategoryMonthlyAvg(activeHistory, effectiveStart, pairedPeriodRefunds);

    List<CategoryBreakdownItem> categoryBreakdown =
        buildCategoryBreakdown(periodExpenses, totalExpenses, priorCategoryMonthlyAvg, periodMonths);
    List<MerchantSummaryItem> topMerchants = buildTopMerchants(periodExpenses);
    List<MonthlyTrendPoint> monthlyTrend = buildMonthlyTrend(activeHistory);

    ExpenseKpis kpis = new ExpenseKpis(
        ExpenseSupport.scale2(totalIncome),
        ExpenseSupport.scale2(totalExpenses),
        ExpenseSupport.scale2(baseLivingExpenses),
        ExpenseSupport.scale2(oneOffOutliersTotal),
        ExpenseSupport.scale2(totalInvestmentsSavings),
        ExpenseSupport.scale2(totalInternalTransfers),
        ExpenseSupport.scale2(totalRefundsNetted),
        ExpenseSupport.scale2(netCashflow),
        savingsRatePct,
        ExpenseSupport.scale2(fixedCostsTotal),
        ExpenseSupport.scale2(variableCostsTotal),
        fixedSharePct,
        needsReviewCount,
        fxDegradedCount);

    List<ExpenseInsightDto> insights = buildInsights(
        currency,
        kpis,
        periodExpenses,
        categoryBreakdown,
        recurringCharges,
        outliers,
        periodMonths);

    return new ExpenseAnalyticsResult(
        currency,
        effectiveStart,
        effectiveEnd,
        kpis,
        categoryBreakdown,
        topMerchants,
        monthlyTrend,
        recurringCharges,
        outliers,
        insights);
  }

  private static List<AnalyticsTransaction> filterActive(List<AnalyticsTransaction> input) {
    if (input == null || input.isEmpty()) {
      return List.of();
    }
    List<AnalyticsTransaction> out = new ArrayList<>(input.size());
    for (AnalyticsTransaction tx : input) {
      if (tx != null && !tx.excluded()) {
        out.add(tx);
      }
    }
    return out;
  }

  private static BigDecimal applyRefundNetting(
      List<NettedExpense> expenses, List<AnalyticsTransaction> refunds) {
    return applyRefundNetting(expenses, refunds, null);
  }

  private static BigDecimal applyRefundNetting(
      List<NettedExpense> expenses,
      List<AnalyticsTransaction> refunds,
      Set<AnalyticsTransaction> pairedRefundSink) {
    if (expenses.isEmpty() || refunds.isEmpty()) {
      return BigDecimal.ZERO;
    }

    List<AnalyticsTransaction> sortedRefunds = new ArrayList<>(refunds);
    sortedRefunds.sort(Comparator
        .comparing(
            (AnalyticsTransaction r) -> r.baseAmount() != null ? r.baseAmount().abs() : BigDecimal.ZERO,
            Comparator.reverseOrder())
        .thenComparing(AnalyticsTransaction::txDate, Comparator.nullsLast(Comparator.naturalOrder())));

    BigDecimal totalNetted = BigDecimal.ZERO;
    for (AnalyticsTransaction refund : sortedRefunds) {
      BigDecimal refundAmt = refund.baseAmount() != null ? refund.baseAmount().abs() : BigDecimal.ZERO;
      if (refundAmt.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }

      NettedExpense bestMatch = findRefundTarget(expenses, refund, refundAmt, true);
      if (bestMatch == null && refund.category() != null && refund.category() != ExpenseCategory.REFUND) {
        bestMatch = findRefundTarget(expenses, refund, refundAmt, false);
      }

      if (bestMatch != null) {
        bestMatch.pairedWithRefund = true;
        bestMatch.netAmount = bestMatch.netAmount.subtract(refundAmt).max(BigDecimal.ZERO);
        totalNetted = totalNetted.add(refundAmt);
        if (pairedRefundSink != null) {
          pairedRefundSink.add(refund);
        }
      }
    }
    return totalNetted;
  }

  private static NettedExpense findRefundTarget(
      List<NettedExpense> expenses,
      AnalyticsTransaction refund,
      BigDecimal refundAmt,
      boolean matchByMerchant) {
    String refundMerchant = safeLower(refund.merchantKey());
    NettedExpense best = null;
    boolean bestExact = false;
    long bestDays = Long.MAX_VALUE;

    for (NettedExpense candidate : expenses) {
      if (candidate.pairedWithRefund) {
        continue;
      }
      if (refundAmt.compareTo(candidate.grossAmount) > 0) {
        continue;
      }
      if (refund.txDate() != null && candidate.tx.txDate() != null) {
        long days = Math.abs(ChronoUnit.DAYS.between(candidate.tx.txDate(), refund.txDate()));
        if (days > REFUND_WINDOW_DAYS) {
          continue;
        }
        boolean matched = matchByMerchant
            ? !refundMerchant.isEmpty()
                && !MerchantNormalizer.isGatewayKey(refundMerchant)
                && refundMerchant.equals(safeLower(candidate.tx.merchantKey()))
            : candidate.tx.category() == refund.category();
        if (matched) {
          boolean exact = refundAmt.compareTo(candidate.grossAmount) == 0;
          if (best == null || (exact && !bestExact) || (exact == bestExact && days < bestDays)) {
            best = candidate;
            bestExact = exact;
            bestDays = days;
          }
        }
      }
    }
    return best;
  }

  private static List<RecurringChargeItem> detectRecurringCharges(
      List<AnalyticsTransaction> history,
      Set<String> periodMerchantKeys,
      LocalDate effectiveEnd) {
    if (history.isEmpty() || periodMerchantKeys.isEmpty()) {
      return List.of();
    }

    Map<String, List<AnalyticsTransaction>> byMerchant = new LinkedHashMap<>();
    for (AnalyticsTransaction tx : history) {
      if (tx.flowKind() != FlowKind.EXPENSE || tx.txDate() == null) {
        continue;
      }
      if (effectiveEnd != null && tx.txDate().isAfter(effectiveEnd)) {
        continue;
      }
      String key = safeLower(tx.merchantKey());
      if (key.isEmpty()
          || key.equals("unknown")
          || MerchantNormalizer.isGatewayKey(key)
          || !periodMerchantKeys.contains(key)) {
        continue;
      }
      byMerchant.computeIfAbsent(key, k -> new ArrayList<>()).add(tx);
    }

    List<RecurringChargeItem> recurring = new ArrayList<>();
    for (Map.Entry<String, List<AnalyticsTransaction>> entry : byMerchant.entrySet()) {
      List<AnalyticsTransaction> txs = entry.getValue();
      Map<YearMonth, List<AnalyticsTransaction>> byMonth = new TreeMap<>();
      for (AnalyticsTransaction tx : txs) {
        byMonth.computeIfAbsent(YearMonth.from(tx.txDate()), k -> new ArrayList<>()).add(tx);
      }
      if (byMonth.size() < 2) {
        continue;
      }

      boolean tooFrequentPerMonth = byMonth.values().stream().anyMatch(list -> list.size() > 2);
      if (tooFrequentPerMonth) {
        continue;
      }

      List<MonthlyCharge> monthly = new ArrayList<>(byMonth.size());
      for (List<AnalyticsTransaction> monthTxs : byMonth.values()) {
        monthTxs.sort(Comparator.comparing(AnalyticsTransaction::txDate));
        BigDecimal sum = BigDecimal.ZERO;
        for (AnalyticsTransaction t : monthTxs) {
          if (t.baseAmount() != null) {
            sum = sum.add(t.baseAmount().abs());
          }
        }
        AnalyticsTransaction rep = monthTxs.get(monthTxs.size() - 1);
        monthly.add(new MonthlyCharge(rep.txDate(), sum, rep.merchantDisplay(), rep.category()));
      }

      if (!isRecurringSeries(monthly)) {
        continue;
      }

      BigDecimal sumAll = BigDecimal.ZERO;
      for (MonthlyCharge mc : monthly) {
        sumAll = sumAll.add(mc.amount);
      }
      BigDecimal avgMonthly = sumAll.divide(BigDecimal.valueOf(monthly.size()), 2, RoundingMode.HALF_UP);
      MonthlyCharge latest = monthly.get(monthly.size() - 1);
      MonthlyCharge prior = monthly.get(monthly.size() - 2);
      BigDecimal annualized = ExpenseSupport.scale2(avgMonthly.multiply(BigDecimal.valueOf(12)));

      BigDecimal priceIncreasePct = null;
      if (prior.amount.compareTo(BigDecimal.ZERO) > 0) {
        BigDecimal deltaPct = latest.amount.subtract(prior.amount)
            .multiply(HUNDRED)
            .divide(prior.amount, 2, RoundingMode.HALF_UP);
        if (deltaPct.compareTo(PRICE_HIKE_MIN_PCT) >= 0) {
          priceIncreasePct = deltaPct;
        }
      }

      recurring.add(new RecurringChargeItem(
          entry.getKey(),
          latest.merchantDisplay,
          latest.category,
          avgMonthly,
          ExpenseSupport.scale2(latest.amount),
          annualized,
          monthly.size(),
          priceIncreasePct,
          latest.date));
    }

    recurring.sort(Comparator.comparing(RecurringChargeItem::annualizedCost).reversed());
    return List.copyOf(recurring);
  }

  private static boolean isRecurringSeries(List<MonthlyCharge> monthly) {
    int n = monthly.size();
    if (n < 2) {
      return false;
    }
    ExpenseCategory latestCat = monthly.get(n - 1).category;
    boolean fixedCategory = isFixedCategory(latestCat);

    int validIntervals = 0;
    int tightAmountPairs = 0;
    int allPairs = n - 1;

    for (int i = 1; i < n; i++) {
      MonthlyCharge prev = monthly.get(i - 1);
      MonthlyCharge curr = monthly.get(i);
      long days = ChronoUnit.DAYS.between(prev.date, curr.date);
      if (days >= 20 && days <= 40) {
        validIntervals++;
      }
      if (prev.amount.compareTo(BigDecimal.ZERO) <= 0) {
        return false;
      }
      BigDecimal relDiff = curr.amount.subtract(prev.amount).abs()
          .divide(prev.amount, 4, RoundingMode.HALF_UP);
      BigDecimal maxAllowed = fixedCategory
          ? RECURRING_FIXED_TOLERANCE
          : (i == n - 1 && n >= 3 ? RECURRING_HIKE_TOLERANCE : RECURRING_TOLERANCE);
      if (relDiff.compareTo(maxAllowed) > 0) {
        return false;
      }
      if (relDiff.compareTo(RECURRING_TOLERANCE) <= 0) {
        tightAmountPairs++;
      }
    }

    if (validIntervals == 0) {
      return false;
    }
    if (!fixedCategory && n >= 3 && tightAmountPairs == 0) {
      return false;
    }
    return validIntervals * 2 >= allPairs;
  }

  private static List<OutlierExpenseItem> detectOutliers(
      List<NettedExpense> periodExpenses, Set<String> recurringKeys) {
    List<BigDecimal> positiveAmounts = new ArrayList<>();
    for (NettedExpense ne : periodExpenses) {
      if (ne.netAmount.compareTo(BigDecimal.ZERO) > 0) {
        positiveAmounts.add(ne.netAmount);
      }
    }
    if (positiveAmounts.isEmpty()) {
      return List.of();
    }

    positiveAmounts.sort(Comparator.naturalOrder());
    BigDecimal median = computeMedian(positiveAmounts);
    BigDecimal threshold = OUTLIER_MIN_FLOOR.max(median.multiply(OUTLIER_MEDIAN_MULTIPLIER));

    List<OutlierExpenseItem> outliers = new ArrayList<>();
    for (NettedExpense ne : periodExpenses) {
      if (ne.netAmount.compareTo(threshold) < 0) {
        continue;
      }
      String key = safeLower(ne.tx.merchantKey());
      if (recurringKeys.contains(key)) {
        continue;
      }
      outliers.add(new OutlierExpenseItem(
          ne.tx.id(),
          ne.tx.txDate(),
          ne.tx.merchantKey(),
          ne.tx.merchantDisplay(),
          ne.tx.category(),
          ExpenseSupport.scale2(ne.netAmount)));
    }
    outliers.sort(Comparator.comparing(OutlierExpenseItem::amount).reversed());
    return List.copyOf(outliers);
  }

  private static BigDecimal computeMedian(List<BigDecimal> sorted) {
    int n = sorted.size();
    if (n % 2 == 1) {
      return sorted.get(n / 2);
    }
    return sorted.get((n / 2) - 1)
        .add(sorted.get(n / 2))
        .divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
  }

  private static Map<ExpenseCategory, BigDecimal> computePriorCategoryMonthlyAvg(
      List<AnalyticsTransaction> history,
      LocalDate periodStart,
      Set<AnalyticsTransaction> excludedRefunds) {
    if (history.isEmpty() || periodStart == null) {
      return Map.of();
    }
    YearMonth startMonth = YearMonth.from(periodStart);
    List<NettedExpense> priorExpenses = new ArrayList<>();
    List<AnalyticsTransaction> historyRefunds = new ArrayList<>();

    for (AnalyticsTransaction tx : history) {
      if (tx.txDate() == null || tx.baseAmount() == null) {
        continue;
      }
      if (tx.flowKind() == FlowKind.REFUND) {
        if (excludedRefunds == null || !excludedRefunds.contains(tx)) {
          historyRefunds.add(tx);
        }
      } else if (tx.flowKind() == FlowKind.EXPENSE && tx.category() != null) {
        YearMonth ym = YearMonth.from(tx.txDate());
        if (ym.isBefore(startMonth)) {
          priorExpenses.add(new NettedExpense(tx, tx.baseAmount().abs()));
        }
      }
    }

    if (priorExpenses.isEmpty()) {
      return Map.of();
    }

    applyRefundNetting(priorExpenses, historyRefunds);

    Map<ExpenseCategory, Map<YearMonth, BigDecimal>> totalsByCatMonth = new HashMap<>();
    for (NettedExpense ne : priorExpenses) {
      if (ne.netAmount.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      YearMonth ym = YearMonth.from(ne.tx.txDate());
      totalsByCatMonth
          .computeIfAbsent(ne.tx.category(), k -> new HashMap<>())
          .merge(ym, ne.netAmount, BigDecimal::add);
    }

    Map<ExpenseCategory, BigDecimal> avgs = new HashMap<>();
    for (Map.Entry<ExpenseCategory, Map<YearMonth, BigDecimal>> entry : totalsByCatMonth.entrySet()) {
      Map<YearMonth, BigDecimal> monthMap = entry.getValue();
      if (monthMap.isEmpty()) {
        continue;
      }
      BigDecimal sum = BigDecimal.ZERO;
      for (BigDecimal val : monthMap.values()) {
        sum = sum.add(val);
      }
      avgs.put(
          entry.getKey(),
          sum.divide(BigDecimal.valueOf(monthMap.size()), 2, RoundingMode.HALF_UP));
    }
    return avgs;
  }

  private static int countPeriodMonths(LocalDate start, LocalDate end) {
    if (start == null || end == null || end.isBefore(start)) {
      return 1;
    }
    long months = ChronoUnit.MONTHS.between(YearMonth.from(start), YearMonth.from(end)) + 1;
    return (int) Math.max(1L, months);
  }

  private static List<CategoryBreakdownItem> buildCategoryBreakdown(
      List<NettedExpense> periodExpenses,
      BigDecimal totalExpenses,
      Map<ExpenseCategory, BigDecimal> priorMonthlyAvg,
      int periodMonths) {
    Map<ExpenseCategory, BigDecimal> sums = new LinkedHashMap<>();
    Map<ExpenseCategory, Integer> counts = new LinkedHashMap<>();

    for (NettedExpense ne : periodExpenses) {
      if (ne.netAmount.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      ExpenseCategory cat = ne.tx.category() != null ? ne.tx.category() : ExpenseCategory.OTHER_EXPENSE;
      sums.merge(cat, ne.netAmount, BigDecimal::add);
      counts.merge(cat, 1, Integer::sum);
    }

    BigDecimal monthFactor = BigDecimal.valueOf(Math.max(1, periodMonths));
    List<CategoryBreakdownItem> items = new ArrayList<>(sums.size());
    for (Map.Entry<ExpenseCategory, BigDecimal> entry : sums.entrySet()) {
      ExpenseCategory cat = entry.getKey();
      BigDecimal amt = ExpenseSupport.scale2(entry.getValue());
      BigDecimal share = totalExpenses.compareTo(BigDecimal.ZERO) > 0
          ? pct(amt, totalExpenses)
          : ExpenseSupport.ZERO;
      BigDecimal prevMonthly = priorMonthlyAvg.get(cat);
      BigDecimal scaledPrev = null;
      BigDecimal momPct = null;
      if (prevMonthly != null && prevMonthly.compareTo(BigDecimal.ZERO) > 0) {
        scaledPrev = ExpenseSupport.scale2(prevMonthly.multiply(monthFactor));
        momPct = amt.subtract(scaledPrev).multiply(HUNDRED).divide(scaledPrev, 2, RoundingMode.HALF_UP);
      }
      items.add(new CategoryBreakdownItem(
          cat, amt, share, counts.getOrDefault(cat, 0), scaledPrev, momPct));
    }
    items.sort(Comparator.comparing(CategoryBreakdownItem::amount).reversed());
    return List.copyOf(items);
  }

  private static List<MerchantSummaryItem> buildTopMerchants(List<NettedExpense> periodExpenses) {
    Map<String, MerchantAcc> accMap = new LinkedHashMap<>();
    for (NettedExpense ne : periodExpenses) {
      if (ne.netAmount.compareTo(BigDecimal.ZERO) <= 0) {
        continue;
      }
      String key = safeLower(ne.tx.merchantKey());
      if (key.isEmpty()) {
        key = "unknown";
      }
      MerchantAcc acc = accMap.computeIfAbsent(
          key, k -> new MerchantAcc(k, ne.tx.merchantDisplay(), ne.tx.category()));
      acc.total = acc.total.add(ne.netAmount);
      acc.count++;
    }

    return accMap.values().stream()
        .sorted(Comparator.comparing((MerchantAcc a) -> a.total).reversed())
        .limit(10)
        .map(a -> new MerchantSummaryItem(
            a.key,
            a.display,
            a.category,
            ExpenseSupport.scale2(a.total),
            a.count,
            a.total.divide(BigDecimal.valueOf(a.count), 2, RoundingMode.HALF_UP)))
        .toList();
  }

  private static List<MonthlyTrendPoint> buildMonthlyTrend(List<AnalyticsTransaction> history) {
    if (history.isEmpty()) {
      return List.of();
    }
    Map<YearMonth, MonthAcc> byMonth = new TreeMap<>();
    List<NettedExpense> allExpenses = new ArrayList<>();
    List<AnalyticsTransaction> allRefunds = new ArrayList<>();

    for (AnalyticsTransaction tx : history) {
      if (tx.txDate() == null) {
        continue;
      }
      MonthAcc acc = byMonth.computeIfAbsent(YearMonth.from(tx.txDate()), k -> new MonthAcc());
      acc.transactionCount++;
      BigDecimal signed = tx.baseAmount() != null ? tx.baseAmount() : BigDecimal.ZERO;
      BigDecimal abs = signed.abs();
      if (tx.flowKind() != null) {
        switch (tx.flowKind()) {
          case INCOME -> acc.income = acc.income.add(abs);
          case INVESTMENT_SAVING -> acc.netInvestments = acc.netInvestments.add(signed.negate());
          case EXPENSE -> allExpenses.add(new NettedExpense(tx, abs));
          case REFUND -> allRefunds.add(tx);
          case INTERNAL_TRANSFER -> {}
        }
      }
    }

    applyRefundNetting(allExpenses, allRefunds);
    for (NettedExpense ne : allExpenses) {
      MonthAcc acc = byMonth.get(YearMonth.from(ne.tx.txDate()));
      if (acc != null) {
        acc.expenses = acc.expenses.add(ne.netAmount);
        if (ne.netAmount.compareTo(BigDecimal.ZERO) > 0) {
          ExpenseCategory cat =
              ne.tx.category() != null ? ne.tx.category() : ExpenseCategory.OTHER_EXPENSE;
          acc.expensesByCategory.merge(cat, ne.netAmount, BigDecimal::add);
        }
      }
    }

    List<MonthlyTrendPoint> points = new ArrayList<>(byMonth.size());
    for (Map.Entry<YearMonth, MonthAcc> entry : byMonth.entrySet()) {
      YearMonth ym = entry.getKey();
      MonthAcc acc = entry.getValue();
      BigDecimal scaledIncome = ExpenseSupport.scale2(acc.income);
      BigDecimal scaledExpenses = ExpenseSupport.scale2(acc.expenses);
      BigDecimal scaledInvestments = ExpenseSupport.scale2(acc.netInvestments.max(BigDecimal.ZERO));
      BigDecimal scaledNet = ExpenseSupport.scale2(acc.income.subtract(acc.expenses));

      ExpenseCategory topCategory = null;
      BigDecimal bestAmount = BigDecimal.ZERO;
      for (Map.Entry<ExpenseCategory, BigDecimal> catEntry : acc.expensesByCategory.entrySet()) {
        if (catEntry.getValue().compareTo(bestAmount) > 0) {
          topCategory = catEntry.getKey();
          bestAmount = catEntry.getValue();
        }
      }
      BigDecimal topCategoryAmount =
          topCategory != null ? ExpenseSupport.scale2(bestAmount) : ExpenseSupport.ZERO;

      BigDecimal savingsRatePct =
          scaledIncome.compareTo(BigDecimal.ZERO) > 0 ? pct(scaledNet, scaledIncome) : null;

      MonthAcc prevMonth = byMonth.get(ym.minusMonths(1));
      BigDecimal momExpensesChangePct = null;
      if (prevMonth != null) {
        BigDecimal prevExpenses = ExpenseSupport.scale2(prevMonth.expenses);
        if (prevExpenses.compareTo(BigDecimal.ZERO) > 0) {
          momExpensesChangePct = pct(scaledExpenses.subtract(prevExpenses), prevExpenses);
        }
      }

      points.add(new MonthlyTrendPoint(
          ym.toString(),
          scaledIncome,
          scaledExpenses,
          scaledInvestments,
          scaledNet,
          acc.transactionCount,
          topCategory,
          topCategoryAmount,
          savingsRatePct,
          momExpensesChangePct));
    }
    return List.copyOf(points);
  }

  private static List<ExpenseInsightDto> buildInsights(
      String currency,
      ExpenseKpis kpis,
      List<NettedExpense> periodExpenses,
      List<CategoryBreakdownItem> breakdown,
      List<RecurringChargeItem> recurring,
      List<OutlierExpenseItem> outliers,
      int periodMonths) {

    List<ExpenseInsightDto> insights = new ArrayList<>();
    BigDecimal monthDivisor = BigDecimal.valueOf(Math.max(1, periodMonths));

    for (RecurringChargeItem rec : recurring) {
      if (rec.priceIncreasePct() != null && rec.priceIncreasePct().compareTo(PRICE_HIKE_MIN_PCT) >= 0) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("merchant", rec.merchantDisplay());
        params.put("pct", rec.priceIncreasePct());
        params.put("amount", rec.latestAmount());
        params.put("annualized", rec.annualizedCost());
        params.put("currency", currency);
        insights.add(new ExpenseInsightDto("RECURRING_PRICE_HIKE", "WARN", Map.copyOf(params)));
      }
    }

    for (CategoryBreakdownItem item : breakdown) {
      if (item.previousPeriodAmount() != null && item.momChangePct() != null) {
        BigDecimal monthlyDelta = item.amount().subtract(item.previousPeriodAmount())
            .divide(monthDivisor, 2, RoundingMode.HALF_UP);
        if (monthlyDelta.compareTo(MOM_SPIKE_MIN_DELTA) >= 0
            && item.momChangePct().compareTo(MOM_SPIKE_MIN_PCT) >= 0) {
          Map<String, Object> params = new LinkedHashMap<>();
          params.put("category", item.category().name());
          params.put("pct", item.momChangePct());
          params.put("amount", item.amount());
          params.put("previous", item.previousPeriodAmount());
          params.put("currency", currency);
          insights.add(new ExpenseInsightDto("CATEGORY_MOM_SPIKE", "WARN", Map.copyOf(params)));
        }
      }
    }

    Map<String, MerchantAcc> smallByMerchant = new LinkedHashMap<>();
    int totalSmallCount = 0;
    BigDecimal totalSmallSum = BigDecimal.ZERO;
    for (NettedExpense ne : periodExpenses) {
      if (ne.netAmount.compareTo(BigDecimal.ZERO) <= 0
          || ne.netAmount.compareTo(SMALL_PURCHASE_LIMIT) > 0
          || isFixedCategory(ne.tx.category())) {
        continue;
      }
      totalSmallCount++;
      totalSmallSum = totalSmallSum.add(ne.netAmount);
      String key = safeLower(ne.tx.merchantKey());
      MerchantAcc acc = smallByMerchant.computeIfAbsent(
          key, k -> new MerchantAcc(k, ne.tx.merchantDisplay(), ne.tx.category()));
      acc.count++;
      acc.total = acc.total.add(ne.netAmount);
    }

    MerchantAcc topSmallMerchant = smallByMerchant.values().stream()
        .filter(a -> a.count >= SMALL_PURCHASE_MIN_COUNT)
        .max(Comparator.comparing((MerchantAcc a) -> a.total))
        .orElse(null);

    if (topSmallMerchant != null) {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("merchant", topSmallMerchant.display);
      params.put("count", topSmallMerchant.count);
      params.put("total", ExpenseSupport.scale2(topSmallMerchant.total));
      params.put("currency", currency);
      insights.add(new ExpenseInsightDto("CONVENIENCE_DRAIN", "WARN", Map.copyOf(params)));
    } else if (totalSmallCount >= SMALL_PURCHASE_MIN_COUNT) {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("merchant", "");
      params.put("count", totalSmallCount);
      params.put("total", ExpenseSupport.scale2(totalSmallSum));
      params.put("currency", currency);
      insights.add(new ExpenseInsightDto("CONVENIENCE_DRAIN", "WARN", Map.copyOf(params)));
    }

    if (!outliers.isEmpty()) {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("count", outliers.size());
      params.put("total", kpis.oneOffOutliersTotal());
      params.put("baseLiving", kpis.baseLivingExpenses());
      params.put("currency", currency);
      insights.add(new ExpenseInsightDto("ONE_OFF_OUTLIERS", "INFO", Map.copyOf(params)));
    }

    if (kpis.fixedSharePct().compareTo(new BigDecimal("60.00")) >= 0) {
      Map<String, Object> params = new LinkedHashMap<>();
      params.put("pct", kpis.fixedSharePct());
      insights.add(new ExpenseInsightDto("HIGH_FIXED_COSTS", "WARN", Map.copyOf(params)));
    }

    if (kpis.savingsRatePct() != null) {
      if (kpis.savingsRatePct().compareTo(new BigDecimal("20.00")) >= 0) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("pct", kpis.savingsRatePct());
        params.put("netCashflow", kpis.netCashflow());
        params.put("currency", currency);
        insights.add(new ExpenseInsightDto("HEALTHY_SAVINGS_RATE", "SUCCESS", Map.copyOf(params)));
      } else if (kpis.savingsRatePct().compareTo(new BigDecimal("10.00")) < 0) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("pct", kpis.savingsRatePct());
        insights.add(new ExpenseInsightDto("LOW_SAVINGS_RATE", "WARN", Map.copyOf(params)));
      }
    }

    if (kpis.fxDegradedCount() > 0) {
      insights.add(new ExpenseInsightDto(
          "FX_DEGRADED_WARNING", "WARN", Map.of("count", kpis.fxDegradedCount())));
    }

    if (kpis.needsReviewCount() > 0) {
      insights.add(new ExpenseInsightDto(
          "NEEDS_REVIEW_ITEMS", "INFO", Map.of("count", kpis.needsReviewCount())));
    }

    return List.copyOf(insights);
  }

  private static LocalDate resolveStart(LocalDate explicit, List<AnalyticsTransaction> txs) {
    if (explicit != null) {
      return explicit;
    }
    return txs.stream()
        .map(AnalyticsTransaction::txDate)
        .filter(d -> d != null)
        .min(Comparator.naturalOrder())
        .orElse(null);
  }

  private static LocalDate resolveEnd(LocalDate explicit, List<AnalyticsTransaction> txs) {
    if (explicit != null) {
      return explicit;
    }
    return txs.stream()
        .map(AnalyticsTransaction::txDate)
        .filter(d -> d != null)
        .max(Comparator.naturalOrder())
        .orElse(null);
  }

  private static BigDecimal pct(BigDecimal part, BigDecimal whole) {
    return part.multiply(HUNDRED).divide(whole, 2, RoundingMode.HALF_UP);
  }

  private static boolean isFixedCategory(ExpenseCategory category) {
    return category == ExpenseCategory.HOUSING
        || category == ExpenseCategory.UTILITIES
        || category == ExpenseCategory.TELECOM
        || category == ExpenseCategory.SUBSCRIPTIONS;
  }

  private static String safeLower(String s) {
    return s != null ? s.trim().toLowerCase(Locale.ROOT) : "";
  }

  private static final class NettedExpense {
    final AnalyticsTransaction tx;
    final BigDecimal grossAmount;
    BigDecimal netAmount;
    boolean pairedWithRefund;

    NettedExpense(AnalyticsTransaction tx, BigDecimal grossAmount) {
      this.tx = tx;
      this.grossAmount = grossAmount;
      this.netAmount = grossAmount;
    }
  }

  private static final class MerchantAcc {
    final String key;
    final String display;
    final ExpenseCategory category;
    BigDecimal total = BigDecimal.ZERO;
    int count = 0;

    MerchantAcc(String key, String display, ExpenseCategory category) {
      this.key = key;
      this.display = display;
      this.category = category;
    }
  }

  private static final class MonthAcc {
    BigDecimal income = BigDecimal.ZERO;
    BigDecimal expenses = BigDecimal.ZERO;
    BigDecimal netInvestments = BigDecimal.ZERO;
    int transactionCount = 0;
    final Map<ExpenseCategory, BigDecimal> expensesByCategory = new EnumMap<>(ExpenseCategory.class);
  }

  private record MonthlyCharge(
      LocalDate date, BigDecimal amount, String merchantDisplay, ExpenseCategory category) {}
}
