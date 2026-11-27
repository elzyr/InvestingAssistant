package pl.investassist.portfolio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.common.util.PaginationUtils;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.instrument.service.InstrumentService;
import pl.investassist.portfolio.port.MarketDataPort;
import pl.investassist.portfolio.valuation.BondValuationEngine;
import pl.investassist.portfolio.valuation.PortfolioLedger;
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
  private final PortfolioRepository portfolioRepository;
  private final PortfolioService portfolioService;
  private final MarketDataPort marketDataClient;
  private final InstrumentService instrumentService;
  private final PortfolioCacheService portfolioCacheService;
  private final TransactionTemplate transactionTemplate;
  private final Clock clock;

  @Autowired
  public TransactionService(
      TransactionRepository transactionRepository,
      PortfolioRepository portfolioRepository,
      PortfolioService portfolioService,
      MarketDataPort marketDataClient,
      InstrumentService instrumentService,
      PortfolioCacheService portfolioCacheService,
      PlatformTransactionManager transactionManager) {
    this(
        transactionRepository,
        portfolioRepository,
        portfolioService,
        marketDataClient,
        instrumentService,
        portfolioCacheService,
        transactionManager,
        Clock.system(ZoneId.of("Europe/Warsaw")));
  }

  TransactionService(
      TransactionRepository transactionRepository,
      PortfolioRepository portfolioRepository,
      PortfolioService portfolioService,
      MarketDataPort marketDataClient,
      InstrumentService instrumentService,
      PortfolioCacheService portfolioCacheService,
      PlatformTransactionManager transactionManager,
      Clock clock) {
    this.transactionRepository = transactionRepository;
    this.portfolioRepository = portfolioRepository;
    this.portfolioService = portfolioService;
    this.marketDataClient = marketDataClient;
    this.instrumentService = instrumentService;
    this.portfolioCacheService = portfolioCacheService;
    this.transactionTemplate = new TransactionTemplate(transactionManager);
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
  }

  public TransactionResponse createTransaction(
      UUID userId, UUID portfolioId, CreateTransactionRequest request) {

    InstrumentResponse foundInstrument = instrumentService.getInstrument(request.instrumentId());
    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    validateCommissionNotExceedingGross(request);

    LocalDate txDate = request.executedAt() != null ? request.executedAt().toLocalDate() : null;
    ResolvedFinancials financials = resolveFinancials(
        foundInstrument, portfolio, request.transactionType(), request.currency(), request.price(),
        request.quantity(), request.commission(), request.baseAmount(), request.exchangeRateToBase(),
        request.instrumentToTxRate(), request.fxFeePct(), () -> txDate);
    String normalizedSeries = BondValuationEngine.normalizeSeries(request.seriesName());
    validateBondRequest(foundInstrument, normalizedSeries, request);

    return executePortfolioWrite(portfolioId, lockedPortfolio -> {
      LocalDateTime executedAt = request.executedAt() != null ? request.executedAt() : LocalDateTime.now(clock);
      Transaction transaction = applyTransactionChanges(
          new Transaction(), lockedPortfolio, request, financials, normalizedSeries, executedAt);

      if (request.transactionType() == TransactionType.SELL) {
        validateSeriesHoldings(
            portfolioId, foundInstrument.id(), foundInstrument, normalizedSeries, null, transaction);
      }

      Transaction savedTransaction = transactionRepository.save(transaction);
      return TransactionResponse.from(savedTransaction);
    });
  }

  public TransactionResponse updateTransaction(
      UUID userId, UUID portfolioId, UUID transactionId, CreateTransactionRequest request) {

    Portfolio portfolio = portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    InstrumentResponse foundInstrument = instrumentService.getInstrument(request.instrumentId());
    validateCommissionNotExceedingGross(request);

    Supplier<LocalDate> txDateSupplier = request.executedAt() != null
        ? () -> request.executedAt().toLocalDate()
        : new Supplier<>() {
            private LocalDate cached;

            @Override
            public LocalDate get() {
              if (cached == null) {
                cached = transactionRepository.findByIdAndPortfolioId(transactionId, portfolioId)
                    .map(Transaction::getExecutedAt)
                    .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId))
                    .toLocalDate();
              }
              return cached;
            }
          };
    ResolvedFinancials financials = resolveFinancials(
        foundInstrument, portfolio, request.transactionType(), request.currency(), request.price(),
        request.quantity(), request.commission(), request.baseAmount(), request.exchangeRateToBase(),
        request.instrumentToTxRate(), request.fxFeePct(), txDateSupplier);
    String newSeries = BondValuationEngine.normalizeSeries(request.seriesName());
    validateBondRequest(foundInstrument, newSeries, request);

    return executePortfolioWrite(portfolioId, lockedPortfolio -> {
      Transaction transaction = transactionRepository.findByIdAndPortfolioId(transactionId, portfolioId)
          .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

      UUID oldInstrumentId = transaction.getInstrumentId();
      String oldSeries = BondValuationEngine.normalizeSeries(transaction.getSeriesName());
      TransactionType oldType = transaction.getTransactionType();
      boolean oldHasBondMetadata = BondValuationEngine.hasBondMetadata(transaction);

      applyTransactionChanges(
          transaction, lockedPortfolio, request, financials, newSeries, transaction.getExecutedAt());

      boolean sameInstrument = oldInstrumentId.equals(request.instrumentId());
      boolean sameInstrumentAndSeries;
      if (!sameInstrument) {
        sameInstrumentAndSeries = false;
      } else {
        InstrumentType sharedType = foundInstrument != null ? foundInstrument.type() : null;
        String effectiveOldSeries = BondValuationEngine.isBond(sharedType, oldSeries, oldHasBondMetadata)
            ? oldSeries
            : null;
        String effectiveNewSeries = BondValuationEngine.isBond(
            sharedType, newSeries, BondValuationEngine.hasBondMetadata(transaction))
            ? newSeries
            : null;
        sameInstrumentAndSeries = Objects.equals(effectiveOldSeries, effectiveNewSeries);
      }

      if (!sameInstrumentAndSeries && oldType == TransactionType.BUY) {
        InstrumentResponse oldInstrument = oldInstrumentId.equals(request.instrumentId())
            ? foundInstrument
            : instrumentService.getInstrument(oldInstrumentId);
        validateSeriesHoldings(portfolioId, oldInstrumentId, oldInstrument, oldSeries, transactionId, null);
      }
      if (request.transactionType() == TransactionType.SELL
          || (sameInstrumentAndSeries && oldType == TransactionType.BUY)) {
        validateSeriesHoldings(
            portfolioId, request.instrumentId(), foundInstrument, newSeries, transactionId, transaction);
      }

      Transaction savedTransaction = transactionRepository.save(transaction);
      return TransactionResponse.from(savedTransaction);
    });
  }

  public void deleteTransaction(UUID userId, UUID portfolioId, UUID transactionId) {
    portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);

    executePortfolioWrite(portfolioId, lockedPortfolio -> {
      Transaction transaction = transactionRepository.findByIdAndPortfolioId(transactionId, portfolioId)
          .orElseThrow(() -> new ResourceNotFoundException("Transaction not found with id: " + transactionId));

      if (transaction.getTransactionType() == TransactionType.BUY) {
        String seriesName = BondValuationEngine.normalizeSeries(transaction.getSeriesName());
        InstrumentResponse inst = instrumentService.getInstrument(transaction.getInstrumentId());
        validateSeriesHoldings(
            portfolioId, transaction.getInstrumentId(), inst, seriesName, transactionId, null);
      }

      transactionRepository.delete(transaction);
      return null;
    });
  }

  @Transactional(readOnly = true)
  public List<TransactionResponse> getTransactions(UUID userId, UUID portfolioId, Integer page, Integer size) {
    portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    Pageable pageable = PaginationUtils.toPageable(page, size);
    Page<Transaction> paged = transactionRepository.findAllByPortfolioIdOrderByExecutedAtDesc(portfolioId, pageable);
    return paged != null ? paged.getContent().stream().map(TransactionResponse::from).toList() : List.of();
  }

  private <T> T executePortfolioWrite(
      UUID portfolioId, java.util.function.Function<Portfolio, T> work) {
    return transactionTemplate.execute(status -> {
      Portfolio lockedPortfolio = lockPortfolio(portfolioId);
      T result = work.apply(lockedPortfolio);
      portfolioCacheService.evictPortfolioAfterCommit(portfolioId);
      return result;
    });
  }

  private void validateSeriesHoldings(
      UUID portfolioId,
      UUID instrumentId,
      InstrumentResponse instrument,
      String normalizedSeries,
      UUID excludedTxId,
      Transaction candidateTx) {
    boolean isBond = isBondForValidation(instrument, normalizedSeries);
    List<Transaction> existingGroup = transactionRepository
        .findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId);
    List<Transaction> simulated = new java.util.ArrayList<>(existingGroup != null ? existingGroup.size() + 1 : 1);
    if (existingGroup != null) {
      for (Transaction existing : existingGroup) {
        if (excludedTxId != null && excludedTxId.equals(existing.getId())) {
          continue;
        }
        if (!isBond || Objects.equals(BondValuationEngine.normalizeSeries(existing.getSeriesName()), normalizedSeries)) {
          simulated.add(existing);
        }
      }
    }
    if (candidateTx != null) {
      simulated.add(candidateTx);
    }
    PortfolioLedger.replayPosition(simulated, true);
  }

  private static boolean isBondForValidation(InstrumentResponse inst, String normalizedSeries) {
    return BondValuationEngine.isBond(
        inst != null ? inst.type() : null, normalizedSeries, false);
  }

  private static void validateBondRequest(
      InstrumentResponse instrument, String normalizedSeries, CreateTransactionRequest request) {
    boolean hasMetadata =
        request.bondRate() != null || request.bondMargin() != null || request.termMonths() != null;
    if (!BondValuationEngine.isBond(
        instrument != null ? instrument.type() : null, normalizedSeries, hasMetadata)) {
      return;
    }
    BondValuationEngine.BondFamily family = BondValuationEngine.findBondFamily(normalizedSeries)
        .orElseThrow(() -> new IllegalArgumentException(
            "Unsupported or unknown treasury bond series: " + request.seriesName()));
    if (request.termMonths() != null && request.termMonths() != family.termMonths()) {
      throw new IllegalArgumentException(
          "Bond series " + family.name() + " requires termMonths=" + family.termMonths()
              + ", got " + request.termMonths());
    }
  }

  private Transaction applyTransactionChanges(
      Transaction target,
      Portfolio lockedPortfolio,
      CreateTransactionRequest request,
      ResolvedFinancials financials,
      String normalizedSeries,
      LocalDateTime fallbackExecutedAt) {
    target.setPortfolio(lockedPortfolio);
    target.setInstrumentId(request.instrumentId());
    target.setTransactionType(request.transactionType());
    target.setQuantity(request.quantity());
    target.setPrice(request.price());
    target.setCommission(request.commission());
    target.setCurrency(request.currency());
    target.setExchangeRate(financials.exchangeRate());
    target.setInstrumentToTxRate(financials.exchangeRate());
    target.setBaseAmount(financials.baseAmount());
    target.setExchangeRateToBase(financials.exchangeRateToBase());
    if (request.executedAt() != null) {
      target.setExecutedAt(request.executedAt());
    } else if (target.getExecutedAt() == null) {
      target.setExecutedAt(fallbackExecutedAt);
    }
    target.setSeriesName(normalizedSeries);
    target.setBondRate(request.bondRate());
    target.setBondMargin(request.bondMargin());
    target.setTermMonths(request.termMonths());
    target.setWithholdingTaxPct(request.withholdingTaxPct());
    target.setFxFeePct(request.fxFeePct());
    return target;
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

  private static boolean hasCustomRateInBaseCurrency(
      Portfolio portfolio, Currency txCurrency, BigDecimal customExchangeRateToBase) {
    return customExchangeRateToBase != null
        && txCurrency != null
        && portfolio != null
        && txCurrency.equals(portfolio.getBaseCurrency());
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
      BigDecimal customInstrumentToTxRate,
      BigDecimal fxFeePct,
      Supplier<LocalDate> txDateSupplier) {

    Currency baseCurrency = portfolio.getBaseCurrency();
    boolean crossCurrencyBuy = txType == TransactionType.BUY
        && instrument.currency() != null
        && !instrument.currency().equals(txCurrency);
    boolean customInstrumentToBase = crossCurrencyBuy
        && hasCustomRateInBaseCurrency(portfolio, txCurrency, customExchangeRateToBase);

    BigDecimal exchangeRate = BigDecimal.ONE;
    if (crossCurrencyBuy) {
      if (customInstrumentToTxRate != null) {
        exchangeRate = customInstrumentToTxRate;
      } else if (customInstrumentToBase) {
        exchangeRate = customExchangeRateToBase;
      } else {
        exchangeRate = marketDataClient.getExchangeRate(instrument.currency(), txCurrency, txDateSupplier.get());
      }
    }
    if (exchangeRate.scale() > 8) {
      exchangeRate = exchangeRate.setScale(8, RoundingMode.HALF_UP);
    }

    boolean sameAsBaseCurrency = txCurrency != null && baseCurrency != null && txCurrency.equals(baseCurrency);
    BigDecimal exchangeRateToBase = (customInstrumentToBase || sameAsBaseCurrency) ? BigDecimal.ONE : customExchangeRateToBase;
    if (exchangeRateToBase == null) {
      LocalDate txDate = txDateSupplier.get();
      exchangeRateToBase = txDate != null
          ? marketDataClient.getNbpTaxRate(txCurrency, baseCurrency, txDate)
          : marketDataClient.getExchangeRate(txCurrency, baseCurrency, null);
    }
    if (exchangeRateToBase.scale() > 8) {
      exchangeRateToBase = exchangeRateToBase.setScale(8, RoundingMode.HALF_UP);
    }

    BigDecimal baseAmount = customBaseAmount;
    if (baseAmount == null) {
      BigDecimal netTotal = PortfolioLedger.computeNetTotal(txType, price, quantity, commission);
      boolean sameUnitRate = txCurrency.equals(baseCurrency) && exchangeRateToBase.compareTo(BigDecimal.ONE) == 0;
      BigDecimal baseBeforeFee = sameUnitRate ? netTotal : netTotal.multiply(exchangeRateToBase);
      baseAmount = applyFxFee(txType, baseBeforeFee, fxFeePct, sameUnitRate);
    }

    return new ResolvedFinancials(exchangeRate, baseAmount, exchangeRateToBase);
  }

  private static BigDecimal applyFxFee(
      TransactionType txType, BigDecimal baseBeforeFee, BigDecimal fxFeePct, boolean sameUnitRate) {
    if (fxFeePct != null && fxFeePct.compareTo(BigDecimal.ZERO) > 0) {
      BigDecimal feeFraction = fxFeePct.divide(new BigDecimal("100"), 8, RoundingMode.HALF_UP);
      BigDecimal multiplier =
          txType == TransactionType.BUY
              ? BigDecimal.ONE.add(feeFraction)
              : BigDecimal.ONE.subtract(feeFraction).max(BigDecimal.ZERO);
      return baseBeforeFee.multiply(multiplier).setScale(4, RoundingMode.HALF_UP);
    }
    return sameUnitRate ? baseBeforeFee : baseBeforeFee.setScale(4, RoundingMode.HALF_UP);
  }
}
