package pl.investassist.portfolio.service.impl;

import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.TransactionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.mapper.TransactionMapper;
import pl.investassist.portfolio.repository.TransactionRepository;
import pl.investassist.portfolio.service.PortfolioService;
import pl.investassist.portfolio.service.PositionService;
import pl.investassist.portfolio.service.TransactionService;

@Service
public class TransactionServiceImpl implements TransactionService {
  private final TransactionRepository transactionRepository;
  private final PortfolioService portfolioService;
  private final PositionService positionService;

  public TransactionServiceImpl(
      TransactionRepository transactionRepository,
      PortfolioService portfolioService,
      PositionService positionService) {
    this.transactionRepository = transactionRepository;
    this.portfolioService = portfolioService;
    this.positionService = positionService;
  }

  @Override
  @Transactional
  public TransactionResponse createTransaction(
      UUID userId, UUID portfolioId, CreateTransactionRequest request) {
    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    positionService.updatePosition(portfolio, request);
    Transaction transaction = TransactionMapper.toEntity(request, portfolio);
    Transaction savedTransaction = transactionRepository.save(transaction);
    return TransactionMapper.toResponse(savedTransaction);
  }

  @Override
  public List<TransactionResponse> getTransactions(UUID userId, UUID portfolioId) {
    portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    return transactionRepository.findAllByPortfolioId(portfolioId).stream()
        .map(TransactionMapper::toResponse)
        .toList();
  }
}
