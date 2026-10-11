package pl.investassist.portfolio.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.TransactionResponse;
import pl.investassist.portfolio.service.TransactionService;

@RestController
@RequestMapping("/api/portfolios/{portfolioId}/transactions")
public class TransactionController {
  private final TransactionService transactionService;

  public TransactionController(TransactionService transactionService) {
    this.transactionService = transactionService;
  }

  @PostMapping
  public TransactionResponse createTransaction(
      @RequestHeader("X-User-Id") UUID userId,
      @PathVariable UUID portfolioId,
      @Valid @RequestBody CreateTransactionRequest request) {
    return transactionService.createTransaction(userId, portfolioId, request);
  }

  @GetMapping
  public List<TransactionResponse> getAllTransactions(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID portfolioId) {
    return transactionService.getTransactions(userId, portfolioId);
  }

  @PutMapping("/{transactionId}")
  public TransactionResponse updateTransaction(
      @RequestHeader("X-User-Id") UUID userId,
      @PathVariable UUID portfolioId,
      @PathVariable UUID transactionId,
      @Valid @RequestBody CreateTransactionRequest request) {
    return transactionService.updateTransaction(userId, portfolioId, transactionId, request);
  }

  @DeleteMapping("/{transactionId}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deleteTransaction(
      @RequestHeader("X-User-Id") UUID userId,
      @PathVariable UUID portfolioId,
      @PathVariable UUID transactionId) {
    transactionService.deleteTransaction(userId, portfolioId, transactionId);
  }
}
