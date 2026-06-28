package pl.investassist.portfolio.service;

import java.util.List;
import java.util.UUID;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.TransactionResponse;

public interface TransactionService {

  TransactionResponse createTransaction(
      UUID userId, UUID portfolioId, CreateTransactionRequest request);

  List<TransactionResponse> getTransactions(UUID userId, UUID portfolioId);
}
