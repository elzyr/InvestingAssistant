package pl.investassist.portfolio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.common.util.PaginationUtils;
import pl.investassist.common.util.SeriesUtils;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.instrument.service.InstrumentService;
import pl.investassist.portfolio.client.MarketDataClient;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.TransactionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.TransactionType;
import pl.investassist.portfolio.repository.PortfolioRepository;
import pl.investassist.portfolio.repository.TransactionRepository;

@Service
public class TransactionService {

  private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

  private final TransactionRepository transactionRepository;
  private final PortfolioRepository portfolioRepository;
  private final PortfolioService portfolioService;
  private final PositionService positionService;
  private final MarketDataClient marketDataClient;
  private final InstrumentService instrumentService;
  private final TransactionTemplate transactionTemplate;

  public TransactionService(
      TransactionRepository transactionRepository,
      PortfolioRepository portfolioRepository,
      PortfolioService portfolioService,
      PositionService positionService,
      MarketDataClient marketDataClient,
      InstrumentService instrumentService,
      PlatformTransactionManager transactionManager) {
    this.transactionRepository = transactionRepository;
    this.portfolioRepository = portfolioRepository;
    this.portfolioService = portfolioService;
    this.positionService = positionService;
    this.marketDataClient = marketDataClient;
    this.instrumentService = instrumentService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
  }

  public TransactionResponse createTransaction(
      UUID userId, UUID portfolioId, CreateTransactionRequest request) {

    InstrumentResponse foundInstrument = instrumentService.getInstrument(request.instrumentId());
    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    validateCommissionNotExceedingGross(request);

    LocalDate txDate = request.executedAt() != null ? request.executedAt().toLocalDate() : null;
    ResolvedFinancials financials = resolveFinancials(
        foundInstrument, portfolio, request.transactionType(), request.currency(), request.price(),
        request.quantity(), request.commission(), request.baseAmount(), request.exchangeRateToBase(), txDate);
    String normalizedSeries = SeriesUtils.normalize(request.seriesName());

    return transactionTemplate.execute(status -> {
      Portfolio lockedPortfolio = lockPortfolio(portfolioId);

      Transaction transaction = request.toEntity(lockedPortfolio);
      transaction.setExchangeRate(financials.exchangeRate());
      transaction.setBaseAmount(financials.baseAmount());
      transaction.setExchangeRateToBase(financials.exchangeRateToBase());

      Transaction savedTransaction = transactionRepository.save(transaction);

      positionService.recalculatePosition(
          lockedPortfolio, foundInstrument.id(), foundInstrument.currency(), normalizedSeries);
      return TransactionResponse.from(savedTransaction);
    });
  }

  public TransactionResponse updateTransaction(
      UUID userId, UUID portfolioId, UUID transactionId, CreateTransactionRequest request) {

    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    InstrumentResponse foundInstrument = instrumentService.getInstrument(request.instrumentId());
    validateCommissionNotExceedingGross(request);

    LocalDateTime preLockExecutedAt = request.executedAt();
    if (preLockExecutedAt == null) {
      preLockExecutedAt = transactionRepository.findByIdAndPortfolioId(transactionId, portfolioId)
          .map(Transaction::getExecutedAt)
          .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));
    }

    LocalDate txDate = preLockExecutedAt != null ? preLockExecutedAt.toLocalDate() : null;
    ResolvedFinancials financials = resolveFinancials(
        foundInstrument, portfolio, request.transactionType(), request.currency(), request.price(),
        request.quantity(), request.commission(), request.baseAmount(), request.exchangeRateToBase(), txDate);
    String newSeries = SeriesUtils.normalize(request.seriesName());

    return transactionTemplate.execute(status -> {
      Portfolio lockedPortfolio = lockPortfolio(portfolioId);
      Transaction transaction = transactionRepository.findByIdAndPortfolioId(transactionId, portfolioId)
          .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

      UUID oldInstrumentId = transaction.getInstrumentId();
      String oldSeries = SeriesUtils.normalize(transaction.getSeriesName());
      boolean sameInstrumentAndSeries = oldInstrumentId.equals(request.instrumentId())
          && Objects.equals(oldSeries, newSeries);
      Currency oldInstrumentCurrency = !sameInstrumentAndSeries ? resolveInstrumentCurrency(oldInstrumentId) : null;

      transaction.setPortfolio(lockedPortfolio);
      transaction.setInstrumentId(request.instrumentId());
      transaction.setTransactionType(request.transactionType());
      transaction.setQuantity(request.quantity());
      transaction.setPrice(request.price());
      transaction.setCommission(request.commission());
      transaction.setCurrency(request.currency());
      transaction.setExchangeRate(financials.exchangeRate());
      transaction.setBaseAmount(financials.baseAmount());
      transaction.setExchangeRateToBase(financials.exchangeRateToBase());
      if (request.executedAt() != null) {
        transaction.setExecutedAt(request.executedAt());
      }
      transaction.setSeriesName(newSeries);
      transaction.setBondRate(request.bondRate());
      transaction.setBondMargin(request.bondMargin());
      transaction.setTermMonths(request.termMonths());

      Transaction savedTransaction = transactionRepository.save(transaction);

      if (!sameInstrumentAndSeries) {
        positionService.recalculatePosition(lockedPortfolio, oldInstrumentId, oldInstrumentCurrency, oldSeries);
      }
      positionService.recalculatePosition(lockedPortfolio, foundInstrument.id(), foundInstrument.currency(), newSeries);

      return TransactionResponse.from(savedTransaction);
    });
  }

  public void deleteTransaction(UUID userId, UUID portfolioId, UUID transactionId) {
    portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);

    transactionTemplate.executeWithoutResult(status -> {
      Portfolio lockedPortfolio = lockPortfolio(portfolioId);
      Transaction transaction = transactionRepository.findByIdAndPortfolioId(transactionId, portfolioId)
          .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

      UUID instrumentId = transaction.getInstrumentId();
      String seriesName = SeriesUtils.normalize(transaction.getSeriesName());
      Currency instrumentCurrency = resolveInstrumentCurrency(instrumentId);

      transactionRepository.delete(transaction);
      positionService.recalculatePosition(lockedPortfolio, instrumentId, instrumentCurrency, seriesName);
    });
  }

  @Transactional(readOnly = true)
  public List<TransactionResponse> getTransactions(UUID userId, UUID portfolioId, Integer page, Integer size) {
    portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    Pageable pageable = PaginationUtils.toPageable(page, size);
    Page<Transaction> paged = transactionRepository.findAllByPortfolioIdOrderByExecutedAtDesc(portfolioId, pageable);
    return paged != null ? paged.getContent().stream().map(TransactionResponse::from).toList() : List.of();
  }

  private void validateCommissionNotExceedingGross(CreateTransactionRequest request) {
    if (request.transactionType() == TransactionType.SELL || request.transactionType() == TransactionType.DIVIDEND) {
      BigDecimal gross = request.price().multiply(request.quantity());
      BigDecimal comm = request.commission() != null ? request.commission() : BigDecimal.ZERO;
      if (comm.compareTo(gross) > 0) {
        throw new IllegalArgumentException(
            "Commission cannot exceed gross amount on " + request.transactionType());
      }
    }
  }

  private Currency resolveInstrumentCurrency(UUID instrumentId) {
    try {
      InstrumentResponse instrument = instrumentService.getInstrument(instrumentId);
      return instrument != null ? instrument.currency() : null;
    } catch (Exception e) {
      log.warn("Failed to resolve instrument currency for {}: {}", instrumentId, e.getMessage());
      return null;
    }
  }

  private Portfolio lockPortfolio(UUID portfolioId) {
    return portfolioRepository.findByIdWithLock(portfolioId)
        .orElseThrow(() -> new ResourceNotFoundException("Portfolio not found: " + portfolioId));
  }

  private record ResolvedFinancials(
      BigDecimal exchangeRate, BigDecimal baseAmount, BigDecimal exchangeRateToBase) {}

  private ResolvedFinancials resolveFinancials(
      InstrumentResponse instrument,
      Portfolio portfolio,
      TransactionType txType,
      Currency txCurrency,
      BigDecimal price,
      BigDecimal quantity,
      BigDecimal commission,
      BigDecimal customBaseAmount,
      BigDecimal customExchangeRateToBase,
      LocalDate txDate) {

    BigDecimal exchangeRate = BigDecimal.ONE;
    if (!instrument.currency().equals(txCurrency)) {
      exchangeRate = marketDataClient.getExchangeRate(instrument.currency(), txCurrency, txDate);
    }

    BigDecimal exchangeRateToBase = customExchangeRateToBase;
    if (exchangeRateToBase == null) {
      if (txCurrency.equals(portfolio.getBaseCurrency())) {
        exchangeRateToBase = BigDecimal.ONE;
      } else {
        exchangeRateToBase = marketDataClient.getExchangeRate(txCurrency, portfolio.getBaseCurrency(), txDate);
      }
    }

    BigDecimal baseAmount = customBaseAmount;
    if (baseAmount == null) {
      BigDecimal total = price.multiply(quantity);
      BigDecimal comm = commission != null ? commission : BigDecimal.ZERO;
      BigDecimal netTotal = (txType == TransactionType.SELL || txType == TransactionType.DIVIDEND) ? total.subtract(comm) : total.add(comm);
      if (txCurrency.equals(portfolio.getBaseCurrency())) {
        baseAmount = netTotal;
      } else {
        baseAmount = netTotal.multiply(exchangeRateToBase).setScale(4, RoundingMode.HALF_UP);
      }
    }

    return new ResolvedFinancials(exchangeRate, baseAmount, exchangeRateToBase);
  }
}
