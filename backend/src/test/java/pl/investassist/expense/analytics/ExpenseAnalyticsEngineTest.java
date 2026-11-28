package pl.investassist.expense.analytics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

class ExpenseAnalyticsEngineTest {

  private final ExpenseAnalyticsEngine engine = new ExpenseAnalyticsEngine();

  @Test
  void analyze_netsRefundsIntoSameMerchantCategoryAndComputesSavingsRate() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> txs = List.of(
        tx(LocalDate.of(2026, 10, 1), "pracodawca", "Wynagrodzenie", "5000.00", FlowKind.INCOME, ExpenseCategory.INCOME),
        tx(LocalDate.of(2026, 10, 2), "zalando", "Zalando", "-450.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 6), "zalando", "Zalando Zwrot", "450.00", FlowKind.REFUND, ExpenseCategory.REFUND),
        tx(LocalDate.of(2026, 10, 8), "biedronka", "Biedronka", "-200.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES)
    );

    ExpenseAnalyticsResult result = engine.analyze(txs, txs, "PLN", start, end);

    assertThat(result.baseCurrency()).isEqualTo("PLN");
    assertThat(result.kpis().totalIncome()).isEqualByComparingTo("5000.00");
    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("200.00");
    assertThat(result.kpis().totalRefundsNetted()).isEqualByComparingTo("450.00");
    assertThat(result.kpis().netCashflow()).isEqualByComparingTo("4800.00");
    assertThat(result.kpis().savingsRatePct()).isEqualByComparingTo("96.00");

    BigDecimal shoppingNet = result.categoryBreakdown().stream()
        .filter(c -> c.category() == ExpenseCategory.SHOPPING)
        .map(CategoryBreakdownItem::amount)
        .findFirst()
        .orElse(BigDecimal.ZERO);
    assertThat(shoppingNet).isEqualByComparingTo("0.00");
  }

  @Test
  void analyze_doesNotInflateIncomeWithUnmatchedRefundAndRespects30DayWindow() {
    LocalDate start = LocalDate.of(2026, 8, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> period = List.of(
        tx(LocalDate.of(2026, 8, 1), "allegro", "Allegro", "-120.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 5), "zalando", "Zalando", "-300.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 15), "allegro", "Allegro Zwrot", "120.00", FlowKind.REFUND, ExpenseCategory.REFUND)
    );

    ExpenseAnalyticsResult result = engine.analyze(period, period, "PLN", start, end);

    assertThat(result.kpis().totalIncome()).isEqualByComparingTo("0.00");
    assertThat(result.kpis().totalRefundsNetted()).isEqualByComparingTo("0.00");
    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("420.00");
  }

  @Test
  void analyze_excludesInternalTransfersInvestmentsAndExcludedRowsFromConsumption() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> txs = List.of(
        tx(LocalDate.of(2026, 10, 1), "salary", "Wynagrodzenie", "6000.00", FlowKind.INCOME, ExpenseCategory.INCOME),
        tx(LocalDate.of(2026, 10, 3), "own", "Przelew własny", "-1500.00", FlowKind.INTERNAL_TRANSFER, ExpenseCategory.INTERNAL_TRANSFER),
        tx(LocalDate.of(2026, 10, 4), "xtb", "XTB S.A.", "-1200.00", FlowKind.INVESTMENT_SAVING, ExpenseCategory.INVESTMENTS_SAVINGS),
        tx(LocalDate.of(2026, 10, 5), "biedronka", "Biedronka", "-400.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        new AnalyticsTransaction(
            UUID.randomUUID(), LocalDate.of(2026, 10, 6), "ignored", "Ignored",
            new BigDecimal("-999.00"), FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE, true, false, true)
    );

    ExpenseAnalyticsResult result = engine.analyze(txs, txs, "PLN", start, end);

    assertThat(result.kpis().totalIncome()).isEqualByComparingTo("6000.00");
    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("400.00");
    assertThat(result.kpis().totalInternalTransfers()).isEqualByComparingTo("1500.00");
    assertThat(result.kpis().totalInvestmentsSavings()).isEqualByComparingTo("1200.00");
    assertThat(result.kpis().needsReviewCount()).isZero();
  }

  @Test
  void analyze_detectsRecurringPriceHikeOutliersAndConvenienceDrain() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    AnalyticsTransaction augNetflix = tx(
        LocalDate.of(2026, 8, 5), "netflix", "Netflix", "-43.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS);
    AnalyticsTransaction sepNetflix = tx(
        LocalDate.of(2026, 9, 5), "netflix", "Netflix", "-43.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS);

    List<AnalyticsTransaction> octTxs = List.of(
        tx(LocalDate.of(2026, 10, 5), "netflix", "Netflix", "-49.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        tx(LocalDate.of(2026, 10, 12), "dentystyka", "Dentystyka", "-1800.00", FlowKind.EXPENSE, ExpenseCategory.HEALTH),
        tx(LocalDate.of(2026, 10, 10), "biedronka", "Biedronka", "-100.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 2), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 3), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 4), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 6), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 7), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 8), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES)
    );

    List<AnalyticsTransaction> allHistory = new java.util.ArrayList<>();
    allHistory.add(augNetflix);
    allHistory.add(sepNetflix);
    allHistory.addAll(octTxs);

    ExpenseAnalyticsResult result = engine.analyze(octTxs, allHistory, "PLN", start, end);

    assertThat(result.recurringCharges()).hasSize(1);
    RecurringChargeItem netflix = result.recurringCharges().get(0);
    assertThat(netflix.merchantKey()).isEqualTo("netflix");
    assertThat(netflix.priceIncreasePct()).isEqualByComparingTo("13.95");
    assertThat(netflix.annualizedCost())
        .isGreaterThanOrEqualTo(new BigDecimal("540.00"))
        .isLessThanOrEqualTo(new BigDecimal("588.00"));

    assertThat(result.outliers()).hasSize(1);
    assertThat(result.outliers().get(0).merchantDisplay()).isEqualTo("Dentystyka");
    assertThat(result.outliers().get(0).amount()).isEqualByComparingTo("1800.00");
    assertThat(result.kpis().oneOffOutliersTotal()).isEqualByComparingTo("1800.00");
    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("2099.00");
    assertThat(result.kpis().baseLivingExpenses()).isEqualByComparingTo("299.00");

    assertThat(result.insights()).anySatisfy(insight -> {
      String code = insight.code().toUpperCase(Locale.ROOT);
      assertThat(code.contains("DRAIN") || code.contains("SMALL") || code.contains("CONVENIENCE")).isTrue();
    });
    assertThat(result.insights()).anySatisfy(insight -> {
      assertThat(insight.code()).isEqualTo("RECURRING_PRICE_HIKE");
      assertThat(insight.params().get("merchant")).isEqualTo("Netflix");
      assertThat((BigDecimal) insight.params().get("pct")).isEqualByComparingTo("13.95");
      assertThat((BigDecimal) insight.params().get("amount")).isEqualByComparingTo("49.00");
      assertThat(insight.params().get("currency")).isEqualTo("PLN");
    });
  }

  @Test
  void analyze_emitsMomAnomalyAndHandlesEmptyAndZeroIncomeSafely() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> period = List.of(
        tx(LocalDate.of(2026, 10, 14), "restauracja", "Restauracja", "-350.00", FlowKind.EXPENSE, ExpenseCategory.DINING)
    );
    List<AnalyticsTransaction> history = List.of(
        tx(LocalDate.of(2026, 8, 14), "restauracja", "Restauracja", "-100.00", FlowKind.EXPENSE, ExpenseCategory.DINING),
        tx(LocalDate.of(2026, 9, 14), "restauracja", "Restauracja", "-100.00", FlowKind.EXPENSE, ExpenseCategory.DINING),
        period.get(0)
    );

    ExpenseAnalyticsResult spikeResult = engine.analyze(period, history, "PLN", start, end);
    assertThat(spikeResult.kpis().totalIncome()).isEqualByComparingTo("0.00");
    assertThat(spikeResult.kpis().netCashflow()).isEqualByComparingTo("-350.00");
    assertThat(spikeResult.kpis().savingsRatePct())
        .satisfiesAnyOf(
            pct -> assertThat(pct).isNull(),
            pct -> assertThat(pct).isEqualByComparingTo("0.00"));
    assertThat(spikeResult.insights()).anySatisfy(insight -> {
      String code = insight.code().toUpperCase(Locale.ROOT);
      assertThat(code.contains("SPIKE") || code.contains("ANOMALY") || code.contains("MOM")).isTrue();
    });

    assertThatCode(() -> {
      ExpenseAnalyticsResult empty = engine.analyze(List.of(), List.of(), "PLN", start, end);
      assertThat(empty.kpis().totalExpenses()).isEqualByComparingTo("0.00");
      assertThat(empty.kpis().totalIncome()).isEqualByComparingTo("0.00");
      assertThat(empty.categoryBreakdown()).isEmpty();
      assertThat(empty.recurringCharges()).isEmpty();
      assertThat(empty.outliers()).isEmpty();
    }).doesNotThrowAnyException();
  }

  @Test
  void analyze_enforcesOneToOneAndNonExceedingRefundsPlusFixedVariableTrendTopMerchantsAndFlags() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> txs = List.of(
        tx(LocalDate.of(2026, 10, 1), "czynsz", "Czynsz", "-2000.00", FlowKind.EXPENSE, ExpenseCategory.HOUSING),
        tx(LocalDate.of(2026, 10, 2), "allegro", "Allegro", "-200.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 4), "allegro", "Allegro Zwrot 1", "80.00", FlowKind.REFUND, ExpenseCategory.REFUND),
        tx(LocalDate.of(2026, 10, 6), "allegro", "Allegro Zwrot 2", "70.00", FlowKind.REFUND, ExpenseCategory.REFUND),
        tx(LocalDate.of(2026, 10, 8), "empik", "Empik", "-100.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 9), "empik", "Empik Zwrot Za Duzy", "150.00", FlowKind.REFUND, ExpenseCategory.REFUND),
        new AnalyticsTransaction(
            UUID.randomUUID(), LocalDate.of(2026, 10, 10), "sklep_a", "Sklep A",
            new BigDecimal("-400.00"), FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE, false, true, true),
        new AnalyticsTransaction(
            UUID.randomUUID(), LocalDate.of(2026, 10, 11), "excluded_shop", "Excluded Shop",
            new BigDecimal("-50.00"), FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE, true, true, true)
    );

    ExpenseAnalyticsResult result = engine.analyze(txs, txs, "PLN", start, end);

    assertThat(result.kpis().totalRefundsNetted()).isEqualByComparingTo("80.00");
    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("2620.00");
    assertThat(result.kpis().fixedCostsTotal()).isEqualByComparingTo("2000.00");
    assertThat(result.kpis().variableCostsTotal()).isEqualByComparingTo("620.00");
    assertThat(result.kpis().fixedSharePct()).isEqualByComparingTo("76.34");
    assertThat(result.kpis().needsReviewCount()).isEqualTo(1);
    assertThat(result.kpis().fxDegradedCount()).isEqualTo(1);

    assertThat(result.topMerchants()).extracting(MerchantSummaryItem::merchantKey)
        .containsExactly("czynsz", "sklep_a", "allegro", "empik");
    assertThat(result.topMerchants().get(2).totalAmount()).isEqualByComparingTo("120.00");

    assertThat(result.monthlyTrend()).hasSize(1);
    assertThat(result.monthlyTrend().get(0).month()).isEqualTo("2026-10");
    assertThat(result.monthlyTrend().get(0).expenses()).isEqualByComparingTo("2620.00");
  }

  @Test
  void analyze_netsLaterMonthRefundIntoPurchasePeriod() {
    AnalyticsTransaction purchase = tx(
        LocalDate.of(2026, 1, 28), "zalando", "Zalando", "-500.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    AnalyticsTransaction refund = tx(
        LocalDate.of(2026, 2, 3), "zalando", "Zalando Zwrot", "500.00", FlowKind.REFUND, ExpenseCategory.REFUND);

    ExpenseAnalyticsResult result = engine.analyze(
        List.of(purchase), List.of(purchase, refund), "PLN",
        LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 31));

    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("0.00");
    assertThat(result.kpis().totalRefundsNetted()).isEqualByComparingTo("500.00");
  }

  @Test
  void analyze_netsInvestmentWithdrawalsAndFloorsAtZero() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> roundTrip = List.of(
        tx(LocalDate.of(2026, 10, 2), "xtb", "XTB S.A. WPŁATA", "-2500.00", FlowKind.INVESTMENT_SAVING, ExpenseCategory.INVESTMENTS_SAVINGS),
        tx(LocalDate.of(2026, 10, 10), "xtb", "XTB S.A. WYPŁATA", "2500.00", FlowKind.INVESTMENT_SAVING, ExpenseCategory.INVESTMENTS_SAVINGS),
        tx(LocalDate.of(2026, 10, 15), "obligacje", "OBLIGACJE SKARBOWE WYKUP", "5000.00", FlowKind.INVESTMENT_SAVING, ExpenseCategory.INVESTMENTS_SAVINGS)
    );

    ExpenseAnalyticsResult floored = engine.analyze(roundTrip, roundTrip, "PLN", start, end);
    assertThat(floored.kpis().totalInvestmentsSavings()).isEqualByComparingTo("0.00");
    assertThat(floored.monthlyTrend()).hasSize(1);
    assertThat(floored.monthlyTrend().get(0).investmentsSavings()).isEqualByComparingTo("0.00");

    List<AnalyticsTransaction> netPositive = List.of(
        tx(LocalDate.of(2026, 10, 2), "xtb", "XTB S.A. WPŁATA", "-3000.00", FlowKind.INVESTMENT_SAVING, ExpenseCategory.INVESTMENTS_SAVINGS),
        tx(LocalDate.of(2026, 10, 10), "xtb", "XTB S.A. WYPŁATA", "1000.00", FlowKind.INVESTMENT_SAVING, ExpenseCategory.INVESTMENTS_SAVINGS)
    );
    ExpenseAnalyticsResult netResult = engine.analyze(netPositive, netPositive, "PLN", start, end);
    assertThat(netResult.kpis().totalInvestmentsSavings()).isEqualByComparingTo("2000.00");
    assertThat(netResult.monthlyTrend().get(0).investmentsSavings()).isEqualByComparingTo("2000.00");
  }

  @Test
  void analyze_filtersRecurringHistoryAfterPeriodEndAndExcludesFullyRefundedMerchants() {
    LocalDate sepStart = LocalDate.of(2026, 9, 1);
    LocalDate sepEnd = LocalDate.of(2026, 9, 30);

    List<AnalyticsTransaction> sepPeriod = List.of(
        tx(LocalDate.of(2026, 9, 5), "netflix", "Netflix", "-43.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        tx(LocalDate.of(2026, 9, 6), "spotify", "Spotify", "-20.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        tx(LocalDate.of(2026, 9, 8), "spotify", "Spotify Zwrot", "20.00", FlowKind.REFUND, ExpenseCategory.REFUND)
    );
    List<AnalyticsTransaction> fullHistory = List.of(
        tx(LocalDate.of(2026, 8, 5), "netflix", "Netflix", "-43.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        tx(LocalDate.of(2026, 8, 6), "spotify", "Spotify", "-20.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        sepPeriod.get(0),
        sepPeriod.get(1),
        sepPeriod.get(2),
        tx(LocalDate.of(2026, 10, 5), "netflix", "Netflix", "-49.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        tx(LocalDate.of(2026, 11, 5), "netflix", "Netflix", "-59.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS)
    );

    ExpenseAnalyticsResult sepResult = engine.analyze(sepPeriod, fullHistory, "PLN", sepStart, sepEnd);

    assertThat(sepResult.recurringCharges()).hasSize(1);
    RecurringChargeItem netflix = sepResult.recurringCharges().get(0);
    assertThat(netflix.merchantKey()).isEqualTo("netflix");
    assertThat(netflix.latestAmount()).isEqualByComparingTo("43.00");
    assertThat(netflix.lastChargeDate()).isEqualTo(LocalDate.of(2026, 9, 5));
    assertThat(netflix.priceIncreasePct()).isNull();
    assertThat(sepResult.insights())
        .noneMatch(i -> "RECURRING_PRICE_HIKE".equals(i.code()));
  }

  @Test
  void analyze_normalizesMultiMonthPeriodSpendAgainstRefundNettedPriorMonthlyAverage() {
    LocalDate augStart = LocalDate.of(2026, 8, 1);
    LocalDate octEnd = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> period = List.of(
        tx(LocalDate.of(2026, 8, 10), "restauracja", "Restauracja", "-300.00", FlowKind.EXPENSE, ExpenseCategory.DINING),
        tx(LocalDate.of(2026, 9, 10), "restauracja", "Restauracja", "-300.00", FlowKind.EXPENSE, ExpenseCategory.DINING),
        tx(LocalDate.of(2026, 10, 10), "restauracja", "Restauracja", "-300.00", FlowKind.EXPENSE, ExpenseCategory.DINING),
        tx(LocalDate.of(2026, 8, 12), "biedronka", "Biedronka", "-1000.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 9, 12), "biedronka", "Biedronka", "-1000.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 12), "biedronka", "Biedronka", "-1000.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES)
    );

    List<AnalyticsTransaction> history = new java.util.ArrayList<>();
    history.add(tx(LocalDate.of(2026, 6, 10), "restauracja", "Restauracja", "-400.00", FlowKind.EXPENSE, ExpenseCategory.DINING));
    history.add(tx(LocalDate.of(2026, 6, 14), "restauracja", "Restauracja Zwrot", "100.00", FlowKind.REFUND, ExpenseCategory.REFUND));
    history.add(tx(LocalDate.of(2026, 7, 10), "restauracja", "Restauracja", "-300.00", FlowKind.EXPENSE, ExpenseCategory.DINING));
    history.add(tx(LocalDate.of(2026, 6, 12), "biedronka", "Biedronka", "-1000.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES));
    history.add(tx(LocalDate.of(2026, 7, 12), "biedronka", "Biedronka", "-1000.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES));
    history.addAll(period);

    ExpenseAnalyticsResult result = engine.analyze(period, history, "PLN", augStart, octEnd);

    CategoryBreakdownItem dining = result.categoryBreakdown().stream()
        .filter(c -> c.category() == ExpenseCategory.DINING)
        .findFirst()
        .orElseThrow();
    assertThat(dining.amount()).isEqualByComparingTo("900.00");
    assertThat(dining.previousPeriodAmount()).isEqualByComparingTo("900.00");
    assertThat(dining.momChangePct()).isEqualByComparingTo("0.00");

    CategoryBreakdownItem groceries = result.categoryBreakdown().stream()
        .filter(c -> c.category() == ExpenseCategory.GROCERIES)
        .findFirst()
        .orElseThrow();
    assertThat(groceries.amount()).isEqualByComparingTo("3000.00");
    assertThat(groceries.previousPeriodAmount()).isEqualByComparingTo("3000.00");
    assertThat(groceries.momChangePct()).isEqualByComparingTo("0.00");

    assertThat(result.insights())
        .noneMatch(i -> "CATEGORY_MOM_SPIKE".equals(i.code()));
  }

  @Test
  void analyze_keepsLargeRecurringRentOutOfOutliersAndExcludesIrregularMultiMonthMerchants() {
    LocalDate octStart = LocalDate.of(2026, 10, 1);
    LocalDate octEnd = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> octPeriod = List.of(
        tx(LocalDate.of(2026, 10, 2), "czynsz", "Czynsz", "-3000.00", FlowKind.EXPENSE, ExpenseCategory.HOUSING),
        tx(LocalDate.of(2026, 10, 4), "biedronka", "Biedronka", "-200.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 6), "apteka", "Apteka", "-100.00", FlowKind.EXPENSE, ExpenseCategory.HEALTH),
        tx(LocalDate.of(2026, 10, 8), "kawa", "Kawa", "-50.00", FlowKind.EXPENSE, ExpenseCategory.DINING),
        tx(LocalDate.of(2026, 10, 12), "laptop", "Laptop", "-4000.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING)
    );
    List<AnalyticsTransaction> rentHistory = List.of(
        tx(LocalDate.of(2026, 8, 2), "czynsz", "Czynsz", "-3000.00", FlowKind.EXPENSE, ExpenseCategory.HOUSING),
        tx(LocalDate.of(2026, 9, 2), "czynsz", "Czynsz", "-3000.00", FlowKind.EXPENSE, ExpenseCategory.HOUSING),
        octPeriod.get(0),
        octPeriod.get(1),
        octPeriod.get(2),
        octPeriod.get(3),
        octPeriod.get(4)
    );

    ExpenseAnalyticsResult rentResult = engine.analyze(octPeriod, rentHistory, "PLN", octStart, octEnd);
    assertThat(rentResult.recurringCharges()).hasSize(1);
    assertThat(rentResult.recurringCharges().get(0).merchantKey()).isEqualTo("czynsz");
    assertThat(rentResult.recurringCharges().get(0).annualizedCost()).isEqualByComparingTo("36000.00");
    assertThat(rentResult.outliers()).hasSize(1);
    assertThat(rentResult.outliers().get(0).merchantKey()).isEqualTo("laptop");
    assertThat(rentResult.kpis().oneOffOutliersTotal()).isEqualByComparingTo("4000.00");
    assertThat(rentResult.kpis().totalExpenses()).isEqualByComparingTo("7350.00");
    assertThat(rentResult.kpis().baseLivingExpenses()).isEqualByComparingTo("3350.00");

    List<AnalyticsTransaction> irregularPeriod = List.of(
        tx(LocalDate.of(2026, 10, 5), "spotify", "Spotify", "-20.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        tx(LocalDate.of(2026, 10, 1), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 10), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 20), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 15), "fryzjer", "Fryzjer", "-120.00", FlowKind.EXPENSE, ExpenseCategory.SERVICES_FEES),
        tx(LocalDate.of(2026, 10, 14), "restauracja", "Restauracja", "-350.00", FlowKind.EXPENSE, ExpenseCategory.DINING)
    );
    List<AnalyticsTransaction> irregularHistory = new java.util.ArrayList<>(List.of(
        tx(LocalDate.of(2026, 8, 5), "spotify", "Spotify", "-20.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        tx(LocalDate.of(2026, 9, 5), "spotify", "Spotify", "-20.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        tx(LocalDate.of(2026, 8, 10), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 9, 10), "zabka", "Żabka", "-25.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 8, 1), "fryzjer", "Fryzjer", "-120.00", FlowKind.EXPENSE, ExpenseCategory.SERVICES_FEES),
        tx(LocalDate.of(2026, 8, 14), "restauracja", "Restauracja", "-100.00", FlowKind.EXPENSE, ExpenseCategory.DINING),
        tx(LocalDate.of(2026, 9, 14), "restauracja", "Restauracja", "-100.00", FlowKind.EXPENSE, ExpenseCategory.DINING)
    ));
    irregularHistory.addAll(irregularPeriod);

    ExpenseAnalyticsResult irregularResult = engine.analyze(
        irregularPeriod, irregularHistory, "PLN", octStart, octEnd);
    assertThat(irregularResult.recurringCharges())
        .extracting(RecurringChargeItem::merchantKey)
        .containsExactly("spotify");
  }

  @Test
  void analyze_ignoresBareGatewayKeysInRecurringDetectionAndRefundMatching() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> period = List.of(
        tx(LocalDate.of(2026, 10, 10), "blik", "Fryzjer", "-100.00", FlowKind.EXPENSE, ExpenseCategory.SERVICES_FEES),
        tx(LocalDate.of(2026, 10, 12), "blik", "Dentystyka", "-1800.00", FlowKind.EXPENSE, ExpenseCategory.HEALTH),
        tx(LocalDate.of(2026, 10, 2), "payu", "Sklep A", "-200.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 5), "payu", "Zwrot Sklep B", "200.00", FlowKind.REFUND, ExpenseCategory.REFUND)
    );
    List<AnalyticsTransaction> history = new java.util.ArrayList<>(List.of(
        tx(LocalDate.of(2026, 8, 10), "blik", "Kwiaciarnia", "-100.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 9, 11), "blik", "Apteka Xyz", "-105.00", FlowKind.EXPENSE, ExpenseCategory.HEALTH)
    ));
    history.addAll(period);

    ExpenseAnalyticsResult result = engine.analyze(period, history, "PLN", start, end);

    assertThat(result.recurringCharges()).isEmpty();
    assertThat(result.outliers()).hasSize(1);
    assertThat(result.outliers().get(0).amount()).isEqualByComparingTo("1800.00");
    assertThat(result.kpis().totalRefundsNetted()).isEqualByComparingTo("0.00");
    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("2100.00");
  }

  @Test
  void analyze_doesNotDoubleNetPairedPeriodRefundIntoPriorMonthAverage() {
    LocalDate octStart = LocalDate.of(2026, 10, 1);
    LocalDate octEnd = LocalDate.of(2026, 10, 31);

    AnalyticsTransaction sepPurchase = tx(
        LocalDate.of(2026, 9, 28), "zalando", "Zalando", "-300.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    AnalyticsTransaction octPurchase = tx(
        LocalDate.of(2026, 10, 2), "zalando", "Zalando", "-300.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    AnalyticsTransaction octRefund = tx(
        LocalDate.of(2026, 10, 5), "zalando", "Zalando Zwrot", "300.00", FlowKind.REFUND, ExpenseCategory.REFUND);
    AnalyticsTransaction octOtherShopping = tx(
        LocalDate.of(2026, 10, 10), "allegro", "Allegro", "-600.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);

    List<AnalyticsTransaction> period = List.of(octPurchase, octRefund, octOtherShopping);
    List<AnalyticsTransaction> history = List.of(sepPurchase, octPurchase, octRefund, octOtherShopping);

    ExpenseAnalyticsResult result = engine.analyze(period, history, "PLN", octStart, octEnd);

    assertThat(result.kpis().totalRefundsNetted()).isEqualByComparingTo("300.00");
    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("600.00");

    CategoryBreakdownItem shopping = result.categoryBreakdown().stream()
        .filter(c -> c.category() == ExpenseCategory.SHOPPING)
        .findFirst()
        .orElseThrow();
    assertThat(shopping.amount()).isEqualByComparingTo("600.00");
    assertThat(shopping.previousPeriodAmount()).isEqualByComparingTo("300.00");
    assertThat(shopping.momChangePct()).isEqualByComparingTo("100.00");
    assertThat(result.insights()).anyMatch(i -> "CATEGORY_MOM_SPIKE".equals(i.code()));
  }

  @Test
  void analyze_netsExplicitCategoryRefundAcrossMerchantsAndEnforcesExact30DayBoundary() {
    LocalDate start = LocalDate.of(2026, 9, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> explicitCatTxs = List.of(
        tx(LocalDate.of(2026, 10, 2), "zalando", "Zalando", "-450.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 6), "payu:zwrot", "Payu Zwrot", "450.00", FlowKind.REFUND, ExpenseCategory.SHOPPING)
    );
    ExpenseAnalyticsResult explicitCatResult = engine.analyze(explicitCatTxs, explicitCatTxs, "PLN", start, end);
    assertThat(explicitCatResult.kpis().totalRefundsNetted()).isEqualByComparingTo("450.00");
    assertThat(explicitCatResult.kpis().totalExpenses()).isEqualByComparingTo("0.00");

    List<AnalyticsTransaction> day30Txs = List.of(
        tx(LocalDate.of(2026, 9, 1), "allegro", "Allegro", "-100.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 1), "allegro", "Allegro Zwrot", "100.00", FlowKind.REFUND, ExpenseCategory.REFUND)
    );
    ExpenseAnalyticsResult day30Result = engine.analyze(day30Txs, day30Txs, "PLN", start, end);
    assertThat(day30Result.kpis().totalRefundsNetted()).isEqualByComparingTo("100.00");
    assertThat(day30Result.kpis().totalExpenses()).isEqualByComparingTo("0.00");

    List<AnalyticsTransaction> day31Txs = List.of(
        tx(LocalDate.of(2026, 9, 1), "allegro", "Allegro", "-100.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 2), "allegro", "Allegro Zwrot", "100.00", FlowKind.REFUND, ExpenseCategory.REFUND)
    );
    ExpenseAnalyticsResult day31Result = engine.analyze(day31Txs, day31Txs, "PLN", start, end);
    assertThat(day31Result.kpis().totalRefundsNetted()).isEqualByComparingTo("0.00");
    assertThat(day31Result.kpis().totalExpenses()).isEqualByComparingTo("100.00");
  }

  @Test
  void analyze_appliesMedianMultiplierThresholdAbove500FloorForOutliers() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> txs = List.of(
        tx(LocalDate.of(2026, 10, 1), "sklep_1", "Sklep 1", "-300.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 2), "sklep_2", "Sklep 2", "-400.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 3), "sklep_3", "Sklep 3", "-400.00", FlowKind.EXPENSE, ExpenseCategory.DINING),
        tx(LocalDate.of(2026, 10, 4), "elektronika", "Elektronika", "-700.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING),
        tx(LocalDate.of(2026, 10, 5), "serwis_auto", "Serwis Auto", "-1000.00", FlowKind.EXPENSE, ExpenseCategory.TRANSPORT)
    );

    ExpenseAnalyticsResult result = engine.analyze(txs, txs, "PLN", start, end);

    assertThat(result.outliers()).hasSize(1);
    assertThat(result.outliers().get(0).merchantKey()).isEqualTo("serwis_auto");
    assertThat(result.outliers().get(0).amount()).isEqualByComparingTo("1000.00");
    assertThat(result.kpis().oneOffOutliersTotal()).isEqualByComparingTo("1000.00");
    assertThat(result.kpis().baseLivingExpenses()).isEqualByComparingTo("1800.00");
    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("2800.00");
  }

  @Test
  void analyze_treatsUtilitiesAndTelecomAsFixedCostsRecurringToleranceAndDrainExclusions() {
    LocalDate octStart = LocalDate.of(2026, 10, 1);
    LocalDate octEnd = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> period = List.of(
        tx(LocalDate.of(2026, 10, 1), "czynsz", "Czynsz", "-2000.00", FlowKind.EXPENSE, ExpenseCategory.HOUSING),
        tx(LocalDate.of(2026, 10, 5), "tauron", "Tauron", "-250.00", FlowKind.EXPENSE, ExpenseCategory.UTILITIES),
        tx(LocalDate.of(2026, 10, 8), "orange", "Orange", "-100.00", FlowKind.EXPENSE, ExpenseCategory.TELECOM),
        tx(LocalDate.of(2026, 10, 10), "netflix", "Netflix", "-50.00", FlowKind.EXPENSE, ExpenseCategory.SUBSCRIPTIONS),
        tx(LocalDate.of(2026, 10, 12), "biedronka", "Biedronka", "-250.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 10, 2), "parking_util", "Parking Util", "-20.00", FlowKind.EXPENSE, ExpenseCategory.UTILITIES),
        tx(LocalDate.of(2026, 10, 3), "parking_util", "Parking Util", "-20.00", FlowKind.EXPENSE, ExpenseCategory.UTILITIES),
        tx(LocalDate.of(2026, 10, 4), "parking_util", "Parking Util", "-20.00", FlowKind.EXPENSE, ExpenseCategory.UTILITIES),
        tx(LocalDate.of(2026, 10, 6), "parking_util", "Parking Util", "-20.00", FlowKind.EXPENSE, ExpenseCategory.UTILITIES),
        tx(LocalDate.of(2026, 10, 7), "parking_util", "Parking Util", "-20.00", FlowKind.EXPENSE, ExpenseCategory.UTILITIES),
        tx(LocalDate.of(2026, 10, 9), "parking_util", "Parking Util", "-20.00", FlowKind.EXPENSE, ExpenseCategory.UTILITIES)
    );

    List<AnalyticsTransaction> history = new java.util.ArrayList<>(List.of(
        tx(LocalDate.of(2026, 9, 5), "tauron", "Tauron", "-200.00", FlowKind.EXPENSE, ExpenseCategory.UTILITIES),
        tx(LocalDate.of(2026, 9, 8), "orange", "Orange", "-80.00", FlowKind.EXPENSE, ExpenseCategory.TELECOM),
        tx(LocalDate.of(2026, 9, 12), "biedronka", "Biedronka", "-200.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES)
    ));
    history.addAll(period);

    ExpenseAnalyticsResult result = engine.analyze(period, history, "PLN", octStart, octEnd);

    assertThat(result.kpis().fixedCostsTotal()).isEqualByComparingTo("2520.00");
    assertThat(result.kpis().variableCostsTotal()).isEqualByComparingTo("250.00");
    assertThat(result.recurringCharges())
        .extracting(RecurringChargeItem::merchantKey)
        .containsExactlyInAnyOrder("tauron", "orange");
    assertThat(result.insights())
        .noneMatch(i -> "CONVENIENCE_DRAIN".equals(i.code()));
  }

  @Test
  void analyze_enrichesMonthlyTrendPointWithCountsTopCategorySavingsRateAndMomChange() {
    List<AnalyticsTransaction> all = List.of(
        tx(LocalDate.of(2026, 8, 1), "pracodawca", "Wynagrodzenie", "5000.00", FlowKind.INCOME, ExpenseCategory.INCOME),
        tx(LocalDate.of(2026, 8, 5), "biedronka", "Biedronka", "-600.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES),
        tx(LocalDate.of(2026, 8, 8), "zalando", "Zalando", "-400.00", FlowKind.EXPENSE, ExpenseCategory.CLOTHING),
        tx(LocalDate.of(2026, 8, 12), "zalando", "Zalando Zwrot", "100.00", FlowKind.REFUND, ExpenseCategory.REFUND),
        tx(LocalDate.of(2026, 8, 15), "own", "Przelew wlasny", "-500.00", FlowKind.INTERNAL_TRANSFER, ExpenseCategory.INTERNAL_TRANSFER),
        new AnalyticsTransaction(
            UUID.randomUUID(), LocalDate.of(2026, 8, 20), "ignored", "Ignored",
            new BigDecimal("-999.00"), FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE, true, false, false),
        tx(LocalDate.of(2026, 9, 5), "tauron", "Tauron", "-900.00", FlowKind.EXPENSE, ExpenseCategory.UTILITIES),
        tx(LocalDate.of(2026, 9, 10), "orlen", "Orlen", "-450.00", FlowKind.EXPENSE, ExpenseCategory.FUEL),
        tx(LocalDate.of(2026, 10, 1), "pracodawca", "Wynagrodzenie", "4000.00", FlowKind.INCOME, ExpenseCategory.INCOME),
        tx(LocalDate.of(2026, 10, 5), "bistro", "Bistro", "-200.00", FlowKind.EXPENSE, ExpenseCategory.DINING),
        tx(LocalDate.of(2026, 10, 7), "bistro", "Bistro Zwrot", "200.00", FlowKind.REFUND, ExpenseCategory.REFUND),
        tx(LocalDate.of(2026, 11, 1), "pracodawca", "Wynagrodzenie", "4000.00", FlowKind.INCOME, ExpenseCategory.INCOME),
        tx(LocalDate.of(2026, 11, 5), "orange", "Orange", "-100.00", FlowKind.EXPENSE, ExpenseCategory.TELECOM),
        tx(LocalDate.of(2027, 1, 5), "orlen", "Orlen", "-200.00", FlowKind.EXPENSE, ExpenseCategory.FUEL)
    );

    ExpenseAnalyticsResult result = engine.analyze(
        all, all, "PLN", LocalDate.of(2026, 8, 1), LocalDate.of(2027, 1, 31));

    List<MonthlyTrendPoint> trend = result.monthlyTrend();
    assertThat(trend).hasSize(5);

    MonthlyTrendPoint aug = trend.get(0);
    assertThat(aug.month()).isEqualTo("2026-08");
    assertThat(aug.income()).isEqualByComparingTo("5000.00");
    assertThat(aug.expenses()).isEqualByComparingTo("900.00");
    assertThat(aug.netCashflow()).isEqualByComparingTo("4100.00");
    assertThat(aug.transactionCount()).isEqualTo(5);
    assertThat(aug.topCategory()).isEqualTo(ExpenseCategory.GROCERIES);
    assertThat(aug.topCategoryAmount()).isEqualByComparingTo("600.00");
    assertThat(aug.savingsRatePct()).isEqualByComparingTo("82.00");
    assertThat(aug.momExpensesChangePct()).isNull();

    MonthlyTrendPoint sep = trend.get(1);
    assertThat(sep.month()).isEqualTo("2026-09");
    assertThat(sep.income()).isEqualByComparingTo("0.00");
    assertThat(sep.expenses()).isEqualByComparingTo("1350.00");
    assertThat(sep.transactionCount()).isEqualTo(2);
    assertThat(sep.topCategory()).isEqualTo(ExpenseCategory.UTILITIES);
    assertThat(sep.topCategoryAmount()).isEqualByComparingTo("900.00");
    assertThat(sep.savingsRatePct()).isNull();
    assertThat(sep.momExpensesChangePct()).isEqualByComparingTo("50.00");

    MonthlyTrendPoint oct = trend.get(2);
    assertThat(oct.month()).isEqualTo("2026-10");
    assertThat(oct.expenses()).isEqualByComparingTo("0.00");
    assertThat(oct.transactionCount()).isEqualTo(3);
    assertThat(oct.topCategory()).isNull();
    assertThat(oct.topCategoryAmount()).isEqualByComparingTo("0.00");
    assertThat(oct.savingsRatePct()).isEqualByComparingTo("100.00");
    assertThat(oct.momExpensesChangePct()).isEqualByComparingTo("-100.00");

    MonthlyTrendPoint nov = trend.get(3);
    assertThat(nov.month()).isEqualTo("2026-11");
    assertThat(nov.transactionCount()).isEqualTo(2);
    assertThat(nov.topCategory()).isEqualTo(ExpenseCategory.TELECOM);
    assertThat(nov.topCategoryAmount()).isEqualByComparingTo("100.00");
    assertThat(nov.savingsRatePct()).isEqualByComparingTo("97.50");
    assertThat(nov.momExpensesChangePct()).isNull();

    MonthlyTrendPoint jan = trend.get(4);
    assertThat(jan.month()).isEqualTo("2027-01");
    assertThat(jan.transactionCount()).isEqualTo(1);
    assertThat(jan.topCategory()).isEqualTo(ExpenseCategory.FUEL);
    assertThat(jan.topCategoryAmount()).isEqualByComparingTo("200.00");
    assertThat(jan.savingsRatePct()).isNull();
    assertThat(jan.momExpensesChangePct()).isNull();
  }

  @Test
  void analyze_doesNotNetRefundAgainstFutureExpenseAfterRefundDate() {
    LocalDate start = LocalDate.of(2026, 10, 1);
    LocalDate end = LocalDate.of(2026, 10, 31);

    List<AnalyticsTransaction> txs = List.of(
        tx(LocalDate.of(2026, 10, 2), "allegro", "Allegro Zwrot", "100.00", FlowKind.REFUND, ExpenseCategory.REFUND),
        tx(LocalDate.of(2026, 10, 10), "allegro", "Allegro Zakup", "-100.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING)
    );

    ExpenseAnalyticsResult result = engine.analyze(txs, txs, "PLN", start, end);
    assertThat(result.kpis().totalRefundsNetted()).isEqualByComparingTo("0.00");
    assertThat(result.kpis().totalExpenses()).isEqualByComparingTo("100.00");
  }

  private static AnalyticsTransaction tx(
      LocalDate date,
      String merchantKey,
      String merchantDisplay,
      String baseAmount,
      FlowKind flowKind,
      ExpenseCategory category) {
    return new AnalyticsTransaction(
        UUID.randomUUID(),
        date,
        merchantKey,
        merchantDisplay,
        new BigDecimal(baseAmount),
        flowKind,
        category,
        false,
        false,
        false);
  }
}
