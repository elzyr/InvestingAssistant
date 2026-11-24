package pl.investassist.expense.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import org.springframework.web.server.ResponseStatusException;
import pl.investassist.common.exception.GlobalExceptionHandler;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.expense.analytics.ExpenseKpis;
import pl.investassist.expense.dto.request.CommitStatementRequest;
import pl.investassist.expense.dto.request.ReclassifyExpensesRequest;
import pl.investassist.expense.dto.request.UpdateExpenseTransactionRequest;
import pl.investassist.expense.dto.response.ExpenseAnalyticsResponse;
import pl.investassist.expense.dto.response.ExpenseStatusResponse;
import pl.investassist.expense.dto.response.ExpenseTransactionResponse;
import pl.investassist.expense.dto.response.MerchantRuleResponse;
import pl.investassist.expense.dto.response.ReclassifyExpensesResponse;
import pl.investassist.expense.dto.response.StatementPreviewResponse;
import pl.investassist.expense.dto.response.StatementSummaryResponse;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;
import pl.investassist.expense.enums.ReconciliationStatus;
import pl.investassist.expense.enums.RuleMatchType;
import pl.investassist.expense.enums.StatementFormat;
import pl.investassist.expense.parser.ReconciliationResult;
import pl.investassist.expense.service.ExpenseService;

@ExtendWith(MockitoExtension.class)
class ExpenseControllerTest {

  @Mock
  private ExpenseService expenseService;

  private MockMvc mockMvc;
  private UUID userId;

  @BeforeEach
  void setUp() {
    ExpenseController target = new ExpenseController(expenseService);
    LocalValidatorFactoryBean validatorFactory = new LocalValidatorFactoryBean();
    validatorFactory.afterPropertiesSet();
    ProxyFactory proxyFactory = new ProxyFactory(target);
    proxyFactory.addAdvice(new MethodValidationInterceptor(validatorFactory.getValidator()));
    Object validatedController = proxyFactory.getProxy();

    mockMvc = MockMvcBuilders.standaloneSetup(validatedController)
        .setControllerAdvice(new GlobalExceptionHandler(new StaticMessageSource()))
        .build();
    userId = UUID.randomUUID();
  }

  @Test
  void getStatus_returnsServiceStatusAndRequiresUserHeader() throws Exception {
    ExpenseStatusResponse disabledStatus = new ExpenseStatusResponse(
        false,
        "none",
        "",
        "PLN",
        10485760L,
        List.of("PDF", "CSV"),
        List.of("AUTO", "MBANK", "REVOLUT", "WISE", "GENERIC"));
    when(expenseService.getStatus(userId)).thenReturn(disabledStatus);

    mockMvc.perform(get("/api/expenses/status").header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aiEnabled").value(false))
        .andExpect(jsonPath("$.aiProvider").value("none"))
        .andExpect(jsonPath("$.baseCurrency").value("PLN"))
        .andExpect(jsonPath("$.maxFileSizeBytes").value(10485760));

    ExpenseStatusResponse ollamaStatus = new ExpenseStatusResponse(
        true,
        "ollama",
        "tev1:4b",
        "PLN",
        10485760L,
        List.of("PDF", "CSV"),
        List.of("AUTO", "MBANK", "REVOLUT", "WISE", "GENERIC"));
    when(expenseService.getStatus(userId)).thenReturn(ollamaStatus);

    mockMvc.perform(get("/api/expenses/status").header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.aiEnabled").value(true))
        .andExpect(jsonPath("$.aiProvider").value("ollama"))
        .andExpect(jsonPath("$.aiModel").value("tev1:4b"));

    mockMvc.perform(get("/api/expenses/status"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void previewStatement_handlesSuccessBadRequest400AndScannedPdf422() throws Exception {
    MockMultipartFile csvFile = new MockMultipartFile(
        "file", "mbank.csv", "text/csv", "Date,Description,Amount\n2026-10-01,Test,-10.00".getBytes(StandardCharsets.UTF_8));

    StatementPreviewResponse preview = new StatementPreviewResponse(
        "mbank.csv",
        "hash123",
        false,
        StatementFormat.CSV,
        BankProfile.MBANK,
        "PLN",
        "PLN",
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 31),
        new ReconciliationResult(
            ReconciliationStatus.VERIFIED,
            new BigDecimal("1000.00"),
            new BigDecimal("990.00"),
            BigDecimal.ZERO,
            new BigDecimal("10.00"),
            BigDecimal.ZERO),
        false,
        null,
        0,
        List.of(),
        0,
        0,
        List.of());
    when(expenseService.previewStatement(eq(userId), any(), eq(BankProfile.MBANK), eq(true), eq("Main")))
        .thenReturn(preview);

    mockMvc.perform(multipart("/api/expenses/statements/preview")
            .file(csvFile)
            .param("bankProfile", "MBANK")
            .param("useAi", "true")
            .param("accountLabel", "Main")
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.fileName").value("mbank.csv"))
        .andExpect(jsonPath("$.detectedBankProfile").value("MBANK"))
        .andExpect(jsonPath("$.reconciliation.status").value("VERIFIED"));

    when(expenseService.previewStatement(eq(userId), any(), any(), eq(true), any()))
        .thenThrow(new IllegalArgumentException("error.expense_invalid_pdf"));
    mockMvc.perform(multipart("/api/expenses/statements/preview")
            .file(csvFile)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isBadRequest());

    when(expenseService.previewStatement(eq(userId), any(), any(), eq(true), any()))
        .thenThrow(new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "error.expense_scanned_pdf"));
    mockMvc.perform(multipart("/api/expenses/statements/preview")
            .file(csvFile)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isUnprocessableEntity());
  }

  @Test
  void commitStatementsListAndDelete_enforcesValidationAndOwnership() throws Exception {
    UUID statementId = UUID.randomUUID();
    StatementSummaryResponse summary = new StatementSummaryResponse(
        statementId,
        "mbank.csv",
        StatementFormat.CSV,
        BankProfile.MBANK,
        "Main",
        "PLN",
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 31),
        ReconciliationStatus.VERIFIED,
        BigDecimal.ZERO,
        2,
        0,
        Instant.parse("2026-10-31T12:00:00Z"));
    when(expenseService.commitStatement(eq(userId), any(CommitStatementRequest.class))).thenReturn(summary);
    when(expenseService.listStatements(userId)).thenReturn(List.of(summary));

    String commitPayload = """
        {
          "fileName": "mbank.csv",
          "fileHashSha256": "abc123",
          "format": "CSV",
          "bankProfile": "MBANK",
          "accountLabel": "Main",
          "currency": "PLN",
          "periodStart": "2026-10-01",
          "periodEnd": "2026-10-31",
          "openingBalance": 1000.00,
          "closingBalance": 950.00,
          "calculatedInflows": 0.00,
          "calculatedOutflows": 50.00,
          "reconciliationStatus": "VERIFIED",
          "reconciliationDelta": 0.00,
          "transactions": [
            {
              "txDate": "2026-10-05",
              "rawDescription": "BIEDRONKA",
              "merchantKey": "biedronka",
              "merchantDisplay": "Biedronka",
              "amount": -50.00,
              "currency": "PLN",
              "baseAmount": -50.00,
              "baseCurrency": "PLN",
              "fxRate": 1.0,
              "fxDegraded": false,
              "flowKind": "EXPENSE",
              "category": "GROCERIES",
              "classificationSource": "RULE_ENGINE",
              "confidence": 0.95,
              "fingerprint": "fp-1",
              "excluded": false,
              "rememberRule": false
            }
          ]
        }
        """;

    mockMvc.perform(post("/api/expenses/statements/commit")
            .header("X-User-Id", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content(commitPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(statementId.toString()))
        .andExpect(jsonPath("$.savedCount").value(2));

    mockMvc.perform(post("/api/expenses/statements/commit")
            .header("X-User-Id", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isBadRequest());

    mockMvc.perform(get("/api/expenses/statements").header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].fileName").value("mbank.csv"));

    doNothing().when(expenseService).deleteStatement(userId, statementId);
    mockMvc.perform(delete("/api/expenses/statements/{statementId}", statementId)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isNoContent());

    UUID otherStatementId = UUID.randomUUID();
    doThrow(new ResourceNotFoundException("error.resource_not_found"))
        .when(expenseService).deleteStatement(userId, otherStatementId);
    mockMvc.perform(delete("/api/expenses/statements/{statementId}", otherStatementId)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isNotFound());
  }

  @Test
  void analyticsTransactionsAndRules_forwardFiltersAndEnforceOwnership() throws Exception {
    UUID statementId = UUID.randomUUID();
    UUID txId = UUID.randomUUID();
    UUID ruleId = UUID.randomUUID();

    ExpenseAnalyticsResponse analytics = new ExpenseAnalyticsResponse(
        "PLN",
        LocalDate.of(2026, 10, 1),
        LocalDate.of(2026, 10, 31),
        new ExpenseKpis(
            new BigDecimal("5000.00"),
            new BigDecimal("200.00"),
            new BigDecimal("200.00"),
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            new BigDecimal("4800.00"),
            new BigDecimal("96.00"),
            BigDecimal.ZERO,
            new BigDecimal("200.00"),
            BigDecimal.ZERO,
            0,
            0),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
    when(expenseService.getAnalytics(
        eq(userId),
        eq(LocalDate.of(2026, 10, 1)),
        eq(LocalDate.of(2026, 10, 31)),
        eq(statementId),
        eq(ExpenseCategory.GROCERIES),
        eq(false),
        eq("pl")))
        .thenReturn(analytics);

    mockMvc.perform(get("/api/expenses/analytics")
            .header("X-User-Id", userId.toString())
            .param("from", "2026-10-01")
            .param("to", "2026-10-31")
            .param("statementId", statementId.toString())
            .param("category", "GROCERIES")
            .param("includeTransactions", "false")
            .param("lang", "pl"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.baseCurrency").value("PLN"))
        .andExpect(jsonPath("$.kpis.totalExpenses").value(200.00));

    ExpenseTransactionResponse txResp = new ExpenseTransactionResponse(
        txId,
        statementId,
        LocalDate.of(2026, 10, 5),
        LocalDate.of(2026, 10, 5),
        "PAYU / KURS",
        "payu",
        "Kurs angielskiego",
        "kurs angielskiego",
        new BigDecimal("-300.00"),
        "PLN",
        new BigDecimal("-300.00"),
        "PLN",
        BigDecimal.ONE,
        false,
        FlowKind.EXPENSE,
        ExpenseCategory.EDUCATION,
        ClassificationSource.USER_OVERRIDE,
        BigDecimal.ONE,
        false,
        false,
        false,
        "Lessons");
    when(expenseService.updateTransaction(eq(userId), eq(txId), any(UpdateExpenseTransactionRequest.class)))
        .thenReturn(txResp);

    mockMvc.perform(patch("/api/expenses/transactions/{transactionId}", txId)
            .header("X-User-Id", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "category": "EDUCATION",
                  "excluded": false,
                  "userNote": "Lessons",
                  "rememberRule": true,
                  "applyToMatching": true
                }
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.category").value("EDUCATION"))
        .andExpect(jsonPath("$.classificationSource").value("USER_OVERRIDE"));

    UUID otherTxId = UUID.randomUUID();
    when(expenseService.updateTransaction(eq(userId), eq(otherTxId), any(UpdateExpenseTransactionRequest.class)))
        .thenThrow(new ResourceNotFoundException("error.resource_not_found"));

    mockMvc.perform(patch("/api/expenses/transactions/{transactionId}", otherTxId)
            .header("X-User-Id", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"category\":\"EDUCATION\"}"))
        .andExpect(status().isNotFound());

    MerchantRuleResponse rule = new MerchantRuleResponse(
        ruleId,
        RuleMatchType.PHRASE,
        "kurs angielskiego",
        "Kurs angielskiego",
        ExpenseCategory.EDUCATION,
        FlowKind.EXPENSE,
        "USER_OVERRIDE",
        1,
        Instant.parse("2026-10-31T12:00:00Z"));
    when(expenseService.listRules(userId)).thenReturn(List.of(rule));

    mockMvc.perform(get("/api/expenses/rules").header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].matchType").value("PHRASE"))
        .andExpect(jsonPath("$[0].matchKey").value("kurs angielskiego"));

    doNothing().when(expenseService).deleteRule(userId, ruleId);
    mockMvc.perform(delete("/api/expenses/rules/{ruleId}", ruleId)
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isNoContent());

    verify(expenseService).deleteRule(userId, ruleId);
  }

  @Test
  void reclassify_forwardsFiltersReturnsSummaryAndEnforcesValidationAndOwnership() throws Exception {
    UUID statementId = UUID.randomUUID();
    ReclassifyExpensesResponse summary = new ReclassifyExpensesResponse(12, 5, true, null);
    when(expenseService.reclassifyTransactions(eq(userId), any()))
        .thenReturn(summary);

    mockMvc.perform(post("/api/expenses/reclassify")
            .header("X-User-Id", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "from": "2026-10-01",
                  "to": "2026-10-31",
                  "statementId": "%s",
                  "includeManualOverrides": true
                }
                """.formatted(statementId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processedCount").value(12))
        .andExpect(jsonPath("$.updatedCount").value(5))
        .andExpect(jsonPath("$.aiUsed").value(true));

    verify(expenseService).reclassifyTransactions(
        userId,
        new ReclassifyExpensesRequest(
            LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), statementId, true));

    mockMvc.perform(post("/api/expenses/reclassify")
            .header("X-User-Id", userId.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.processedCount").value(12));

    mockMvc.perform(post("/api/expenses/reclassify"))
        .andExpect(status().isBadRequest());

    UUID foreignStmt = UUID.randomUUID();
    when(expenseService.reclassifyTransactions(eq(userId), any()))
        .thenThrow(new ResourceNotFoundException("error.resource_not_found"));

    mockMvc.perform(post("/api/expenses/reclassify")
            .header("X-User-Id", userId.toString())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"statementId\":\"%s\"}".formatted(foreignStmt)))
        .andExpect(status().isNotFound());
  }
}
