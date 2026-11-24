package pl.investassist.expense.controller;

import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
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
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.service.ExpenseService;

@RestController
@RequestMapping("/api/expenses")
@Validated
public class ExpenseController {

  private final ExpenseService expenseService;

  public ExpenseController(ExpenseService expenseService) {
    this.expenseService = expenseService;
  }

  @GetMapping("/status")
  public ResponseEntity<ExpenseStatusResponse> getStatus(
      @RequestHeader("X-User-Id") UUID userId) {
    return ResponseEntity.ok(expenseService.getStatus(userId));
  }

  @PostMapping(value = "/statements/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<StatementPreviewResponse> previewStatement(
      @RequestHeader("X-User-Id") UUID userId,
      @RequestParam("file") MultipartFile file,
      @RequestParam(value = "bankProfile", required = false, defaultValue = "AUTO")
          BankProfile bankProfile,
      @RequestParam(value = "useAi", required = false, defaultValue = "true") boolean useAi,
      @RequestParam(value = "accountLabel", required = false) String accountLabel) {
    return ResponseEntity.ok(
        expenseService.previewStatement(userId, file, bankProfile, useAi, accountLabel));
  }

  @PostMapping("/statements/commit")
  public ResponseEntity<StatementSummaryResponse> commitStatement(
      @RequestHeader("X-User-Id") UUID userId,
      @Valid @RequestBody CommitStatementRequest request) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(expenseService.commitStatement(userId, request));
  }

  @GetMapping("/statements")
  public ResponseEntity<List<StatementSummaryResponse>> listStatements(
      @RequestHeader("X-User-Id") UUID userId) {
    return ResponseEntity.ok(expenseService.listStatements(userId));
  }

  @DeleteMapping("/statements/{statementId}")
  public ResponseEntity<Void> deleteStatement(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID statementId) {
    expenseService.deleteStatement(userId, statementId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/analytics")
  public ResponseEntity<ExpenseAnalyticsResponse> getAnalytics(
      @RequestHeader("X-User-Id") UUID userId,
      @RequestParam(value = "from", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate from,
      @RequestParam(value = "to", required = false)
          @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
          LocalDate to,
      @RequestParam(value = "statementId", required = false) UUID statementId,
      @RequestParam(value = "category", required = false) ExpenseCategory category,
      @RequestParam(value = "includeTransactions", required = false, defaultValue = "true")
          boolean includeTransactions,
      @RequestParam(value = "lang", required = false, defaultValue = "pl") String lang) {
    return ResponseEntity.ok(
        expenseService.getAnalytics(
            userId, from, to, statementId, category, includeTransactions, lang));
  }

  @PatchMapping("/transactions/{transactionId}")
  public ResponseEntity<ExpenseTransactionResponse> updateTransaction(
      @RequestHeader("X-User-Id") UUID userId,
      @PathVariable UUID transactionId,
      @Valid @RequestBody UpdateExpenseTransactionRequest request) {
    return ResponseEntity.ok(expenseService.updateTransaction(userId, transactionId, request));
  }

  @GetMapping("/rules")
  public ResponseEntity<List<MerchantRuleResponse>> listRules(
      @RequestHeader("X-User-Id") UUID userId) {
    return ResponseEntity.ok(expenseService.listRules(userId));
  }

  @DeleteMapping("/rules/{ruleId}")
  public ResponseEntity<Void> deleteRule(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID ruleId) {
    expenseService.deleteRule(userId, ruleId);
    return ResponseEntity.noContent().build();
  }

  @PostMapping("/reclassify")
  public ResponseEntity<ReclassifyExpensesResponse> reclassify(
      @RequestHeader("X-User-Id") UUID userId,
      @RequestBody(required = false) ReclassifyExpensesRequest request) {
    return ResponseEntity.ok(expenseService.reclassifyTransactions(userId, request));
  }
}
