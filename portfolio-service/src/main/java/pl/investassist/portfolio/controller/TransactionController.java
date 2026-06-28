package pl.investassist.portfolio.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
  public ResponseEntity<TransactionResponse> createTransaction(
      @RequestHeader("X-User-Id") UUID userId,
      @PathVariable UUID portfolioId,
      @Valid @RequestBody CreateTransactionRequest request) {
    return ResponseEntity.ok()
        .body(transactionService.createTransaction(userId, portfolioId, request));
  }

  @GetMapping
  public ResponseEntity<List<TransactionResponse>> getAllTransactions(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID portfolioId) {
    return ResponseEntity.ok().body(transactionService.getTransactions(userId, portfolioId));
  }
}
