package pl.investassist.portfolio.mapper;

import java.time.LocalDateTime;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.TransactionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;

public final class TransactionMapper {

  private TransactionMapper() {}

  public static TransactionResponse toResponse(Transaction transaction) {
    if (transaction == null) {
      return null;
    }
    return new TransactionResponse(
        transaction.getId(),
        transaction.getInstrumentId(),
        transaction.getTransactionType(),
        transaction.getQuantity(),
        transaction.getPrice(),
        transaction.getCommission(),
        transaction.getCurrency(),
        transaction.getExchangeRate(),
        transaction.getExecutedAt());
  }

  public static Transaction toEntity(CreateTransactionRequest request, Portfolio portfolio) {
    if (request == null) {
      return null;
    }
    Transaction transaction = new Transaction();
    transaction.setPortfolio(portfolio);
    transaction.setInstrumentId(request.instrumentId());
    transaction.setTransactionType(request.transactionType());
    transaction.setQuantity(request.quantity());
    transaction.setPrice(request.price());
    transaction.setCommission(request.commission());
    transaction.setCurrency(request.currency());
    if (request.exchangeRate() != null) {
      transaction.setExchangeRate(request.exchangeRate());
    }
    return transaction;
  }
}
