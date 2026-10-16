package pl.investassist.portfolio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.investassist.portfolio.client.InstrumentClient;
import pl.investassist.portfolio.client.MarketDataClient;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.InstrumentResponse;
import pl.investassist.portfolio.dto.response.TransactionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.Currency;
import pl.investassist.portfolio.exception.ResourceNotFoundException;
import pl.investassist.portfolio.repository.TransactionRepository;

@Service
public class TransactionService {
  private final TransactionRepository transactionRepository;
  private final PortfolioService portfolioService;
  private final PositionService positionService;
  private final MarketDataClient marketDataClient;
  private final InstrumentClient instrumentClient;
  private final TransactionTemplate transactionTemplate;

  public TransactionService(
      TransactionRepository transactionRepository,
      PortfolioService portfolioService,
      PositionService positionService,
      MarketDataClient marketDataClient,
      InstrumentClient instrumentClient,
      PlatformTransactionManager transactionManager) {
    this.transactionRepository = transactionRepository;
    this.portfolioService = portfolioService;
    this.positionService = positionService;
    this.marketDataClient = marketDataClient;
    this.instrumentClient = instrumentClient;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public TransactionResponse createTransaction(
      UUID userId, UUID portfolioId, CreateTransactionRequest request) {

    InstrumentResponse foundInstrument = instrumentClient.getInstrumentById(request.instrumentId());
    if (foundInstrument == null) {
      throw new ResourceNotFoundException(
          "Instrument not found with id: " + request.instrumentId());
    }

    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);

    LocalDate txDate = request.executedAt() != null ? request.executedAt().toLocalDate() : null;
    ResolvedFinancials financials = resolveFinancials(
        foundInstrument, portfolio, request.currency(), request.price(),
        request.quantity(), request.commission(), request.baseAmount(), txDate);

    return transactionTemplate.execute(status -> {
      Transaction transaction = request.toEntity(portfolio);
      transaction.setExchangeRate(financials.exchangeRate());
      transaction.setBaseAmount(financials.baseAmount());

      Transaction savedTransaction = transactionRepository.save(transaction);

      positionService.recalculatePosition(
          portfolio, foundInstrument.id(), foundInstrument.currency(), request.seriesName());
      return TransactionResponse.from(savedTransaction);
    });
  }

  public TransactionResponse updateTransaction(
      UUID userId, UUID portfolioId, UUID transactionId, CreateTransactionRequest request) {

    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    Transaction transaction = transactionRepository.findByIdAndPortfolioId(transactionId, portfolioId)
        .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

    InstrumentResponse foundInstrument = instrumentClient.getInstrumentById(request.instrumentId());
    if (foundInstrument == null) {
      throw new ResourceNotFoundException("Instrument not found with id: " + request.instrumentId());
    }

    UUID oldInstrumentId = transaction.getInstrumentId();
    Currency oldCurrency = transaction.getCurrency() != null ? transaction.getCurrency() : portfolio.getBaseCurrency();
    String oldSeries = (transaction.getSeriesName() != null && !transaction.getSeriesName().trim().isBlank())
        ? transaction.getSeriesName().trim() : null;
    String newSeries = (request.seriesName() != null && !request.seriesName().trim().isBlank())
        ? request.seriesName().trim() : null;

    boolean sameInstrumentAndSeries = oldInstrumentId.equals(request.instrumentId())
        && Objects.equals(oldSeries, newSeries);

    LocalDate txDate = request.executedAt() != null ? request.executedAt().toLocalDate() : null;
    ResolvedFinancials financials = resolveFinancials(
        foundInstrument, portfolio, request.currency(), request.price(),
        request.quantity(), request.commission(), request.baseAmount(), txDate);

    return transactionTemplate.execute(status -> {
      transaction.setInstrumentId(request.instrumentId());
      transaction.setTransactionType(request.transactionType());
      transaction.setQuantity(request.quantity());
      transaction.setPrice(request.price());
      transaction.setCommission(request.commission());
      transaction.setCurrency(request.currency());
      transaction.setExchangeRate(financials.exchangeRate());
      transaction.setBaseAmount(financials.baseAmount());
      if (request.executedAt() != null) {
        transaction.setExecutedAt(request.executedAt());
      }
      transaction.setSeriesName(request.seriesName());
      transaction.setBondRate(request.bondRate());
      transaction.setBondMargin(request.bondMargin());
      transaction.setTermMonths(request.termMonths());

      Transaction savedTransaction = transactionRepository.save(transaction);

      if (!sameInstrumentAndSeries) {
        positionService.recalculatePosition(portfolio, oldInstrumentId, oldCurrency, oldSeries);
      }
      positionService.recalculatePosition(portfolio, foundInstrument.id(), foundInstrument.currency(), request.seriesName());

      return TransactionResponse.from(savedTransaction);
    });
  }

  public void deleteTransaction(UUID userId, UUID portfolioId, UUID transactionId) {
    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    Transaction transaction = transactionRepository.findByIdAndPortfolioId(transactionId, portfolioId)
        .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

    UUID instrumentId = transaction.getInstrumentId();
    String seriesName = (transaction.getSeriesName() != null && !transaction.getSeriesName().trim().isBlank())
        ? transaction.getSeriesName().trim() : null;

    Currency cur = transaction.getCurrency() != null ? transaction.getCurrency() : portfolio.getBaseCurrency();

    transactionTemplate.executeWithoutResult(status -> {
      transactionRepository.delete(transaction);
      positionService.recalculatePosition(portfolio, instrumentId, cur, seriesName);
    });
  }

  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public List<TransactionResponse> getTransactions(UUID userId, UUID portfolioId) {
    portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    return transactionRepository.findAllByPortfolioIdOrderByExecutedAtDesc(portfolioId).stream()
        .map(TransactionResponse::from)
        .toList();
  }

  private record ResolvedFinancials(BigDecimal exchangeRate, BigDecimal baseAmount) {}

  private ResolvedFinancials resolveFinancials(
      InstrumentResponse instrument,
      Portfolio portfolio,
      Currency txCurrency,
      BigDecimal price,
      BigDecimal quantity,
      BigDecimal commission,
      BigDecimal customBaseAmount,
      LocalDate txDate) {

    BigDecimal exchangeRate = BigDecimal.ONE;
    if (!instrument.currency().equals(txCurrency)) {
      exchangeRate = marketDataClient.getExchangeRate(instrument.currency(), txCurrency, txDate);
    }

    BigDecimal baseAmount = customBaseAmount;
    if (baseAmount == null) {
      BigDecimal total = price.multiply(quantity);
      BigDecimal comm = commission != null ? commission : BigDecimal.ZERO;
      if (txCurrency.equals(portfolio.getBaseCurrency())) {
        baseAmount = total.add(comm);
      } else {
        BigDecimal fxRateToBase = marketDataClient.getExchangeRate(txCurrency, portfolio.getBaseCurrency(), txDate);
        baseAmount = total.multiply(fxRateToBase).add(comm.multiply(fxRateToBase)).setScale(4, RoundingMode.HALF_UP);
      }
    }

    return new ResolvedFinancials(exchangeRate, baseAmount);
  }
}
