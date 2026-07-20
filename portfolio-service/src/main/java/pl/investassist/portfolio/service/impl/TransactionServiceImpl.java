package pl.investassist.portfolio.service.impl;

import jakarta.transaction.Transactional;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import pl.investassist.portfolio.client.InstrumentClient;
import pl.investassist.portfolio.client.MarketDataClient;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.InstrumentResponse;
import pl.investassist.portfolio.dto.response.TransactionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.exception.ResourceNotFoundException;
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
  private final MarketDataClient marketDataClient;

  private final InstrumentClient instrumentClient;

  public TransactionServiceImpl(
      TransactionRepository transactionRepository,
      PortfolioService portfolioService,
      PositionService positionService,
      MarketDataClient marketDataClient,
      InstrumentClient instrumentClient) {
    this.transactionRepository = transactionRepository;
    this.portfolioService = portfolioService;
    this.positionService = positionService;
    this.marketDataClient = marketDataClient;
    this.instrumentClient = instrumentClient;
  }

  @Override
  @Transactional
  public TransactionResponse createTransaction(
      UUID userId, UUID portfolioId, CreateTransactionRequest request) {

    InstrumentResponse foundInstrument = instrumentClient.getInstrumentById(request.instrumentId());
    if (foundInstrument == null) {
      throw new ResourceNotFoundException(
          "Instrument not found with id: " + request.instrumentId());
    }

    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    Transaction transaction = TransactionMapper.toEntity(request, portfolio);

    BigDecimal exchangeRate = BigDecimal.ONE;
    BigDecimal resolvedPrice = request.price();

    if (!foundInstrument.currency().equals(request.currency())) {
      exchangeRate =
          marketDataClient.getExchangeRate(foundInstrument.currency(), request.currency());
      resolvedPrice = request.price().divide(exchangeRate, 6, RoundingMode.HALF_UP);
    }

    transaction.setExchangeRate(exchangeRate);

    positionService.updatePosition(portfolio, request, foundInstrument.currency(), resolvedPrice);
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
