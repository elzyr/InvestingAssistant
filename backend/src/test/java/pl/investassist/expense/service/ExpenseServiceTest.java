package pl.investassist.expense.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import pl.investassist.auth.entity.User;
import pl.investassist.auth.enums.Role;
import pl.investassist.auth.repository.UserRepository;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.expense.analytics.ExpenseAnalyticsEngine;
import pl.investassist.expense.classifier.ExpenseClassifier;
import pl.investassist.expense.classifier.MerchantNormalizer;
import pl.investassist.expense.classifier.PiiScrubber;
import pl.investassist.expense.dto.request.CommitStatementRequest;
import pl.investassist.expense.dto.request.CommitTransactionItem;
import pl.investassist.expense.dto.request.ReclassifyExpensesRequest;
import pl.investassist.expense.dto.request.UpdateExpenseTransactionRequest;
import pl.investassist.expense.dto.response.ExpenseAnalyticsResponse;
import pl.investassist.expense.dto.response.ExpenseStatusResponse;
import pl.investassist.expense.dto.response.ExpenseTransactionResponse;
import pl.investassist.expense.dto.response.ReclassifyExpensesResponse;
import pl.investassist.expense.dto.response.StatementPreviewResponse;
import pl.investassist.expense.dto.response.StatementSummaryResponse;
import pl.investassist.expense.entity.ExpenseMerchantRule;
import pl.investassist.expense.entity.ExpenseStatement;
import pl.investassist.expense.entity.ExpenseTransaction;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;
import pl.investassist.expense.enums.ReconciliationStatus;
import pl.investassist.expense.enums.RuleMatchType;
import pl.investassist.expense.enums.StatementFormat;
import pl.investassist.expense.llm.ExpenseLlmPort;
import pl.investassist.expense.llm.LlmCategorySuggestion;
import pl.investassist.expense.llm.LlmClassificationContext;
import pl.investassist.expense.llm.LlmClassifyInput;
import pl.investassist.expense.parser.ParsedTransactionRow;
import pl.investassist.expense.parser.StatementIngestionService;
import pl.investassist.expense.parser.TransactionFingerprinter;
import pl.investassist.expense.repository.ExpenseMerchantRuleRepository;
import pl.investassist.expense.repository.ExpenseStatementRepository;
import pl.investassist.expense.repository.ExpenseTransactionRepository;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.portfolio.port.MarketDataPort;

@ExtendWith(MockitoExtension.class)
class ExpenseServiceTest {

  @Mock
  private UserRepository userRepository;
  @Mock
  private ExpenseStatementRepository statementRepository;
  @Mock
  private ExpenseTransactionRepository transactionRepository;
  @Mock
  private ExpenseMerchantRuleRepository ruleRepository;

  private StubMarketDataPort marketDataPort;
  private StubLlmPort llmPort;
  private ExpenseService expenseService;
  private UUID userId;

  @BeforeEach
  void setUp() {
    userId = UUID.randomUUID();
    marketDataPort = new StubMarketDataPort();
    llmPort = new StubLlmPort();

    TransactionFingerprinter fingerprinter = new TransactionFingerprinter();
    PiiScrubber piiScrubber = new PiiScrubber();
    MerchantNormalizer normalizer = new MerchantNormalizer();
    ExpenseClassifier classifier = new ExpenseClassifier(normalizer, piiScrubber, llmPort);
    StatementIngestionService ingestionService = new StatementIngestionService();
    ExpenseAnalyticsEngine analyticsEngine = new ExpenseAnalyticsEngine();

    expenseService = new ExpenseService(
        userRepository,
        statementRepository,
        transactionRepository,
        ruleRepository,
        ingestionService,
        fingerprinter,
        classifier,
        analyticsEngine,
        llmPort,
        marketDataPort);
  }

  @Test
  void previewStatement_convertsFxFlagsUnsupportedCurrencyAndCountsDuplicates() {
    stubUserPln();
    marketDataPort.withFx("EUR", "PLN", "4.30");
    String csv = String.join("\n",
        "Date,Description,Amount,Currency",
        "2026-10-05,SPOTIFY,-10.00,EUR",
        "2026-10-06,TOKYO SHOP,-100.00,JPY"
    );
    MockMultipartFile file = new MockMultipartFile(
        "file", "multi.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

    TransactionFingerprinter fpTool = new TransactionFingerprinter();
    String firstFp = fpTool.computeFingerprints(List.of(
        new ParsedTransactionRow(
            LocalDate.of(2026, 10, 5), LocalDate.of(2026, 10, 5), "SPOTIFY", new BigDecimal("-10.00"), "EUR"),
        new ParsedTransactionRow(
            LocalDate.of(2026, 10, 6), LocalDate.of(2026, 10, 6), "TOKYO SHOP", new BigDecimal("-100.00"), "JPY")
    )).get(0);

    when(transactionRepository.findExistingFingerprints(eq(userId), anyCollection()))
        .thenReturn(Set.of(firstFp));
    when(statementRepository.existsByUserIdAndFileHashSha256(eq(userId), any())).thenReturn(false);
    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

    StatementPreviewResponse preview =
        expenseService.previewStatement(userId, file, BankProfile.AUTO, false, "Multi");

    assertThat(preview.baseCurrency()).isEqualTo("PLN");
    assertThat(preview.duplicateCount()).isEqualTo(1);
    assertThat(preview.fxDegradedCount()).isEqualTo(1);
    assertThat(preview.transactions()).hasSize(2);

    assertThat(preview.transactions().get(0).baseAmount()).isEqualByComparingTo("-43.00");
    assertThat(preview.transactions().get(0).fxRate()).isEqualByComparingTo("4.30");
    assertThat(preview.transactions().get(0).fxDegraded()).isFalse();
    assertThat(preview.transactions().get(0).duplicate()).isTrue();

    assertThat(preview.transactions().get(1).fxDegraded()).isTrue();
    assertThat(preview.transactions().get(1).duplicate()).isFalse();
  }

  @Test
  void previewStatement_skipsZeroRowsAndKeepsTwinPaymentFromAnotherStatementPeriod() {
    String csv = String.join("\n",
        "Data,Opis,Kwota,Waluta",
        "2026-01-31,KIOSK,-10.00,PLN",
        "2026-01-31,CARD CHECK,0.00,PLN"
    );
    MockMultipartFile file = new MockMultipartFile(
        "file", "feb.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    String storedFp = new TransactionFingerprinter().computeFingerprints(List.of(
        new ParsedTransactionRow(
            LocalDate.of(2026, 1, 31), LocalDate.of(2026, 1, 31), "KIOSK", new BigDecimal("-10.00"), "PLN")
    )).get(0);

    when(transactionRepository.findFingerprintsOutsidePeriod(eq(userId), anyCollection(), any(), any()))
        .thenAnswer(inv -> inv.<java.util.Collection<String>>getArgument(1).contains(storedFp)
            ? Set.of(storedFp) : Set.of());
    when(transactionRepository.findExistingFingerprints(eq(userId), anyCollection())).thenReturn(Set.of());
    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

    StatementPreviewResponse preview =
        expenseService.previewStatement(userId, file, BankProfile.AUTO, false, null);

    assertThat(preview.transactions()).hasSize(1);
    assertThat(preview.transactions().get(0).duplicate()).isFalse();
    assertThat(preview.transactions().get(0).fingerprint()).isNotEqualTo(storedFp);
  }

  @Test
  void previewStatement_throwsNoTransactionsForHeaderOnlyCsvAndUnparseablePdf() {
    String headerOnlyCsv = "#Data operacji;#Opis operacji;#Kwota;#Saldo po operacji\n";
    MockMultipartFile csvFile = new MockMultipartFile(
        "file", "empty_rows.csv", "text/csv", headerOnlyCsv.getBytes(StandardCharsets.UTF_8));

    assertThatThrownBy(() -> expenseService.previewStatement(
        userId, csvFile, BankProfile.AUTO, false, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("error.expense_no_transactions");

    byte[] unparseablePdf = buildMinimalPdf14(List.of(
        "Opening balance: 100.00 PLN",
        "Account summary without parseable transaction lines"
    ));
    MockMultipartFile pdfFile = new MockMultipartFile(
        "file", "unparseable.pdf", "application/pdf", unparseablePdf);

    assertThatThrownBy(() -> expenseService.previewStatement(
        userId, pdfFile, BankProfile.AUTO, true, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("error.expense_no_transactions");
  }

  @Test
  void commitStatement_skipsDuplicateFingerprintsAndSavesPhraseRuleForGatewaysInSingleBatch() {
    when(transactionRepository.findExistingFingerprints(eq(userId), anyCollection()))
        .thenReturn(Set.of("fp-existing"));
    when(statementRepository.save(any(ExpenseStatement.class))).thenAnswer(inv -> {
      ExpenseStatement st = inv.getArgument(0);
      st.setId(UUID.randomUUID());
      return st;
    });

    LocalDate txDate = LocalDate.of(2026, 10, 8);
    CommitTransactionItem existing = commitItem("fp-existing", "biedronka", "Biedronka", null, "-50.00", ExpenseCategory.GROCERIES, false);
    CommitTransactionItem gatewayNew = commitItem("fp-new", "payu", "Kurs angielskiego", "kurs angielskiego", "-300.00", ExpenseCategory.EDUCATION, true);
    CommitTransactionItem batchDup = commitItem("fp-new", "payu", "Kurs angielskiego", "kurs angielskiego", "-300.00", ExpenseCategory.EDUCATION, false);

    CommitStatementRequest request = new CommitStatementRequest(
        "oct.csv",
        "hash-1",
        StatementFormat.CSV,
        BankProfile.MBANK,
        "Main",
        "PLN",
        txDate,
        txDate,
        new BigDecimal("1000.00"),
        new BigDecimal("700.00"),
        BigDecimal.ZERO,
        new BigDecimal("300.00"),
        ReconciliationStatus.VERIFIED,
        BigDecimal.ZERO,
        List.of(existing, gatewayNew, batchDup));

    StatementSummaryResponse summary = expenseService.commitStatement(userId, request);

    assertThat(summary.savedCount()).isEqualTo(1);
    assertThat(summary.skippedDuplicates()).isEqualTo(2);

    ArgumentCaptor<List<ExpenseTransaction>> txCaptor = ArgumentCaptor.forClass(List.class);
    verify(transactionRepository).saveAll(txCaptor.capture());
    assertThat(txCaptor.getValue()).hasSize(1);
    assertThat(txCaptor.getValue().get(0).getFingerprint()).isEqualTo("fp-new");

    verify(ruleRepository).upsertRule(
        any(UUID.class),
        eq(userId),
        eq("PHRASE"),
        eq("kurs angielskiego"),
        eq("Kurs angielskiego"),
        eq("EDUCATION"),
        eq("EXPENSE"),
        eq("USER_OVERRIDE"));
    verify(ruleRepository, never()).saveAll(any());
    verify(ruleRepository, never()).save(any());
  }

  @Test
  void updateTransaction_restrictsApplyToMatchingBySignAndSavesPhraseRuleForGateways() {
    UUID outflowId = UUID.randomUUID();
    ExpenseTransaction zalandoOutflow = entityTx(
        outflowId, userId, LocalDate.of(2026, 10, 2), "zalando", "Zalando", null,
        "-450.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);
    ExpenseTransaction otherOutflow = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 4), "zalando", "Zalando", null,
        "-120.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);
    ExpenseTransaction zalandoRefund = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 6), "zalando", "Zalando Zwrot", null,
        "450.00", FlowKind.REFUND, ExpenseCategory.REFUND);

    when(transactionRepository.findByIdAndUserId(outflowId, userId)).thenReturn(Optional.of(zalandoOutflow));
    when(transactionRepository.save(any(ExpenseTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    when(transactionRepository.findAllByUserIdAndMerchantKey(userId, "zalando"))
        .thenReturn(List.of(zalandoOutflow, otherOutflow, zalandoRefund));

    ExpenseTransactionResponse updated = expenseService.updateTransaction(
        userId,
        outflowId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.SHOPPING, FlowKind.EXPENSE, false, "Clothes", true, true));

    assertThat(updated.category()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(otherOutflow.getCategory()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(otherOutflow.getFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(otherOutflow.getClassificationSource()).isEqualTo(ClassificationSource.USER_RULE);

    assertThat(zalandoRefund.getCategory()).isEqualTo(ExpenseCategory.REFUND);
    assertThat(zalandoRefund.getFlowKind()).isEqualTo(FlowKind.REFUND);

    UUID gatewayTxId = UUID.randomUUID();
    ExpenseTransaction gatewayTx = entityTx(
        gatewayTxId, userId, LocalDate.of(2026, 10, 9), "payu", "Kurs", "kurs angielskiego",
        "-200.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);
    when(transactionRepository.findByIdAndUserId(gatewayTxId, userId)).thenReturn(Optional.of(gatewayTx));

    expenseService.updateTransaction(
        userId,
        gatewayTxId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.EDUCATION, FlowKind.EXPENSE, false, null, true, false));

    verify(ruleRepository).upsertRule(
        any(UUID.class),
        eq(userId),
        eq("MERCHANT"),
        eq("zalando"),
        eq("Zalando"),
        eq("SHOPPING"),
        eq("EXPENSE"),
        eq("USER_OVERRIDE"));
    verify(ruleRepository).upsertRule(
        any(UUID.class),
        eq(userId),
        eq("PHRASE"),
        eq("kurs angielskiego"),
        eq("Kurs"),
        eq("EDUCATION"),
        eq("EXPENSE"),
        eq("USER_OVERRIDE"));
    verify(ruleRepository, never()).save(any());
  }

  @Test
  void getAnalytics_includesMatchingRefundsWhenFilteringByExpenseCategory() {
    stubUserPln();
    LocalDate from = LocalDate.of(2026, 10, 1);
    LocalDate to = LocalDate.of(2026, 10, 31);
    ExpenseTransaction shopping = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 2), "zalando", "Zalando", null,
        "-450.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    ExpenseTransaction refund = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 6), "zalando", "Zalando Zwrot", null,
        "450.00", FlowKind.REFUND, ExpenseCategory.REFUND);

    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(refund, shopping));

    ExpenseAnalyticsResponse response = expenseService.getAnalytics(
        userId, from, to, null, ExpenseCategory.SHOPPING, true, "en");

    assertThat(response.kpis().totalExpenses()).isEqualByComparingTo("0.00");
    assertThat(response.kpis().totalRefundsNetted()).isEqualByComparingTo("450.00");
    assertThat(response.transactions()).hasSize(1);
    assertThat(response.transactions().get(0).category()).isEqualTo(ExpenseCategory.SHOPPING);
  }

  @Test
  void ownershipIsolation_throwsResourceNotFoundForOtherUsersEntities() {
    UUID intruderId = UUID.randomUUID();
    UUID foreignId = UUID.randomUUID();

    when(statementRepository.findByIdAndUserId(foreignId, intruderId)).thenReturn(Optional.empty());
    when(transactionRepository.findByIdAndUserId(foreignId, intruderId)).thenReturn(Optional.empty());
    when(ruleRepository.findByIdAndUserId(foreignId, intruderId)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> expenseService.deleteStatement(intruderId, foreignId))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> expenseService.updateTransaction(
        intruderId,
        foreignId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.GROCERIES, FlowKind.EXPENSE, false, null, false, false)))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> expenseService.deleteRule(intruderId, foreignId))
        .isInstanceOf(ResourceNotFoundException.class);
    assertThatThrownBy(() -> expenseService.getAnalytics(intruderId, null, null, foreignId, null, false, "en"))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void getAnalytics_filtersByStatementIdAndDateRangeAndSupportsOmittingTransactions() {
    stubUserPln();
    llmPort.enabled = true;

    UUID stmtA = UUID.randomUUID();
    UUID stmtB = UUID.randomUUID();
    LocalDate from = LocalDate.of(2026, 10, 1);
    LocalDate to = LocalDate.of(2026, 10, 31);

    ExpenseStatement statementA = new ExpenseStatement();
    statementA.setId(stmtA);
    when(statementRepository.findByIdAndUserId(stmtA, userId)).thenReturn(Optional.of(statementA));

    ExpenseTransaction inRangeStmtA = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 10), "biedronka", "Biedronka", null,
        "-200.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES);
    inRangeStmtA.setStatementId(stmtA);
    inRangeStmtA.setRawDescription("PLATNOSC KARTA BIEDRONKA PL61 1090 1014 0000 0712 1981 2874");

    ExpenseTransaction incomeStmtA = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 2), "pracodawca", "Wynagrodzenie", null,
        "5000.00", FlowKind.INCOME, ExpenseCategory.INCOME);
    incomeStmtA.setStatementId(stmtA);

    ExpenseTransaction beforeFromStmtA = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 9, 25), "lidl", "Lidl", null,
        "-150.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES);
    beforeFromStmtA.setStatementId(stmtA);

    ExpenseTransaction afterToStmtA = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 11, 2), "zabka", "Żabka", null,
        "-40.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES);
    afterToStmtA.setStatementId(stmtA);

    ExpenseTransaction inRangeStmtB = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 15), "allegro", "Allegro", null,
        "-999.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    inRangeStmtB.setStatementId(stmtB);

    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(afterToStmtA, inRangeStmtB, inRangeStmtA, incomeStmtA, beforeFromStmtA));

    ExpenseAnalyticsResponse response = expenseService.getAnalytics(
        userId, from, to, stmtA, null, true, "pl");

    assertThat(response.transactions()).hasSize(2);
    assertThat(response.transactions())
        .extracting(ExpenseTransactionResponse::id)
        .containsExactlyInAnyOrder(inRangeStmtA.getId(), incomeStmtA.getId());
    assertThat(response.kpis().totalIncome()).isEqualByComparingTo("5000.00");
    assertThat(response.kpis().totalExpenses()).isEqualByComparingTo("200.00");

    ExpenseAnalyticsResponse summaryOnly = expenseService.getAnalytics(
        userId, from, to, stmtA, null, false, "pl");
    assertThat(summaryOnly.transactions()).isEmpty();
    assertThat(summaryOnly.kpis().totalExpenses()).isEqualByComparingTo("200.00");
    assertThat(summaryOnly.monthlyTrend()).isNotEmpty();
  }

  @Test
  void getStatus_reflectsLlmConfiguration() {
    stubUserPln();
    llmPort.enabled = true;

    ExpenseStatusResponse status = expenseService.getStatus(userId);
    assertThat(status.aiEnabled()).isTrue();
    assertThat(status.aiProvider()).isEqualTo("ollama");
    assertThat(status.aiModel()).isEqualTo("tev1:4b");
    assertThat(status.baseCurrency()).isEqualTo("PLN");
  }

  @Test
  void updateTransaction_usesPhraseKeyForGatewayApplyToMatchingAndHandlesUserNotePreservationAndClearing() {
    UUID gatewayTxId = UUID.randomUUID();
    ExpenseTransaction gatewayTx = entityTx(
        gatewayTxId, userId, LocalDate.of(2026, 10, 9), "payu", "Kurs", "kurs angielskiego",
        "-200.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);
    gatewayTx.setUserNote("Saved note");

    ExpenseTransaction samePhrasePayu = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 12), "payu", "Kurs", "kurs angielskiego",
        "-250.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);
    ExpenseTransaction unrelatedPayu = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 13), "payu", "Sklep", "zakupy ubran",
        "-300.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);

    when(transactionRepository.findByIdAndUserId(gatewayTxId, userId)).thenReturn(Optional.of(gatewayTx));
    when(transactionRepository.save(any(ExpenseTransaction.class))).thenAnswer(inv -> inv.getArgument(0));
    when(transactionRepository.findAllByUserIdAndPhraseKey(userId, "kurs angielskiego"))
        .thenReturn(List.of(gatewayTx, samePhrasePayu));

    ExpenseTransactionResponse preservedNoteResp = expenseService.updateTransaction(
        userId,
        gatewayTxId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.EDUCATION, FlowKind.EXPENSE, false, null, true, true));

    assertThat(preservedNoteResp.userNote()).isEqualTo("Saved note");
    assertThat(samePhrasePayu.getCategory()).isEqualTo(ExpenseCategory.EDUCATION);
    assertThat(samePhrasePayu.getClassificationSource()).isEqualTo(ClassificationSource.USER_RULE);
    assertThat(unrelatedPayu.getCategory()).isEqualTo(ExpenseCategory.OTHER_EXPENSE);

    verify(ruleRepository).upsertRule(
        any(UUID.class),
        eq(userId),
        eq("PHRASE"),
        eq("kurs angielskiego"),
        eq("Kurs"),
        eq("EDUCATION"),
        eq("EXPENSE"),
        eq("USER_OVERRIDE"));
    verify(transactionRepository).findAllByUserIdAndPhraseKey(userId, "kurs angielskiego");
    verify(transactionRepository, never()).findAllByUserIdAndMerchantKey(eq(userId), any());

    ExpenseTransactionResponse clearedNoteResp = expenseService.updateTransaction(
        userId,
        gatewayTxId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.EDUCATION, FlowKind.EXPENSE, false, "", false, false));

    assertThat(clearedNoteResp.userNote()).isNull();
    assertThat(gatewayTx.getUserNote()).isNull();
  }

  @Test
  void updateTransaction_resolvesPositiveConsumptionCategoryAsRefundAndRejectsSignIncompatibleFlows() {
    UUID refundId = UUID.randomUUID();
    ExpenseTransaction refundTx = entityTx(
        refundId, userId, LocalDate.of(2026, 10, 6), "payu", "Payu Zwrot Sklep", "zwrot sklep",
        "450.00", FlowKind.REFUND, ExpenseCategory.REFUND);
    UUID outflowId = UUID.randomUUID();
    ExpenseTransaction outflowTx = entityTx(
        outflowId, userId, LocalDate.of(2026, 10, 2), "zalando", "Zalando", null,
        "-450.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);

    when(transactionRepository.findByIdAndUserId(refundId, userId)).thenReturn(Optional.of(refundTx));
    when(transactionRepository.findByIdAndUserId(outflowId, userId)).thenReturn(Optional.of(outflowTx));
    when(transactionRepository.save(any(ExpenseTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

    ExpenseTransactionResponse updatedRefund = expenseService.updateTransaction(
        userId,
        refundId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.SHOPPING, null, false, null, false, false));

    assertThat(updatedRefund.category()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(updatedRefund.flowKind()).isEqualTo(FlowKind.REFUND);
    assertThat(refundTx.getFlowKind()).isEqualTo(FlowKind.REFUND);

    stubUserPln();
    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(refundTx, outflowTx));

    ExpenseAnalyticsResponse analytics = expenseService.getAnalytics(
        userId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null, false, "en");
    assertThat(analytics.kpis().totalExpenses()).isEqualByComparingTo("0.00");
    assertThat(analytics.kpis().totalRefundsNetted()).isEqualByComparingTo("450.00");

    assertThatThrownBy(() -> expenseService.updateTransaction(
        userId,
        refundId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.SHOPPING, FlowKind.EXPENSE, false, null, false, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("error.bad_request");

    assertThatThrownBy(() -> expenseService.updateTransaction(
        userId,
        outflowId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.INCOME, FlowKind.INCOME, false, null, false, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("error.bad_request");

    assertThatThrownBy(() -> expenseService.updateTransaction(
        userId,
        outflowId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.REFUND, FlowKind.REFUND, false, null, false, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("error.bad_request");
  }

  @Test
  void commitStatement_resolvesPositiveConsumptionCategoryAsRefundAndRejectsSignIncompatibleFlows() {
    when(transactionRepository.findExistingFingerprints(eq(userId), anyCollection())).thenReturn(Set.of());
    when(statementRepository.save(any(ExpenseStatement.class))).thenAnswer(inv -> {
      ExpenseStatement st = inv.getArgument(0);
      st.setId(UUID.randomUUID());
      return st;
    });

    LocalDate txDate = LocalDate.of(2026, 10, 8);
    CommitTransactionItem positiveShoppingOmittedFlow = new CommitTransactionItem(
        txDate, txDate, "PAYU ZWROT", "payu", "Zwrot", "zwrot",
        new BigDecimal("450.00"), "PLN", new BigDecimal("450.00"), "PLN", BigDecimal.ONE, false,
        null, ExpenseCategory.SHOPPING, ClassificationSource.USER_OVERRIDE, BigDecimal.ONE,
        "fp-pos-shopping", false, false, null);

    CommitStatementRequest validRequest = new CommitStatementRequest(
        "oct.csv", "hash-valid", StatementFormat.CSV, BankProfile.GENERIC, null, "PLN",
        txDate, txDate, null, null, new BigDecimal("450.00"), BigDecimal.ZERO,
        ReconciliationStatus.UNAVAILABLE, null, List.of(positiveShoppingOmittedFlow));

    expenseService.commitStatement(userId, validRequest);

    ArgumentCaptor<List<ExpenseTransaction>> txCaptor = ArgumentCaptor.forClass(List.class);
    verify(transactionRepository).saveAll(txCaptor.capture());
    assertThat(txCaptor.getValue()).hasSize(1);
    assertThat(txCaptor.getValue().get(0).getCategory()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(txCaptor.getValue().get(0).getFlowKind()).isEqualTo(FlowKind.REFUND);

    CommitTransactionItem positiveExpenseFlow = new CommitTransactionItem(
        txDate, txDate, "PAYU ZWROT", "payu", "Zwrot", "zwrot",
        new BigDecimal("450.00"), "PLN", new BigDecimal("450.00"), "PLN", BigDecimal.ONE, false,
        FlowKind.EXPENSE, ExpenseCategory.SHOPPING, ClassificationSource.USER_OVERRIDE, BigDecimal.ONE,
        "fp-bad-pos", false, false, null);

    assertThatThrownBy(() -> expenseService.commitStatement(
        userId,
        new CommitStatementRequest(
            "bad.csv", "hash-bad-1", StatementFormat.CSV, BankProfile.GENERIC, null, "PLN",
            txDate, txDate, null, null, null, null, ReconciliationStatus.UNAVAILABLE, null,
            List.of(positiveExpenseFlow))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("error.bad_request");

    CommitTransactionItem negativeIncomeFlow = commitItem(
        "fp-bad-neg", "sklep", "Sklep", null, "-500.00", ExpenseCategory.INCOME, false);

    assertThatThrownBy(() -> expenseService.commitStatement(
        userId,
        new CommitStatementRequest(
            "bad2.csv", "hash-bad-2", StatementFormat.CSV, BankProfile.GENERIC, null, "PLN",
            txDate, txDate, null, null, null, null, ReconciliationStatus.UNAVAILABLE, null,
            List.of(negativeIncomeFlow))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("error.bad_request");
  }

  @Test
  void getAnalytics_scopesGatewayTransactionsByPhraseKeySoUnrelatedBlikPaymentsDoNotMerge() {
    stubUserPln();
    LocalDate from = LocalDate.of(2026, 10, 1);
    LocalDate to = LocalDate.of(2026, 10, 31);

    ExpenseTransaction augFlowers = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 8, 10), "blik", "Kwiaciarnia", "kwiaciarnia",
        "-100.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    ExpenseTransaction sepPharmacy = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 9, 11), "blik", "Apteka Xyz", "apteka xyz",
        "-105.00", FlowKind.EXPENSE, ExpenseCategory.HEALTH);
    ExpenseTransaction octBarber = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 10), "blik", "Fryzjer", "fryzjer",
        "-100.00", FlowKind.EXPENSE, ExpenseCategory.SERVICES_FEES);
    ExpenseTransaction octDentist = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 12), "blik", "Dentystyka", "dentystyka",
        "-1800.00", FlowKind.EXPENSE, ExpenseCategory.HEALTH);

    ExpenseTransaction augCourse = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 8, 5), "payu", "Kurs Angielskiego", "kurs angielskiego",
        "-200.00", FlowKind.EXPENSE, ExpenseCategory.EDUCATION);
    ExpenseTransaction sepCourse = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 9, 5), "payu", "Kurs Angielskiego", "kurs angielskiego",
        "-200.00", FlowKind.EXPENSE, ExpenseCategory.EDUCATION);
    ExpenseTransaction octCourse = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 5), "payu", "Kurs Angielskiego", "kurs angielskiego",
        "-200.00", FlowKind.EXPENSE, ExpenseCategory.EDUCATION);

    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(octDentist, octBarber, octCourse, sepPharmacy, sepCourse, augFlowers, augCourse));

    ExpenseAnalyticsResponse response = expenseService.getAnalytics(
        userId, from, to, null, null, true, "en");

    assertThat(response.recurringCharges()).hasSize(1);
    assertThat(response.recurringCharges().get(0).merchantKey()).isEqualTo("payu:kurs angielskiego");
    assertThat(response.recurringCharges().get(0).merchantDisplay()).isEqualTo("Kurs Angielskiego");

    assertThat(response.outliers()).hasSize(1);
    assertThat(response.outliers().get(0).merchantDisplay()).isEqualTo("Dentystyka");
    assertThat(response.outliers().get(0).amount()).isEqualByComparingTo("1800.00");
    assertThat(response.kpis().oneOffOutliersTotal()).isEqualByComparingTo("1800.00");
    assertThat(response.kpis().baseLivingExpenses()).isEqualByComparingTo("300.00");
    assertThat(response.kpis().fixedCostsTotal()).isEqualByComparingTo("200.00");

    assertThat(response.topMerchants())
        .extracting(m -> m.merchantKey())
        .containsExactly("blik:dentystyka", "payu:kurs angielskiego", "blik:fryzjer");

    Map<UUID, Boolean> recurringById = response.transactions().stream()
        .collect(java.util.stream.Collectors.toMap(ExpenseTransactionResponse::id, ExpenseTransactionResponse::isRecurring));
    assertThat(recurringById.get(octCourse.getId())).isTrue();
    assertThat(recurringById.get(octBarber.getId())).isFalse();
    assertThat(recurringById.get(octDentist.getId())).isFalse();
  }

  @Test
  void updateTransactionAndCommit_keepConsumptionRuleFlowAsExpenseWhenSavingRefundWithRememberRule() {
    ExpenseMerchantRule existingRule = new ExpenseMerchantRule();
    existingRule.setUserId(userId);
    existingRule.setMatchType(RuleMatchType.MERCHANT);
    existingRule.setMatchKey("sklep_alfa");
    existingRule.setDisplayLabel("Sklep Alfa");
    existingRule.setCategory(ExpenseCategory.SHOPPING);
    existingRule.setFlowKind(FlowKind.EXPENSE);
    existingRule.setSource(ClassificationSource.USER_OVERRIDE.name());
    existingRule.setHitCount(1);

    UUID refundId = UUID.randomUUID();
    ExpenseTransaction refundTx = entityTx(
        refundId, userId, LocalDate.of(2026, 10, 6), "sklep_alfa", "Sklep Alfa", null,
        "200.00", FlowKind.REFUND, ExpenseCategory.REFUND);

    when(transactionRepository.findByIdAndUserId(refundId, userId)).thenReturn(Optional.of(refundTx));
    when(transactionRepository.save(any(ExpenseTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

    ExpenseTransactionResponse updated = expenseService.updateTransaction(
        userId,
        refundId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.SHOPPING, null, false, null, true, false));

    assertThat(updated.category()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(updated.flowKind()).isEqualTo(FlowKind.REFUND);
    assertThat(existingRule.getFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(existingRule.getCategory()).isEqualTo(ExpenseCategory.SHOPPING);

    stubUserPln();
    String csv = String.join("\n",
        "Data;Opis;Kwota;Waluta",
        "2026-10-10;SKLEP ALFA;-300,00;PLN",
        "2026-10-12;SKLEP ALFA ZWROT;100,00;PLN"
    );
    MockMultipartFile file = new MockMultipartFile(
        "file", "next.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    when(transactionRepository.findExistingFingerprints(eq(userId), anyCollection())).thenReturn(Set.of());
    when(statementRepository.existsByUserIdAndFileHashSha256(eq(userId), any())).thenReturn(false);
    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(existingRule));

    StatementPreviewResponse preview =
        expenseService.previewStatement(userId, file, BankProfile.AUTO, false, null);

    assertThat(preview.transactions()).hasSize(2);
    assertThat(preview.transactions().get(0).category()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(preview.transactions().get(0).flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(preview.transactions().get(0).classificationSource()).isEqualTo(ClassificationSource.USER_RULE);
    assertThat(preview.transactions().get(0).needsReview()).isFalse();

    assertThat(preview.transactions().get(1).category()).isEqualTo(ExpenseCategory.REFUND);
    assertThat(preview.transactions().get(1).flowKind()).isEqualTo(FlowKind.REFUND);
    assertThat(preview.transactions().get(1).classificationSource()).isEqualTo(ClassificationSource.RULE_ENGINE);

    when(statementRepository.save(any(ExpenseStatement.class))).thenAnswer(inv -> {
      ExpenseStatement st = inv.getArgument(0);
      st.setId(UUID.randomUUID());
      return st;
    });

    LocalDate txDate = LocalDate.of(2026, 10, 14);
    CommitTransactionItem newConsumptionRefund = new CommitTransactionItem(
        txDate, txDate, "NOWY BUTIK ZWROT", "nowy_butik", "Nowy Butik", null,
        new BigDecimal("100.00"), "PLN", new BigDecimal("100.00"), "PLN", BigDecimal.ONE, false,
        null, ExpenseCategory.SHOPPING, ClassificationSource.USER_OVERRIDE, BigDecimal.ONE,
        "fp-new-butik-refund", false, true, null);
    CommitTransactionItem plainRefundRuleIgnored = new CommitTransactionItem(
        txDate, txDate, "SKLEP BETA ZWROT", "sklep_beta", "Sklep Beta", null,
        new BigDecimal("50.00"), "PLN", new BigDecimal("50.00"), "PLN", BigDecimal.ONE, false,
        FlowKind.REFUND, ExpenseCategory.REFUND, ClassificationSource.USER_OVERRIDE, BigDecimal.ONE,
        "fp-plain-refund", false, true, null);

    expenseService.commitStatement(
        userId,
        new CommitStatementRequest(
            "refunds.csv", "hash-refunds", StatementFormat.CSV, BankProfile.GENERIC, null, "PLN",
            txDate, txDate, null, null, new BigDecimal("150.00"), BigDecimal.ZERO,
            ReconciliationStatus.UNAVAILABLE, null, List.of(newConsumptionRefund, plainRefundRuleIgnored)));

    verify(ruleRepository).upsertRule(
        any(UUID.class),
        eq(userId),
        eq("MERCHANT"),
        eq("sklep_alfa"),
        eq("Sklep Alfa"),
        eq("SHOPPING"),
        eq("EXPENSE"),
        eq("USER_OVERRIDE"));
    verify(ruleRepository).upsertRule(
        any(UUID.class),
        eq(userId),
        eq("MERCHANT"),
        eq("nowy_butik"),
        eq("Nowy Butik"),
        eq("SHOPPING"),
        eq("EXPENSE"),
        eq("USER_OVERRIDE"));
    verify(ruleRepository, never()).upsertRule(
        any(), any(), any(), eq("sklep_beta"), any(), any(), any(), any());
    verify(ruleRepository, never()).save(any());
    verify(ruleRepository, never()).saveAll(any());
  }

  @Test
  void previewStatement_appliesPersistedUserRulesAndMarksFxDegradedWhenMarketDataThrows() {
    stubUserPln();
    marketDataPort.throwOnFx = true;

    ExpenseMerchantRule persistedRule = new ExpenseMerchantRule();
    persistedRule.setUserId(userId);
    persistedRule.setMatchType(RuleMatchType.MERCHANT);
    persistedRule.setMatchKey("lokalny_warzywniak");
    persistedRule.setDisplayLabel("Lokalny Warzywniak");
    persistedRule.setCategory(ExpenseCategory.GROCERIES);
    persistedRule.setFlowKind(FlowKind.EXPENSE);
    persistedRule.setSource(ClassificationSource.USER_OVERRIDE.name());
    persistedRule.setHitCount(3);

    String csv = String.join("\n",
        "Data;Opis;Kwota;Waluta",
        "2026-10-05;LOKALNY WARZYWNIAK;-45,00;EUR"
    );
    MockMultipartFile file = new MockMultipartFile(
        "file", "fx_fail.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

    when(transactionRepository.findExistingFingerprints(eq(userId), anyCollection())).thenReturn(Set.of());
    when(statementRepository.existsByUserIdAndFileHashSha256(eq(userId), any())).thenReturn(false);
    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(persistedRule));

    StatementPreviewResponse preview =
        expenseService.previewStatement(userId, file, BankProfile.AUTO, false, null);

    assertThat(preview.fxDegradedCount()).isEqualTo(1);
    assertThat(preview.needsReviewCount()).isZero();
    assertThat(preview.transactions()).hasSize(1);
    assertThat(preview.transactions().get(0).category()).isEqualTo(ExpenseCategory.GROCERIES);
    assertThat(preview.transactions().get(0).flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(preview.transactions().get(0).classificationSource()).isEqualTo(ClassificationSource.USER_RULE);
    assertThat(preview.transactions().get(0).needsReview()).isFalse();
    assertThat(preview.transactions().get(0).fxDegraded()).isTrue();
    assertThat(preview.transactions().get(0).baseAmount()).isEqualByComparingTo("-45.00");

    UUID txId = UUID.randomUUID();
    ExpenseTransaction tx = entityTx(
        txId, userId, LocalDate.of(2026, 10, 5), "lokalny_warzywniak", "Lokalny Warzywniak", null,
        "-45.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES);
    when(transactionRepository.findByIdAndUserId(txId, userId)).thenReturn(Optional.of(tx));
    when(transactionRepository.save(any(ExpenseTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

    expenseService.updateTransaction(
        userId,
        txId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.HEALTH, FlowKind.EXPENSE, false, null, true, false));

    verify(ruleRepository).upsertRule(
        any(UUID.class),
        eq(userId),
        eq("MERCHANT"),
        eq("lokalny_warzywniak"),
        eq("Lokalny Warzywniak"),
        eq("HEALTH"),
        eq("EXPENSE"),
        eq("USER_OVERRIDE"));
  }

  @Test
  void previewStatement_forwardsOwnerFullNameForSelfTransferDetectionAndLlmContext() {
    stubUserPln();
    llmPort.enabled = true;

    String csv = String.join("\n",
        "Data;Opis;Kwota;Waluta",
        "2026-10-10;Jan Kowalski ., /OPF/AN/PL00102030400000000000000000/Jan Kowalski/1/.PL/Przelew na telefon BLIK;100,00;PLN",
        "2026-10-11;JAN SKLEP SPOZYWCZY;-40,00;PLN",
        "2026-10-12;NIEZNANY PUNKT XYZ;-75,00;PLN"
    );
    MockMultipartFile file = new MockMultipartFile(
        "file", "mbank_self.csv", "text/csv", csv.getBytes(StandardCharsets.UTF_8));

    when(transactionRepository.findExistingFingerprints(eq(userId), anyCollection())).thenReturn(Set.of());
    when(statementRepository.existsByUserIdAndFileHashSha256(eq(userId), any())).thenReturn(false);
    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());

    StatementPreviewResponse preview =
        expenseService.previewStatement(userId, file, BankProfile.AUTO, true, null);

    assertThat(preview.transactions()).hasSize(3);

    assertThat(preview.transactions().get(0).flowKind()).isEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(preview.transactions().get(0).category()).isEqualTo(ExpenseCategory.INTERNAL_TRANSFER);
    assertThat(preview.transactions().get(0).classificationSource()).isEqualTo(ClassificationSource.RULE_ENGINE);
    assertThat(preview.transactions().get(0).needsReview()).isFalse();

    assertThat(preview.transactions().get(1).flowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(preview.transactions().get(1).category()).isEqualTo(ExpenseCategory.GROCERIES);

    assertThat(llmPort.capturedContext).isNotNull();
    assertThat(llmPort.capturedContext.accountOwnerName()).isEqualTo("Jan Kowalski");
  }

  @Test
  void updateTransaction_persistsMerchantRuleForGoogleKey() {
    UUID txId = UUID.randomUUID();
    ExpenseTransaction googleTx = entityTx(
        txId, userId, LocalDate.of(2026, 10, 15), "google", "Google", "google",
        "-37.99", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);

    when(transactionRepository.findByIdAndUserId(txId, userId)).thenReturn(Optional.of(googleTx));
    when(transactionRepository.save(any(ExpenseTransaction.class))).thenAnswer(inv -> inv.getArgument(0));

    expenseService.updateTransaction(
        userId,
        txId,
        new UpdateExpenseTransactionRequest(ExpenseCategory.SUBSCRIPTIONS, FlowKind.EXPENSE, false, null, true, false));

    verify(ruleRepository).upsertRule(
        any(UUID.class),
        eq(userId),
        eq("MERCHANT"),
        eq("google"),
        eq("Google"),
        eq("SUBSCRIPTIONS"),
        eq("EXPENSE"),
        eq("USER_OVERRIDE"));
  }

  @Test
  void reclassifyTransactions_updatesEligibleRowsPreservesUserOverridesAndRespectsFilters() {
    stubUserPln();
    llmPort.enabled = true;
    UUID stmtA = UUID.randomUUID();
    UUID stmtB = UUID.randomUUID();

    ExpenseStatement statementA = new ExpenseStatement();
    statementA.setId(stmtA);
    when(statementRepository.findByIdAndUserId(stmtA, userId)).thenReturn(Optional.of(statementA));

    ExpenseTransaction outdatedZalando = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 5), "zalando", "Zalando", "zalando",
        "-300.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    outdatedZalando.setStatementId(stmtA);
    outdatedZalando.setRawDescription("ZALANDO SE");

    ExpenseTransaction manualZalando = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 6), "zalando", "Zalando", "zalando",
        "-150.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    manualZalando.setStatementId(stmtA);
    manualZalando.setRawDescription("ZALANDO SE");
    manualZalando.setClassificationSource(ClassificationSource.USER_OVERRIDE);

    ExpenseTransaction excludedTx = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 7), "zalando", "Zalando", "zalando",
        "-99.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    excludedTx.setStatementId(stmtA);
    excludedTx.setRawDescription("ZALANDO SE");
    excludedTx.setExcluded(true);

    ExpenseTransaction unchangedBiedronka = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 8), "biedronka", "Biedronka", "biedronka",
        "-85.00", FlowKind.EXPENSE, ExpenseCategory.GROCERIES);
    unchangedBiedronka.setStatementId(stmtA);
    unchangedBiedronka.setRawDescription("BIEDRONKA");

    UUID unknownId = UUID.randomUUID();
    ExpenseTransaction unknownStudio = entityTx(
        unknownId, userId, LocalDate.of(2026, 10, 9), "unknown_studio", "Unknown Studio", "unknown studio",
        "-120.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);
    unknownStudio.setStatementId(stmtA);
    unknownStudio.setRawDescription("STUDIO FRYZJERSKIE XYZ");
    unknownStudio.setClassificationSource(ClassificationSource.FALLBACK);

    ExpenseTransaction outOfDateRange = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 9, 25), "orlen", "Orlen", "orlen",
        "-200.00", FlowKind.EXPENSE, ExpenseCategory.TRANSPORT);
    outOfDateRange.setStatementId(stmtA);
    outOfDateRange.setRawDescription("PKN ORLEN");

    ExpenseTransaction afterToDateRange = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 11, 1), "orlen", "Orlen", "orlen",
        "-210.00", FlowKind.EXPENSE, ExpenseCategory.TRANSPORT);
    afterToDateRange.setStatementId(stmtA);
    afterToDateRange.setRawDescription("PKN ORLEN");

    ExpenseTransaction otherStatement = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 10), "orlen", "Orlen", "orlen",
        "-200.00", FlowKind.EXPENSE, ExpenseCategory.TRANSPORT);
    otherStatement.setStatementId(stmtB);
    otherStatement.setRawDescription("PKN ORLEN");

    llmPort.suggestions = Map.of(
        unknownId.toString(),
        new LlmCategorySuggestion(
            ExpenseCategory.BEAUTY_CARE, FlowKind.EXPENSE, "Studio XYZ", new BigDecimal("0.91")));

    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId)).thenReturn(List.of(
        outdatedZalando, manualZalando, excludedTx, unchangedBiedronka, unknownStudio,
        outOfDateRange, afterToDateRange, otherStatement));

    ReclassifyExpensesResponse response = expenseService.reclassifyTransactions(
        userId,
        new ReclassifyExpensesRequest(
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), stmtA, false));

    assertThat(response.processedCount()).isEqualTo(3);
    assertThat(response.updatedCount()).isEqualTo(2);
    assertThat(response.aiUsed()).isTrue();
    assertThat(response.aiWarning()).isNull();

    assertThat(outdatedZalando.getCategory()).isEqualTo(ExpenseCategory.CLOTHING);
    assertThat(outdatedZalando.getClassificationSource()).isEqualTo(ClassificationSource.RULE_ENGINE);
    assertThat(unknownStudio.getCategory()).isEqualTo(ExpenseCategory.BEAUTY_CARE);
    assertThat(unknownStudio.getClassificationSource()).isEqualTo(ClassificationSource.LLM);
    assertThat(unknownStudio.getMerchantDisplay()).isEqualTo("Studio XYZ");

    assertThat(manualZalando.getCategory()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(manualZalando.getClassificationSource()).isEqualTo(ClassificationSource.USER_OVERRIDE);
    assertThat(excludedTx.getCategory()).isEqualTo(ExpenseCategory.SHOPPING);
    assertThat(outOfDateRange.getCategory()).isEqualTo(ExpenseCategory.TRANSPORT);
    assertThat(afterToDateRange.getCategory()).isEqualTo(ExpenseCategory.TRANSPORT);
    assertThat(otherStatement.getCategory()).isEqualTo(ExpenseCategory.TRANSPORT);

    ArgumentCaptor<List<ExpenseTransaction>> savedCaptor = ArgumentCaptor.forClass(List.class);
    verify(transactionRepository).saveAll(savedCaptor.capture());
    assertThat(savedCaptor.getValue()).containsExactlyInAnyOrder(outdatedZalando, unknownStudio);
  }

  @Test
  void reclassifyTransactions_includesManualOverridesWhenRequestedHandlesEmptyAndRejectsForeignStatement() {
    stubUserPln();
    llmPort.enabled = true;
    llmPort.throwOnClassify = true;

    ExpenseTransaction manualOrlen = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 5), "orlen", "Orlen", "orlen",
        "-250.00", FlowKind.EXPENSE, ExpenseCategory.TRANSPORT);
    manualOrlen.setRawDescription("PKN ORLEN");
    manualOrlen.setClassificationSource(ClassificationSource.USER_OVERRIDE);

    ExpenseTransaction unknownTx = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 6), "tajemniczy_sklep", "Tajemniczy Sklep", "tajemniczy sklep",
        "-50.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);
    unknownTx.setRawDescription("TAJEMNICZY SKLEP QZX");
    unknownTx.setClassificationSource(ClassificationSource.FALLBACK);

    ExpenseTransaction manualUnknown = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 7), "tajemniczy_sklep_qzx", "Tajemniczy Sklep Qzx", "tajemniczy sklep qzx",
        "-180.00", FlowKind.EXPENSE, ExpenseCategory.CLOTHING);
    manualUnknown.setRawDescription("TAJEMNICZY SKLEP QZX");
    manualUnknown.setClassificationSource(ClassificationSource.USER_OVERRIDE);
    manualUnknown.setConfidence(new BigDecimal("1.00"));

    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(manualOrlen, unknownTx, manualUnknown));

    ReclassifyExpensesResponse overrideRes = expenseService.reclassifyTransactions(
        userId, new ReclassifyExpensesRequest(null, null, null, true));

    assertThat(overrideRes.processedCount()).isEqualTo(3);
    assertThat(overrideRes.updatedCount()).isEqualTo(1);
    assertThat(overrideRes.aiUsed()).isFalse();
    assertThat(overrideRes.aiWarning()).isNotBlank();
    assertThat(manualOrlen.getCategory()).isEqualTo(ExpenseCategory.FUEL);
    assertThat(manualOrlen.getClassificationSource()).isEqualTo(ClassificationSource.RULE_ENGINE);
    assertThat(manualUnknown.getCategory()).isEqualTo(ExpenseCategory.CLOTHING);
    assertThat(manualUnknown.getFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(manualUnknown.getClassificationSource()).isEqualTo(ClassificationSource.USER_OVERRIDE);
    assertThat(manualUnknown.getConfidence()).isEqualByComparingTo("1.00");

    ArgumentCaptor<List<ExpenseTransaction>> savedCaptor = ArgumentCaptor.forClass(List.class);
    verify(transactionRepository).saveAll(savedCaptor.capture());
    assertThat(savedCaptor.getValue()).containsExactly(manualOrlen);

    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId)).thenReturn(List.of());
    ReclassifyExpensesResponse emptyRes = expenseService.reclassifyTransactions(userId, null);
    assertThat(emptyRes.processedCount()).isZero();
    assertThat(emptyRes.updatedCount()).isZero();
    assertThat(emptyRes.aiUsed()).isFalse();

    UUID foreignStmt = UUID.randomUUID();
    when(statementRepository.findByIdAndUserId(foreignStmt, userId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> expenseService.reclassifyTransactions(
        userId, new ReclassifyExpensesRequest(null, null, foreignStmt, false)))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  @Test
  void reclassifyTransactions_preservesPriorLlmClassificationWhenAiDisabledOrReturnsNoSuggestion() {
    stubUserPln();
    llmPort.enabled = false;

    ExpenseTransaction priorLlmWhenDisabled = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 11), "studio_xyz", "Studio XYZ", "studio xyz",
        "-120.00", FlowKind.EXPENSE, ExpenseCategory.BEAUTY_CARE);
    priorLlmWhenDisabled.setRawDescription("STUDIO FRYZJERSKIE XYZ");
    priorLlmWhenDisabled.setClassificationSource(ClassificationSource.LLM);
    priorLlmWhenDisabled.setConfidence(new BigDecimal("0.91"));

    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(priorLlmWhenDisabled));

    ReclassifyExpensesResponse disabledRes = expenseService.reclassifyTransactions(userId, null);

    assertThat(disabledRes.processedCount()).isEqualTo(1);
    assertThat(disabledRes.updatedCount()).isZero();
    assertThat(disabledRes.aiUsed()).isFalse();
    assertThat(disabledRes.aiWarning()).isNull();
    assertThat(priorLlmWhenDisabled.getCategory()).isEqualTo(ExpenseCategory.BEAUTY_CARE);
    assertThat(priorLlmWhenDisabled.getFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(priorLlmWhenDisabled.getClassificationSource()).isEqualTo(ClassificationSource.LLM);
    assertThat(priorLlmWhenDisabled.getConfidence()).isEqualByComparingTo("0.91");

    llmPort.enabled = true;
    llmPort.suggestions = Map.of();

    ExpenseTransaction priorLlmNoSuggestion = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 12), "atelier_xyz", "Atelier XYZ", "atelier xyz",
        "-150.00", FlowKind.EXPENSE, ExpenseCategory.GIFTS);
    priorLlmNoSuggestion.setRawDescription("ATELIER ARTYSTYCZNE XYZ");
    priorLlmNoSuggestion.setClassificationSource(ClassificationSource.LLM);
    priorLlmNoSuggestion.setConfidence(new BigDecimal("0.85"));

    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(priorLlmNoSuggestion));

    ReclassifyExpensesResponse noSuggestionRes = expenseService.reclassifyTransactions(userId, null);

    assertThat(noSuggestionRes.processedCount()).isEqualTo(1);
    assertThat(noSuggestionRes.updatedCount()).isZero();
    assertThat(noSuggestionRes.aiUsed()).isTrue();
    assertThat(noSuggestionRes.aiWarning()).isNull();
    assertThat(priorLlmNoSuggestion.getCategory()).isEqualTo(ExpenseCategory.GIFTS);
    assertThat(priorLlmNoSuggestion.getFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(priorLlmNoSuggestion.getClassificationSource()).isEqualTo(ClassificationSource.LLM);
    assertThat(priorLlmNoSuggestion.getConfidence()).isEqualByComparingTo("0.85");

    verify(transactionRepository, never()).saveAll(any());
  }

  @Test
  void reclassifyTransactions_preservesManualOverrideOnUnknownMerchantWhenAiDisabled() {
    stubUserPln();
    llmPort.enabled = false;

    ExpenseTransaction manualOrlen = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 5), "orlen", "Orlen", "orlen",
        "-250.00", FlowKind.EXPENSE, ExpenseCategory.TRANSPORT);
    manualOrlen.setRawDescription("PKN ORLEN");
    manualOrlen.setClassificationSource(ClassificationSource.USER_OVERRIDE);

    ExpenseTransaction manualUnknown = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 7), "tajemniczy_sklep_qzx", "Tajemniczy Sklep Qzx", "tajemniczy sklep qzx",
        "-180.00", FlowKind.EXPENSE, ExpenseCategory.CLOTHING);
    manualUnknown.setRawDescription("TAJEMNICZY SKLEP QZX");
    manualUnknown.setClassificationSource(ClassificationSource.USER_OVERRIDE);
    manualUnknown.setConfidence(new BigDecimal("1.00"));

    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(manualOrlen, manualUnknown));

    ReclassifyExpensesResponse response = expenseService.reclassifyTransactions(
        userId, new ReclassifyExpensesRequest(null, null, null, true));

    assertThat(response.processedCount()).isEqualTo(2);
    assertThat(response.updatedCount()).isEqualTo(1);
    assertThat(response.aiUsed()).isFalse();
    assertThat(response.aiWarning()).isNull();

    assertThat(manualOrlen.getCategory()).isEqualTo(ExpenseCategory.FUEL);
    assertThat(manualOrlen.getClassificationSource()).isEqualTo(ClassificationSource.RULE_ENGINE);

    assertThat(manualUnknown.getCategory()).isEqualTo(ExpenseCategory.CLOTHING);
    assertThat(manualUnknown.getFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(manualUnknown.getClassificationSource()).isEqualTo(ClassificationSource.USER_OVERRIDE);
    assertThat(manualUnknown.getConfidence()).isEqualByComparingTo("1.00");

    ArgumentCaptor<List<ExpenseTransaction>> savedCaptor = ArgumentCaptor.forClass(List.class);
    verify(transactionRepository).saveAll(savedCaptor.capture());
    assertThat(savedCaptor.getValue()).containsExactly(manualOrlen);
  }

  @Test
  void reclassifyTransactions_preservesManualOverrideOnUnknownMerchantWhenAiReturnsNoSuggestion() {
    stubUserPln();
    llmPort.enabled = true;
    llmPort.suggestions = Map.of();

    ExpenseTransaction manualUnknown = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 7), "tajemniczy_sklep_qzx", "Tajemniczy Sklep Qzx", "tajemniczy sklep qzx",
        "-180.00", FlowKind.EXPENSE, ExpenseCategory.CLOTHING);
    manualUnknown.setRawDescription("TAJEMNICZY SKLEP QZX");
    manualUnknown.setClassificationSource(ClassificationSource.USER_OVERRIDE);
    manualUnknown.setConfidence(new BigDecimal("1.00"));

    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(manualUnknown));

    ReclassifyExpensesResponse response = expenseService.reclassifyTransactions(
        userId, new ReclassifyExpensesRequest(null, null, null, true));

    assertThat(response.processedCount()).isEqualTo(1);
    assertThat(response.updatedCount()).isZero();
    assertThat(response.aiUsed()).isTrue();
    assertThat(response.aiWarning()).isNull();

    assertThat(manualUnknown.getCategory()).isEqualTo(ExpenseCategory.CLOTHING);
    assertThat(manualUnknown.getFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(manualUnknown.getClassificationSource()).isEqualTo(ClassificationSource.USER_OVERRIDE);
    assertThat(manualUnknown.getConfidence()).isEqualByComparingTo("1.00");

    verify(transactionRepository, never()).saveAll(any());
  }

  @Test
  void reclassifyTransactions_appliesUserMerchantRulesAndOwnerSelfTransferDetection() {
    stubUserPln();
    llmPort.enabled = false;

    ExpenseMerchantRule empikRule = new ExpenseMerchantRule();
    empikRule.setUserId(userId);
    empikRule.setMatchType(RuleMatchType.MERCHANT);
    empikRule.setMatchKey("empik");
    empikRule.setDisplayLabel("Empik");
    empikRule.setCategory(ExpenseCategory.EDUCATION);
    empikRule.setFlowKind(FlowKind.EXPENSE);

    ExpenseTransaction empikTx = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 13), "empik", "Empik", "empik",
        "-89.00", FlowKind.EXPENSE, ExpenseCategory.SHOPPING);
    empikTx.setRawDescription("EMPIK 123 WARSZAWA");
    empikTx.setClassificationSource(ClassificationSource.RULE_ENGINE);

    ExpenseTransaction selfTransfer = entityTx(
        UUID.randomUUID(), userId, LocalDate.of(2026, 10, 14), "jan_kowalski", "Jan Kowalski", "jan kowalski",
        "-500.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);
    selfTransfer.setRawDescription("PRZELEW DO JAN KOWALSKI");
    selfTransfer.setClassificationSource(ClassificationSource.FALLBACK);

    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of(empikRule));
    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(empikTx, selfTransfer));

    ReclassifyExpensesResponse response = expenseService.reclassifyTransactions(userId, null);

    assertThat(response.processedCount()).isEqualTo(2);
    assertThat(response.updatedCount()).isEqualTo(2);
    assertThat(response.aiUsed()).isFalse();

    assertThat(empikTx.getCategory()).isEqualTo(ExpenseCategory.EDUCATION);
    assertThat(empikTx.getFlowKind()).isEqualTo(FlowKind.EXPENSE);
    assertThat(empikTx.getClassificationSource()).isEqualTo(ClassificationSource.USER_RULE);

    assertThat(selfTransfer.getCategory()).isEqualTo(ExpenseCategory.INTERNAL_TRANSFER);
    assertThat(selfTransfer.getFlowKind()).isEqualTo(FlowKind.INTERNAL_TRANSFER);
    assertThat(selfTransfer.getClassificationSource()).isEqualTo(ClassificationSource.RULE_ENGINE);

    ArgumentCaptor<List<ExpenseTransaction>> savedCaptor = ArgumentCaptor.forClass(List.class);
    verify(transactionRepository).saveAll(savedCaptor.capture());
    assertThat(savedCaptor.getValue()).containsExactlyInAnyOrder(empikTx, selfTransfer);
  }

  @Test
  void expenseTransaction_hasOptimisticLockVersionField() {
    boolean hasVersion = java.util.Arrays.stream(ExpenseTransaction.class.getDeclaredFields())
        .anyMatch(f -> f.isAnnotationPresent(jakarta.persistence.Version.class));
    assertThat(hasVersion).isTrue();
  }

  @Test
  void reclassifyTransactions_skipsRowsOverriddenConcurrentlyDuringLlmCall() {
    stubUserPln();
    llmPort.enabled = true;
    UUID txId = UUID.randomUUID();
    ExpenseTransaction staleSnapshot = entityTx(
        txId, userId, LocalDate.of(2026, 10, 9), "unknown_studio", "Unknown Studio", "unknown studio",
        "-120.00", FlowKind.EXPENSE, ExpenseCategory.OTHER_EXPENSE);
    staleSnapshot.setRawDescription("STUDIO FRYZJERSKIE XYZ");
    staleSnapshot.setClassificationSource(ClassificationSource.FALLBACK);

    ExpenseTransaction concurrentlyOverridden = entityTx(
        txId, userId, LocalDate.of(2026, 10, 9), "unknown_studio", "Unknown Studio", "unknown studio",
        "-120.00", FlowKind.EXPENSE, ExpenseCategory.GIFTS);
    concurrentlyOverridden.setRawDescription("STUDIO FRYZJERSKIE XYZ");
    concurrentlyOverridden.setClassificationSource(ClassificationSource.USER_OVERRIDE);

    when(ruleRepository.findAllByUserIdOrderByUpdatedAtDesc(userId)).thenReturn(List.of());
    when(transactionRepository.findAllByUserIdOrderByTxDateDescCreatedAtDesc(userId))
        .thenReturn(List.of(staleSnapshot));
    org.mockito.Mockito.lenient().when(transactionRepository.findAllById(anyCollection()))
        .thenReturn(List.of(concurrentlyOverridden));
    llmPort.suggestions = Map.of(
        txId.toString(),
        new LlmCategorySuggestion(ExpenseCategory.BEAUTY_CARE, FlowKind.EXPENSE, "Studio XYZ", new BigDecimal("0.91")));

    ReclassifyExpensesResponse res = expenseService.reclassifyTransactions(
        userId, new ReclassifyExpensesRequest(null, null, null, false));

    assertThat(res.updatedCount()).isZero();
    verify(transactionRepository, never()).saveAll(any());
  }

  @Test
  void commitStatement_rejectsMismatchedBaseAmountOrNonPositiveFxRateAndIsIdempotentOnDuplicate() {
    org.mockito.Mockito.lenient().when(statementRepository.save(any(ExpenseStatement.class))).thenAnswer(inv -> {
      ExpenseStatement st = inv.getArgument(0);
      st.setId(UUID.randomUUID());
      return st;
    });
    LocalDate txDate = LocalDate.of(2026, 10, 8);
    CommitTransactionItem mismatchedSign = new CommitTransactionItem(
        txDate, txDate, "BIEDRONKA", "biedronka", "Biedronka", null,
        new BigDecimal("-50.00"), "PLN", new BigDecimal("50.00"), "PLN", BigDecimal.ONE, false,
        FlowKind.EXPENSE, ExpenseCategory.GROCERIES, ClassificationSource.RULE_ENGINE, BigDecimal.ONE,
        "fp-mismatch", false, false, null);

    assertThatThrownBy(() -> expenseService.commitStatement(
        userId,
        new CommitStatementRequest(
            "bad.csv", "hash-mismatch", StatementFormat.CSV, BankProfile.GENERIC, null, "PLN",
            txDate, txDate, null, null, null, null, ReconciliationStatus.UNAVAILABLE, null,
            List.of(mismatchedSign))))
        .isInstanceOf(IllegalArgumentException.class);

    CommitTransactionItem zeroFx = new CommitTransactionItem(
        txDate, txDate, "BIEDRONKA", "biedronka", "Biedronka", null,
        new BigDecimal("-50.00"), "EUR", new BigDecimal("-200.00"), "PLN", BigDecimal.ZERO, false,
        FlowKind.EXPENSE, ExpenseCategory.GROCERIES, ClassificationSource.RULE_ENGINE, BigDecimal.ONE,
        "fp-zero-fx", false, false, null);

    assertThatThrownBy(() -> expenseService.commitStatement(
        userId,
        new CommitStatementRequest(
            "bad2.csv", "hash-zero-fx", StatementFormat.CSV, BankProfile.GENERIC, null, "EUR",
            txDate, txDate, null, null, null, null, ReconciliationStatus.UNAVAILABLE, null,
            List.of(zeroFx))))
        .isInstanceOf(IllegalArgumentException.class);

    CommitTransactionItem dupItem = commitItem(
        "fp-dup-1", "biedronka", "Biedronka", null, "-50.00", ExpenseCategory.GROCERIES, false);
    when(transactionRepository.findExistingFingerprints(eq(userId), anyCollection()))
        .thenReturn(Set.of("fp-dup-1"));
    UUID existingStatementId = UUID.randomUUID();
    ExpenseStatement persistedStatement = new ExpenseStatement();
    persistedStatement.setId(existingStatementId);
    persistedStatement.setUserId(userId);
    persistedStatement.setFileName("dup.csv");
    persistedStatement.setFormat(StatementFormat.CSV);
    persistedStatement.setBankProfile(BankProfile.GENERIC);
    persistedStatement.setAccountLabel("Main PLN");
    persistedStatement.setCurrency("PLN");
    persistedStatement.setPeriodStart(txDate);
    persistedStatement.setPeriodEnd(txDate);
    persistedStatement.setReconciliationStatus(ReconciliationStatus.UNAVAILABLE);
    persistedStatement.setReconciliationDelta(BigDecimal.ZERO);
    persistedStatement.setCreatedAt(java.time.LocalDateTime.of(2026, 10, 8, 9, 30));
    when(statementRepository.findFirstByUserIdAndFileHashSha256OrderByCreatedAtDesc(userId, "hash-dup"))
        .thenReturn(Optional.of(persistedStatement));

    StatementSummaryResponse dupSummary = expenseService.commitStatement(
        userId,
        new CommitStatementRequest(
            "dup.csv", "hash-dup", StatementFormat.CSV, BankProfile.GENERIC, null, "PLN",
            txDate, txDate, null, null, null, new BigDecimal("50.00"), ReconciliationStatus.UNAVAILABLE, null,
            List.of(dupItem)));

    assertThat(dupSummary.id()).isEqualTo(existingStatementId);
    assertThat(dupSummary.accountLabel()).isEqualTo("Main PLN");
    assertThat(dupSummary.savedCount()).isZero();
    assertThat(dupSummary.skippedDuplicates()).isEqualTo(1);
    verify(statementRepository, never()).save(any());
  }

  @Test
  @SuppressWarnings("unchecked")
  void commitStatement_chunksExistingFingerprintLookupsIntoAtMostThousandItemBatches() {
    when(statementRepository.save(any(ExpenseStatement.class))).thenAnswer(inv -> {
      ExpenseStatement st = inv.getArgument(0);
      st.setId(UUID.randomUUID());
      return st;
    });
    when(transactionRepository.findExistingFingerprints(eq(userId), anyCollection()))
        .thenReturn(Set.of());

    int totalItems = 2050;
    List<CommitTransactionItem> items = new ArrayList<>(totalItems);
    for (int i = 0; i < totalItems; i++) {
      items.add(commitItem(
          "fp-chunk-" + i,
          "biedronka",
          "Biedronka",
          null,
          "-10.00",
          ExpenseCategory.GROCERIES,
          false));
    }

    LocalDate txDate = LocalDate.of(2026, 10, 8);
    StatementSummaryResponse summary = expenseService.commitStatement(
        userId,
        new CommitStatementRequest(
            "large.csv", "hash-large", StatementFormat.CSV, BankProfile.GENERIC, null, "PLN",
            txDate, txDate, null, null, null, null, ReconciliationStatus.UNAVAILABLE, null, items));

    assertThat(summary.savedCount()).isEqualTo(totalItems);

    ArgumentCaptor<Collection<String>> chunkCaptor = ArgumentCaptor.forClass(Collection.class);
    verify(transactionRepository, org.mockito.Mockito.times(3))
        .findExistingFingerprints(eq(userId), chunkCaptor.capture());
    assertThat(chunkCaptor.getAllValues())
        .extracting(Collection::size)
        .containsExactly(1000, 1000, 50);
  }

  private void stubUserPln() {
    User user = new User("user@test.pl", "hash", "Jan", "Kowalski", Role.USER, Currency.PLN);
    user.setId(userId);
    when(userRepository.findById(userId)).thenReturn(Optional.of(user));
  }

  private static CommitTransactionItem commitItem(
      String fp,
      String merchantKey,
      String display,
      String phraseKey,
      String amount,
      ExpenseCategory cat,
      boolean rememberRule) {
    BigDecimal amt = new BigDecimal(amount);
    return new CommitTransactionItem(
        LocalDate.of(2026, 10, 8),
        LocalDate.of(2026, 10, 8),
        display,
        merchantKey,
        display,
        phraseKey,
        amt,
        "PLN",
        amt,
        "PLN",
        BigDecimal.ONE,
        false,
        cat.defaultFlowKind(),
        cat,
        ClassificationSource.USER_OVERRIDE,
        BigDecimal.ONE,
        fp,
        false,
        rememberRule,
        null);
  }

  private static ExpenseTransaction entityTx(
      UUID id,
      UUID userId,
      LocalDate date,
      String merchantKey,
      String display,
      String phraseKey,
      String amount,
      FlowKind flowKind,
      ExpenseCategory category) {
    ExpenseTransaction tx = new ExpenseTransaction();
    tx.setId(id);
    tx.setUserId(userId);
    tx.setStatementId(UUID.randomUUID());
    tx.setTxDate(date);
    tx.setBookingDate(date);
    tx.setRawDescription(display);
    tx.setMerchantKey(merchantKey);
    tx.setMerchantDisplay(display);
    tx.setPhraseKey(phraseKey);
    BigDecimal amt = new BigDecimal(amount);
    tx.setAmount(amt);
    tx.setCurrency("PLN");
    tx.setBaseAmount(amt);
    tx.setBaseCurrency("PLN");
    tx.setFxRate(BigDecimal.ONE);
    tx.setFxDegraded(false);
    tx.setFlowKind(flowKind);
    tx.setCategory(category);
    tx.setClassificationSource(ClassificationSource.RULE_ENGINE);
    tx.setConfidence(new BigDecimal("0.94"));
    tx.setFingerprint("fp-" + id);
    return tx;
  }

  private static byte[] buildMinimalPdf14(List<String> textLines) {
    StringBuilder stream = new StringBuilder();
    stream.append("BT\n/F1 10 Tf\n50 750 Td\n14 TL\n");
    for (int i = 0; i < textLines.size(); i++) {
      String escaped = textLines.get(i)
          .replace("\\", "\\\\")
          .replace("(", "\\(")
          .replace(")", "\\)");
      stream.append("(").append(escaped).append(") Tj");
      if (i < textLines.size() - 1) {
        stream.append(" T*");
      }
      stream.append("\n");
    }
    stream.append("ET\n");
    byte[] streamBytes = stream.toString().getBytes(StandardCharsets.US_ASCII);

    List<String> objects = List.of(
        "1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n",
        "2 0 obj\n<< /Type /Pages /Kids [3 0 R] /Count 1 >>\nendobj\n",
        "3 0 obj\n<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] "
            + "/Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>\nendobj\n",
        "4 0 obj\n<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>\nendobj\n"
    );

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    out.writeBytes("%PDF-1.4\n".getBytes(StandardCharsets.US_ASCII));
    List<Integer> offsets = new ArrayList<>();
    for (String obj : objects) {
      offsets.add(out.size());
      out.writeBytes(obj.getBytes(StandardCharsets.US_ASCII));
    }
    offsets.add(out.size());
    out.writeBytes(String.format(
        Locale.ROOT, "5 0 obj\n<< /Length %d >>\nstream\n", streamBytes.length)
        .getBytes(StandardCharsets.US_ASCII));
    out.writeBytes(streamBytes);
    out.writeBytes("endstream\nendobj\n".getBytes(StandardCharsets.US_ASCII));

    int xrefOffset = out.size();
    StringBuilder xref = new StringBuilder();
    xref.append("xref\n0 ").append(offsets.size() + 1).append("\n");
    xref.append("0000000000 65535 f \n");
    for (int offset : offsets) {
      xref.append(String.format(Locale.ROOT, "%010d 00000 n \n", offset));
    }
    xref.append("trailer\n<< /Size ").append(offsets.size() + 1).append(" /Root 1 0 R >>\n");
    xref.append("startxref\n").append(xrefOffset).append("\n%%EOF\n");
    out.writeBytes(xref.toString().getBytes(StandardCharsets.US_ASCII));
    return out.toByteArray();
  }

  private static final class StubMarketDataPort implements MarketDataPort {
    private final Map<String, FxSeries> fxSeries = new LinkedHashMap<>();
    private boolean throwOnFx;

    void withFx(String from, String to, String spotRate) {
      fxSeries.put(from + ":" + to, new FxSeries(from, to, new BigDecimal(spotRate), new TreeMap<>()));
    }

    @Override
    public BigDecimal getExchangeRate(Currency from, Currency to, LocalDate date) {
      return BigDecimal.ONE;
    }

    @Override
    public List<InstrumentResponse> searchInstruments(String query) {
      return List.of();
    }

    @Override
    public Map<String, MarketQuote> getBatchQuotes(Collection<String> symbols) {
      return Map.of();
    }

    @Override
    public Map<String, PriceHistory> getBatchHistory(Collection<String> symbols, LocalDate startDate) {
      return Map.of();
    }

    @Override
    public Map<String, FxSeries> getBatchFxHistory(
        Collection<CurrencyPair> pairs, LocalDate startDate, LocalDate endDate) {
      if (throwOnFx) {
        throw new IllegalStateException("FX service unavailable");
      }
      Map<String, FxSeries> out = new LinkedHashMap<>();
      for (CurrencyPair pair : pairs) {
        if (fxSeries.containsKey(pair.key())) {
          out.put(pair.key(), fxSeries.get(pair.key()));
        }
      }
      return out;
    }

    @Override
    public BigDecimal getCurrentCpi() {
      return BigDecimal.ZERO;
    }

    @Override
    public InflationSnapshot getInflation() {
      return new InflationSnapshot(BigDecimal.ZERO, Map.of());
    }
  }

  private static final class StubLlmPort implements ExpenseLlmPort {
    private boolean enabled;
    private LlmClassificationContext capturedContext;
    private Map<String, LlmCategorySuggestion> suggestions = Map.of();
    private boolean throwOnClassify;

    @Override
    public boolean isEnabled() {
      return enabled;
    }

    @Override
    public String providerName() {
      return enabled ? "ollama" : "none";
    }

    @Override
    public String modelName() {
      return enabled ? "tev1:4b" : "";
    }

    @Override
    public Map<String, LlmCategorySuggestion> classifyUnknowns(List<LlmClassifyInput> items) {
      if (throwOnClassify) {
        throw new IllegalStateException("LLM unavailable");
      }
      return suggestions;
    }

    @Override
    public Map<String, LlmCategorySuggestion> classifyUnknowns(
        List<LlmClassifyInput> items, LlmClassificationContext context) {
      capturedContext = context;
      return classifyUnknowns(items);
    }
  }
}
