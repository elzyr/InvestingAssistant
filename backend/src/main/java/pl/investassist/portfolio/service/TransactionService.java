package pl.investassist.portfolio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.ResourceNotFoundException;
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
  private final TransactionRepository transactionRepository;
  private final PortfolioService portfolioService;
  private final PositionService positionService;
  private final MarketDataClient marketDataClient;
  private final InstrumentService instrumentService;
  private final TransactionTemplate transactionTemplate;

  @Autowired(required = false)
  private PortfolioRepository portfolioRepository;

  public TransactionService(
      TransactionRepository transactionRepository,
      PortfolioService portfolioService,
      PositionService positionService,
      MarketDataClient marketDataClient,
      InstrumentService instrumentService,
      PlatformTransactionManager transactionManager) {
    this.transactionRepository = transactionRepository;
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

    if (request.transactionType() == TransactionType.SELL) {
      BigDecimal gross = request.price().multiply(request.quantity());
      BigDecimal comm = request.commission() != null ? request.commission() : BigDecimal.ZERO;
      if (comm.compareTo(gross) > 0) {
        throw new IllegalArgumentException("Commission cannot exceed gross proceeds on SELL");
      }
    }

    LocalDate txDate = request.executedAt() != null ? request.executedAt().toLocalDate() : null;
    ResolvedFinancials financials = resolveFinancials(
        foundInstrument, portfolio, request.transactionType(), request.currency(), request.price(),
        request.quantity(), request.commission(), request.baseAmount(), request.exchangeRateToBase(), txDate);

    return transactionTemplate.execute(status -> {
      Portfolio lockedPortfolio = portfolio;
      if (portfolioRepository != null) {
        lockedPortfolio = portfolioRepository.findByIdWithLock(portfolioId).orElse(portfolio);
      }

      Transaction transaction = request.toEntity(lockedPortfolio);
      transaction.setExchangeRate(financials.exchangeRate());
      transaction.setBaseAmount(financials.baseAmount());
      transaction.setExchangeRateToBase(financials.exchangeRateToBase());

      Transaction savedTransaction = transactionRepository.save(transaction);

      positionService.recalculatePosition(
          lockedPortfolio, foundInstrument.id(), foundInstrument.currency(), request.seriesName());
      return TransactionResponse.from(savedTransaction);
    });
  }

  public TransactionResponse updateTransaction(
      UUID userId, UUID portfolioId, UUID transactionId, CreateTransactionRequest request) {

    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    Transaction transaction = transactionRepository.findByIdAndPortfolioId(transactionId, portfolioId)
        .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

    InstrumentResponse foundInstrument = instrumentService.getInstrument(request.instrumentId());

    if (request.transactionType() == TransactionType.SELL) {
      BigDecimal gross = request.price().multiply(request.quantity());
      BigDecimal comm = request.commission() != null ? request.commission() : BigDecimal.ZERO;
      if (comm.compareTo(gross) > 0) {
        throw new IllegalArgumentException("Commission cannot exceed gross proceeds on SELL");
      }
    }

    UUID oldInstrumentId = transaction.getInstrumentId();
    String oldSeries = (transaction.getSeriesName() != null && !transaction.getSeriesName().trim().isBlank())
        ? transaction.getSeriesName().trim() : null;
    String newSeries = (request.seriesName() != null && !request.seriesName().trim().isBlank())
        ? request.seriesName().trim() : null;

    boolean sameInstrumentAndSeries = oldInstrumentId.equals(request.instrumentId())
        && Objects.equals(oldSeries, newSeries);

    LocalDateTime effectiveExecutedAt = request.executedAt() != null ? request.executedAt() : transaction.getExecutedAt();
    LocalDate txDate = effectiveExecutedAt != null ? effectiveExecutedAt.toLocalDate() : null;
    ResolvedFinancials financials = resolveFinancials(
        foundInstrument, portfolio, request.transactionType(), request.currency(), request.price(),
        request.quantity(), request.commission(), request.baseAmount(), request.exchangeRateToBase(), txDate);

    Currency oldInstrumentCurrency = null;
    if (!sameInstrumentAndSeries) {
      try {
        InstrumentResponse oldInst = instrumentService.getInstrument(oldInstrumentId);
        if (oldInst != null) {
          oldInstrumentCurrency = oldInst.currency();
        }
      } catch (Exception ignored) {
      }
    }

    final Currency finalOldCur = oldInstrumentCurrency;
    return transactionTemplate.execute(status -> {
      Portfolio lockedPortfolio = portfolio;
      if (portfolioRepository != null) {
        lockedPortfolio = portfolioRepository.findByIdWithLock(portfolioId).orElse(portfolio);
      }

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
      transaction.setSeriesName(request.seriesName());
      transaction.setBondRate(request.bondRate());
      transaction.setBondMargin(request.bondMargin());
      transaction.setTermMonths(request.termMonths());

      Transaction savedTransaction = transactionRepository.save(transaction);

      if (!sameInstrumentAndSeries) {
        positionService.recalculatePosition(lockedPortfolio, oldInstrumentId, finalOldCur, oldSeries);
      }
      positionService.recalculatePosition(lockedPortfolio, foundInstrument.id(), foundInstrument.currency(), request.seriesName());

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

    Currency instrumentCurrency = null;
    try {
      InstrumentResponse instrument = instrumentService.getInstrument(instrumentId);
      if (instrument != null) {
        instrumentCurrency = instrument.currency();
      }
    } catch (Exception ignored) {
    }

    final Currency finalCur = instrumentCurrency;
    transactionTemplate.executeWithoutResult(status -> {
      Portfolio lockedPortfolio = portfolio;
      if (portfolioRepository != null) {
        lockedPortfolio = portfolioRepository.findByIdWithLock(portfolioId).orElse(portfolio);
      }

      transactionRepository.delete(transaction);
      positionService.recalculatePosition(lockedPortfolio, instrumentId, finalCur, seriesName);
    });
  }

  @org.springframework.transaction.annotation.Transactional(readOnly = true)
  public List<TransactionResponse> getTransactions(UUID userId, UUID portfolioId) {
    portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    return transactionRepository.findAllByPortfolioIdOrderByExecutedAtDesc(portfolioId).stream()
        .map(TransactionResponse::from)
        .toList();
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
      BigDecimal netTotal = (txType == TransactionType.SELL) ? total.subtract(comm) : total.add(comm);
      if (txCurrency.equals(portfolio.getBaseCurrency())) {
        baseAmount = netTotal;
      } else {
        baseAmount = netTotal.multiply(exchangeRateToBase).setScale(4, RoundingMode.HALF_UP);
      }
    }

    return new ResolvedFinancials(exchangeRate, baseAmount, exchangeRateToBase);
  }
}
