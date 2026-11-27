package pl.investassist.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;
import pl.investassist.auth.dto.LoginRequest;
import pl.investassist.auth.dto.RegisterRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
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

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

  @Mock
  private TransactionRepository transactionRepository;

  @Mock
  private PortfolioRepository portfolioRepository;

  @Mock
  private PortfolioService portfolioService;

  @Mock
  private MarketDataClient marketDataClient;

  @Mock
  private InstrumentService instrumentService;

  @Mock
  private PortfolioCacheService portfolioCacheService;

  @Mock
  private PlatformTransactionManager transactionManager;

  private TransactionService transactionService;

  private UUID userId;
  private UUID portfolioId;
  private UUID instrumentId;
  private Portfolio portfolio;

  @BeforeEach
  void setUp() {
    lenient().when(transactionManager.getTransaction(any(TransactionDefinition.class)))
        .thenReturn(new SimpleTransactionStatus());
    lenient().doNothing().when(transactionManager).commit(any());

    transactionService = new TransactionService(
        transactionRepository,
        portfolioRepository,
        portfolioService,
        marketDataClient,
        instrumentService,
        portfolioCacheService,
        transactionManager);

    userId = UUID.randomUUID();
    portfolioId = UUID.randomUUID();
    instrumentId = UUID.randomUUID();

    portfolio = new Portfolio();
    portfolio.setId(portfolioId);
    portfolio.setUserId(userId);
    portfolio.setBaseCurrency(Currency.PLN);

    lenient().when(portfolioRepository.findByIdWithLock(portfolioId)).thenReturn(Optional.of(portfolio));
  }

  @Test
  void createTransaction_whenInstrumentMissing_throwsResourceNotFoundException() {
    when(instrumentService.getInstrument(instrumentId))
        .thenThrow(new ResourceNotFoundException("Instrument not found with UUID: " + instrumentId));

    CreateTransactionRequest request = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("100"),
        new BigDecimal("10"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 1, 15, 10, 0),
        "EDO1034",
        new BigDecimal("6.50"),
        new BigDecimal("1.50"),
        120,
        null);

    assertThatThrownBy(() -> transactionService.createTransaction(userId, portfolioId, request))
        .isInstanceOf(ResourceNotFoundException.class)
        .hasMessageContaining("Instrument not found with UUID: " + instrumentId);

    verify(portfolioService, never()).validateAndGetPortfolioEntity(any(), any());
    verify(transactionRepository, never()).save(any());
  }

  @Test
  void createTransaction_whenInstrumentFoundSameCurrency_calculatesBaseAmountAndRecalculatesPosition() {
    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "PL_BOND", "Polska obligacja", InstrumentType.BOND, Currency.PLN);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
      Transaction tx = i.getArgument(0);
      tx.setId(UUID.randomUUID());
      return tx;
    });

    CreateTransactionRequest request = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("100"),
        new BigDecimal("10"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 1, 15, 10, 0),
        "EDO1034",
        new BigDecimal("6.50"),
        new BigDecimal("1.50"),
        120,
        null);

    TransactionResponse response = transactionService.createTransaction(userId, portfolioId, request);

    assertThat(response).isNotNull();
    assertThat(response.baseAmount()).isEqualByComparingTo("1000");

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(captor.capture());
    Transaction saved = captor.getValue();
    assertThat(saved.getExchangeRate()).isEqualByComparingTo("1");
    assertThat(saved.getBaseAmount()).isEqualByComparingTo("1000");
  }

  @Test
  void createTransaction_whenSellTransaction_subtractsCommissionFromBaseAmount() {
    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "PL_BOND", "Polska obligacja", InstrumentType.BOND, Currency.PLN);
    Transaction priorBuy = new Transaction();
    priorBuy.setId(UUID.randomUUID());
    priorBuy.setPortfolio(portfolio);
    priorBuy.setInstrumentId(instrumentId);
    priorBuy.setTransactionType(TransactionType.BUY);
    priorBuy.setQuantity(new BigDecimal("10"));
    priorBuy.setPrice(new BigDecimal("100"));
    priorBuy.setCommission(BigDecimal.ZERO);
    priorBuy.setCurrency(Currency.PLN);
    priorBuy.setBaseAmount(new BigDecimal("1000"));
    priorBuy.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));
    priorBuy.setSeriesName("EDO0135");

    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(priorBuy));
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
      Transaction tx = i.getArgument(0);
      tx.setId(UUID.randomUUID());
      return tx;
    });

    CreateTransactionRequest request = new CreateTransactionRequest(
        instrumentId,
        TransactionType.SELL,
        new BigDecimal("100"),
        new BigDecimal("5"),
        new BigDecimal("10"),
        Currency.PLN,
        LocalDateTime.of(2025, 2, 15, 10, 0),
        "EDO0135",
        null,
        null,
        null,
        null);

    TransactionResponse response = transactionService.createTransaction(userId, portfolioId, request);

    assertThat(response).isNotNull();
    assertThat(response.baseAmount()).isEqualByComparingTo("490");
  }

  @Test
  void createTransaction_whenForeignCurrency_calculatesFxExchangeRateToBase() {
    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "VUAA", "Vanguard S&P 500", InstrumentType.ETF, Currency.USD);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(marketDataClient.getNbpTaxRate(eq(Currency.USD), eq(Currency.PLN), any()))
        .thenReturn(new BigDecimal("4.12345678"));
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
      Transaction tx = i.getArgument(0);
      tx.setId(UUID.randomUUID());
      return tx;
    });

    CreateTransactionRequest request = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("100"),
        new BigDecimal("5"),
        new BigDecimal("2"),
        Currency.USD,
        LocalDateTime.of(2025, 1, 15, 10, 0),
        null,
        null,
        null,
        null,
        null);

    TransactionResponse response = transactionService.createTransaction(userId, portfolioId, request);

    assertThat(response).isNotNull();
    assertThat(response.baseAmount()).isEqualByComparingTo("2069.9753");

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(captor.capture());
    Transaction saved = captor.getValue();
    assertThat(saved).extracting("exchangeRateToBase").isEqualTo(new BigDecimal("4.12345678"));
    assertThat(response).extracting("exchangeRateToBase").isEqualTo(new BigDecimal("4.12345678"));
  }

  @Test
  void createTransaction_whenDividendTransaction_createsAndProcessesDividend() {
    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "AAPL", "Apple Inc", InstrumentType.STOCK, Currency.USD);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(marketDataClient.getNbpTaxRate(eq(Currency.USD), eq(Currency.PLN), any()))
        .thenReturn(new BigDecimal("4.0000"));
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
      Transaction tx = i.getArgument(0);
      tx.setId(UUID.randomUUID());
      return tx;
    });

    TransactionType dividendType = TransactionType.valueOf("DIVIDEND");
    CreateTransactionRequest request = new CreateTransactionRequest(
        instrumentId,
        dividendType,
        new BigDecimal("10"),
        new BigDecimal("5"),
        BigDecimal.ZERO,
        Currency.USD,
        LocalDateTime.of(2025, 3, 1, 10, 0),
        null,
        null,
        null,
        null,
        null);

    TransactionResponse response = transactionService.createTransaction(userId, portfolioId, request);

    assertThat(response).isNotNull();
    assertThat(response.transactionType()).isEqualTo(dividendType);
    assertThat(response.baseAmount()).isEqualByComparingTo("200.0000");

    ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
    verify(transactionRepository).save(captor.capture());
    Transaction saved = captor.getValue();
    assertThat(saved.getTransactionType()).isEqualTo(dividendType);
    assertThat(saved.getBaseAmount()).isEqualByComparingTo("200.0000");
  }

  @Test
  void getTransactions_whenForeignCurrency_returnsExchangeRateToBase() {
    UUID txId = UUID.randomUUID();
    Transaction tx = new Transaction();
    tx.setId(txId);
    tx.setPortfolio(portfolio);
    tx.setInstrumentId(instrumentId);
    tx.setTransactionType(TransactionType.BUY);
    tx.setQuantity(new BigDecimal("5"));
    tx.setPrice(new BigDecimal("100"));
    tx.setCommission(BigDecimal.ZERO);
    tx.setCurrency(Currency.USD);
    tx.setBaseAmount(new BigDecimal("2000.0000"));
    tx.setExecutedAt(LocalDateTime.of(2025, 1, 15, 10, 0));
    org.springframework.test.util.ReflectionTestUtils.setField(tx, "exchangeRateToBase", new BigDecimal("4.0000"));

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.findAllByPortfolioIdOrderByExecutedAtDesc(eq(portfolioId), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(java.util.List.of(tx)));

    java.util.List<TransactionResponse> responses = transactionService.getTransactions(userId, portfolioId, null, null);

    assertThat(responses).hasSize(1);
    assertThat(responses.get(0)).extracting("exchangeRateToBase").isEqualTo(new BigDecimal("4.0000"));
  }

  @Test
  void deleteTransaction_whenFound_deletesTransaction() {
    UUID txId = UUID.randomUUID();
    Transaction tx = new Transaction();
    tx.setId(txId);
    tx.setInstrumentId(instrumentId);
    tx.setSeriesName("EDO1034");

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.findByIdAndPortfolioId(txId, portfolioId)).thenReturn(Optional.of(tx));

    transactionService.deleteTransaction(userId, portfolioId, txId);

    verify(transactionRepository).delete(tx);
  }

  @Test
  void updateTransaction_whenSeriesChanged_updatesTransactionAndValidatesOldSeries() {
    UUID txId = UUID.randomUUID();
    Transaction existingTx = new Transaction();
    existingTx.setId(txId);
    existingTx.setPortfolio(portfolio);
    existingTx.setInstrumentId(instrumentId);
    existingTx.setTransactionType(TransactionType.BUY);
    existingTx.setQuantity(new BigDecimal("10"));
    existingTx.setPrice(new BigDecimal("100"));
    existingTx.setCurrency(Currency.PLN);
    existingTx.setSeriesName("EDO1034");
    existingTx.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.findByIdAndPortfolioId(txId, portfolioId)).thenReturn(Optional.of(existingTx));

    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "PL_BOND", "Polska obligacja", InstrumentType.BOND, Currency.PLN);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    CreateTransactionRequest updateRequest = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("100"),
        new BigDecimal("15"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 3, 1, 10, 0),
        "COI1028",
        new BigDecimal("7.00"),
        new BigDecimal("1.25"),
        48,
        null);

    TransactionResponse response = transactionService.updateTransaction(userId, portfolioId, txId, updateRequest);

    assertThat(response).isNotNull();
    assertThat(response.quantity()).isEqualByComparingTo("15");
    assertThat(response.seriesName()).isEqualTo("COI1028");
    assertThat(response.baseAmount()).isEqualByComparingTo("1500");

    verify(transactionRepository).findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId);
  }

  @Test
  void createTransaction_whenSellCommissionExceedsGrossProceeds_throwsIllegalArgumentException() {
    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "VUAA", "Vanguard S&P 500", InstrumentType.ETF, Currency.PLN);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);

    CreateTransactionRequest request = new CreateTransactionRequest(
        instrumentId,
        TransactionType.SELL,
        new BigDecimal("100"),
        new BigDecimal("1"),
        new BigDecimal("150"),
        Currency.PLN,
        LocalDateTime.of(2025, 2, 15, 10, 0),
        null,
        null,
        null,
        null,
        null);

    assertThatThrownBy(() -> transactionService.createTransaction(userId, portfolioId, request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void updateTransaction_whenExecutedAtIsNullInRequest_usesExistingTransactionDateForFxLookup() {
    UUID txId = UUID.randomUUID();
    Transaction existingTx = new Transaction();
    existingTx.setId(txId);
    existingTx.setPortfolio(portfolio);
    existingTx.setInstrumentId(instrumentId);
    existingTx.setTransactionType(TransactionType.BUY);
    existingTx.setQuantity(new BigDecimal("10"));
    existingTx.setPrice(new BigDecimal("100"));
    existingTx.setCurrency(Currency.USD);
    existingTx.setExecutedAt(LocalDateTime.of(2024, 5, 10, 10, 0));

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.findByIdAndPortfolioId(txId, portfolioId)).thenReturn(Optional.of(existingTx));

    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "VUAA", "Vanguard S&P 500", InstrumentType.ETF, Currency.USD);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(marketDataClient.getNbpTaxRate(eq(Currency.USD), eq(Currency.PLN), eq(java.time.LocalDate.of(2024, 5, 10))))
        .thenReturn(new BigDecimal("4.2000"));
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    CreateTransactionRequest updateRequest = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("100"),
        new BigDecimal("12"),
        BigDecimal.ZERO,
        Currency.USD,
        null,
        null,
        null,
        null,
        null,
        null);

    TransactionResponse response = transactionService.updateTransaction(userId, portfolioId, txId, updateRequest);

    assertThat(response).isNotNull();
    verify(marketDataClient).getNbpTaxRate(eq(Currency.USD), eq(Currency.PLN), eq(java.time.LocalDate.of(2024, 5, 10)));
  }

  @Test
  void createTransactionRequest_validationEnforcesBounds() {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    CreateTransactionRequest negativeRate = new CreateTransactionRequest(
        instrumentId, TransactionType.BUY, new BigDecimal("100"), new BigDecimal("10"),
        BigDecimal.ZERO, Currency.PLN, LocalDateTime.of(2025, 1, 1, 10, 0),
        "EDO1034", new BigDecimal("-1.0"), new BigDecimal("1.5"), 120, new BigDecimal("1000"));
    assertThat(validator.validate(negativeRate)).isNotEmpty();

    CreateTransactionRequest negativeBaseAmount = new CreateTransactionRequest(
        instrumentId, TransactionType.BUY, new BigDecimal("100"), new BigDecimal("10"),
        BigDecimal.ZERO, Currency.PLN, LocalDateTime.of(2025, 1, 1, 10, 0),
        "EDO1034", new BigDecimal("6.5"), new BigDecimal("1.5"), 120, new BigDecimal("-1000"));
    assertThat(validator.validate(negativeBaseAmount)).isNotEmpty();

    CreateTransactionRequest negativeMargin = new CreateTransactionRequest(
        instrumentId, TransactionType.BUY, new BigDecimal("100"), new BigDecimal("10"),
        BigDecimal.ZERO, Currency.PLN, LocalDateTime.of(2025, 1, 1, 10, 0),
        "EDO1034", new BigDecimal("6.5"), new BigDecimal("-0.5"), 120, new BigDecimal("1000"));
    assertThat(validator.validate(negativeMargin)).isNotEmpty();
  }

  @Test
  void authRequests_passwordBoundsEnforcedAt72Bytes() {
    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    String longPassword = "A".repeat(73);
    LoginRequest loginReq = new LoginRequest("test@example.com", longPassword);
    assertThat(validator.validate(loginReq)).isNotEmpty();

    RegisterRequest regReq = new RegisterRequest(
        "test@example.com", longPassword, "First", "Last", Currency.PLN);
    assertThat(validator.validate(regReq)).isNotEmpty();
  }

  @Test
  void getTransactions_withPageAndSize_usesDatabasePageable() {
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);

    Transaction tx = new Transaction();
    tx.setId(UUID.randomUUID());
    tx.setPortfolio(portfolio);
    tx.setInstrumentId(instrumentId);
    tx.setTransactionType(TransactionType.BUY);
    tx.setQuantity(new BigDecimal("10"));
    tx.setPrice(new BigDecimal("100"));
    tx.setCurrency(Currency.PLN);
    tx.setExecutedAt(LocalDateTime.now());

    org.springframework.data.domain.Page<Transaction> paged =
        new org.springframework.data.domain.PageImpl<>(java.util.List.of(tx));

    when(transactionRepository.findAllByPortfolioIdOrderByExecutedAtDesc(
        eq(portfolioId), any(Pageable.class)))
        .thenReturn(paged);

    java.util.List<TransactionResponse> result = transactionService.getTransactions(userId, portfolioId, 0, 10);
    assertThat(result).hasSize(1);
    verify(transactionRepository).findAllByPortfolioIdOrderByExecutedAtDesc(
        eq(portfolioId), any(Pageable.class));
  }

  @Test
  void createTransaction_whenDividendCommissionExceedsGross_throwsIllegalArgumentException() {
    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "AAPL", "Apple Inc.", InstrumentType.STOCK, Currency.PLN);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);

    CreateTransactionRequest request = new CreateTransactionRequest(
        instrumentId,
        TransactionType.DIVIDEND,
        new BigDecimal("10"),
        new BigDecimal("2"),
        new BigDecimal("25"),
        Currency.PLN,
        LocalDateTime.of(2025, 2, 15, 10, 0),
        null,
        null,
        null,
        null,
        null);

    assertThatThrownBy(() -> transactionService.createTransaction(userId, portfolioId, request))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void getTransactions_whenInvalidPagination_throwsIllegalArgumentException() {
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);

    assertThatThrownBy(() -> transactionService.getTransactions(userId, portfolioId, -1, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> transactionService.getTransactions(userId, portfolioId, 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void deleteTransaction_acquiresPortfolioLockBeforeLoadingTransaction() {
    UUID txId = UUID.randomUUID();
    Transaction tx = new Transaction();
    tx.setId(txId);
    tx.setInstrumentId(instrumentId);

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.findByIdAndPortfolioId(txId, portfolioId)).thenReturn(Optional.of(tx));

    transactionService.deleteTransaction(userId, portfolioId, txId);

    InOrder inOrder = inOrder(portfolioRepository, transactionRepository);
    inOrder.verify(portfolioRepository).findByIdWithLock(portfolioId);
    inOrder.verify(transactionRepository).findByIdAndPortfolioId(txId, portfolioId);
    inOrder.verify(transactionRepository).delete(tx);
  }

  @Test
  void createTransaction_whenUntrimmedSeriesName_normalizesBeforeSaving() {
    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "PL_BOND", "Polska obligacja", InstrumentType.BOND, Currency.PLN);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    CreateTransactionRequest request = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("100"),
        new BigDecimal("10"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 1, 15, 10, 0),
        "  EDO1034  ",
        null,
        null,
        null,
        null);

    TransactionResponse response = transactionService.createTransaction(userId, portfolioId, request);

    assertThat(response.seriesName()).isEqualTo("EDO1034");
  }

  @Test
  void createTransaction_whenSellExceedsAvailableHoldings_throwsInsufficientPositionException() {
    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "VUAA", "Vanguard S&P 500", InstrumentType.ETF, Currency.PLN);
    Transaction priorBuy = new Transaction();
    priorBuy.setId(UUID.randomUUID());
    priorBuy.setPortfolio(portfolio);
    priorBuy.setInstrumentId(instrumentId);
    priorBuy.setTransactionType(TransactionType.BUY);
    priorBuy.setQuantity(new BigDecimal("3"));
    priorBuy.setPrice(new BigDecimal("100"));
    priorBuy.setCommission(BigDecimal.ZERO);
    priorBuy.setCurrency(Currency.PLN);
    priorBuy.setBaseAmount(new BigDecimal("300"));
    priorBuy.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(priorBuy));

    CreateTransactionRequest sellTooMuch = new CreateTransactionRequest(
        instrumentId,
        TransactionType.SELL,
        new BigDecimal("110"),
        new BigDecimal("5"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 2, 1, 10, 0),
        null, null, null, null, null);

    assertThatThrownBy(() -> transactionService.createTransaction(userId, portfolioId, sellTooMuch))
        .isInstanceOf(pl.investassist.common.exception.InsufficientPositionException.class);
    verify(transactionRepository, never()).save(any());
  }

  @Test
  void deleteTransaction_whenBuyNeededBySubsequentSell_throwsInsufficientPositionException() {
    UUID buyId = UUID.randomUUID();
    Transaction buyTx = new Transaction();
    buyTx.setId(buyId);
    buyTx.setPortfolio(portfolio);
    buyTx.setInstrumentId(instrumentId);
    buyTx.setTransactionType(TransactionType.BUY);
    buyTx.setQuantity(new BigDecimal("5"));
    buyTx.setPrice(new BigDecimal("100"));
    buyTx.setCommission(BigDecimal.ZERO);
    buyTx.setCurrency(Currency.PLN);
    buyTx.setBaseAmount(new BigDecimal("500"));
    buyTx.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    Transaction sellTx = new Transaction();
    sellTx.setId(UUID.randomUUID());
    sellTx.setPortfolio(portfolio);
    sellTx.setInstrumentId(instrumentId);
    sellTx.setTransactionType(TransactionType.SELL);
    sellTx.setQuantity(new BigDecimal("4"));
    sellTx.setPrice(new BigDecimal("120"));
    sellTx.setCommission(BigDecimal.ZERO);
    sellTx.setCurrency(Currency.PLN);
    sellTx.setBaseAmount(new BigDecimal("480"));
    sellTx.setExecutedAt(LocalDateTime.of(2025, 2, 10, 10, 0));

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.findByIdAndPortfolioId(buyId, portfolioId)).thenReturn(Optional.of(buyTx));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(buyTx, sellTx));

    assertThatThrownBy(() -> transactionService.deleteTransaction(userId, portfolioId, buyId))
        .isInstanceOf(pl.investassist.common.exception.InsufficientPositionException.class);
    verify(transactionRepository, never()).delete(any());
  }

  @Test
  void updateTransaction_whenBuyReducedBelowSubsequentSell_throwsInsufficientPositionException() {
    UUID buyId = UUID.randomUUID();
    Transaction buyTx = new Transaction();
    buyTx.setId(buyId);
    buyTx.setPortfolio(portfolio);
    buyTx.setInstrumentId(instrumentId);
    buyTx.setTransactionType(TransactionType.BUY);
    buyTx.setQuantity(new BigDecimal("10"));
    buyTx.setPrice(new BigDecimal("100"));
    buyTx.setCommission(BigDecimal.ZERO);
    buyTx.setCurrency(Currency.PLN);
    buyTx.setBaseAmount(new BigDecimal("1000"));
    buyTx.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    Transaction sellTx = new Transaction();
    sellTx.setId(UUID.randomUUID());
    sellTx.setPortfolio(portfolio);
    sellTx.setInstrumentId(instrumentId);
    sellTx.setTransactionType(TransactionType.SELL);
    sellTx.setQuantity(new BigDecimal("8"));
    sellTx.setPrice(new BigDecimal("120"));
    sellTx.setCommission(BigDecimal.ZERO);
    sellTx.setCurrency(Currency.PLN);
    sellTx.setBaseAmount(new BigDecimal("960"));
    sellTx.setExecutedAt(LocalDateTime.of(2025, 2, 10, 10, 0));

    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "VUAA", "Vanguard S&P 500", InstrumentType.ETF, Currency.PLN);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(transactionRepository.findByIdAndPortfolioId(buyId, portfolioId)).thenReturn(Optional.of(buyTx));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(buyTx, sellTx));

    CreateTransactionRequest reduceBuy = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("100"),
        new BigDecimal("5"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 1, 10, 10, 0),
        null, null, null, null, null);

    assertThatThrownBy(() -> transactionService.updateTransaction(userId, portfolioId, buyId, reduceBuy))
        .isInstanceOf(pl.investassist.common.exception.InsufficientPositionException.class);
    verify(transactionRepository, never()).save(any());
  }

  @Test
  void updateTransaction_whenSellIncreasedAboveHoldings_throwsInsufficientPositionException() {
    UUID buyId = UUID.randomUUID();
    UUID sellId = UUID.randomUUID();
    Transaction buyTx = new Transaction();
    buyTx.setId(buyId);
    buyTx.setPortfolio(portfolio);
    buyTx.setInstrumentId(instrumentId);
    buyTx.setTransactionType(TransactionType.BUY);
    buyTx.setQuantity(new BigDecimal("10"));
    buyTx.setPrice(new BigDecimal("100"));
    buyTx.setCommission(BigDecimal.ZERO);
    buyTx.setCurrency(Currency.PLN);
    buyTx.setBaseAmount(new BigDecimal("1000"));
    buyTx.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    Transaction sellTx = new Transaction();
    sellTx.setId(sellId);
    sellTx.setPortfolio(portfolio);
    sellTx.setInstrumentId(instrumentId);
    sellTx.setTransactionType(TransactionType.SELL);
    sellTx.setQuantity(new BigDecimal("8"));
    sellTx.setPrice(new BigDecimal("120"));
    sellTx.setCommission(BigDecimal.ZERO);
    sellTx.setCurrency(Currency.PLN);
    sellTx.setBaseAmount(new BigDecimal("960"));
    sellTx.setExecutedAt(LocalDateTime.of(2025, 2, 10, 10, 0));

    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "VUAA", "Vanguard S&P 500", InstrumentType.ETF, Currency.PLN);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(transactionRepository.findByIdAndPortfolioId(sellId, portfolioId)).thenReturn(Optional.of(sellTx));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(buyTx, sellTx));

    CreateTransactionRequest increaseSell = new CreateTransactionRequest(
        instrumentId,
        TransactionType.SELL,
        new BigDecimal("120"),
        new BigDecimal("12"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 2, 10, 10, 0),
        null, null, null, null, null);

    assertThatThrownBy(() -> transactionService.updateTransaction(userId, portfolioId, sellId, increaseSell))
        .isInstanceOf(pl.investassist.common.exception.InsufficientPositionException.class);
    verify(transactionRepository, never()).save(any());
  }

  @Test
  void updateTransaction_whenBuyMovedAfterExistingSell_throwsInsufficientPositionException() {
    UUID buyId = UUID.randomUUID();
    Transaction buyTx = new Transaction();
    buyTx.setId(buyId);
    buyTx.setPortfolio(portfolio);
    buyTx.setInstrumentId(instrumentId);
    buyTx.setTransactionType(TransactionType.BUY);
    buyTx.setQuantity(new BigDecimal("10"));
    buyTx.setPrice(new BigDecimal("100"));
    buyTx.setCommission(BigDecimal.ZERO);
    buyTx.setCurrency(Currency.PLN);
    buyTx.setBaseAmount(new BigDecimal("1000"));
    buyTx.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    Transaction sellTx = new Transaction();
    sellTx.setId(UUID.randomUUID());
    sellTx.setPortfolio(portfolio);
    sellTx.setInstrumentId(instrumentId);
    sellTx.setTransactionType(TransactionType.SELL);
    sellTx.setQuantity(new BigDecimal("5"));
    sellTx.setPrice(new BigDecimal("120"));
    sellTx.setCommission(BigDecimal.ZERO);
    sellTx.setCurrency(Currency.PLN);
    sellTx.setBaseAmount(new BigDecimal("600"));
    sellTx.setExecutedAt(LocalDateTime.of(2025, 2, 10, 10, 0));

    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "VUAA", "Vanguard S&P 500", InstrumentType.ETF, Currency.PLN);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(transactionRepository.findByIdAndPortfolioId(buyId, portfolioId)).thenReturn(Optional.of(buyTx));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(buyTx, sellTx));

    CreateTransactionRequest moveBuyLater = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("100"),
        new BigDecimal("10"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 3, 10, 10, 0),
        null, null, null, null, null);

    assertThatThrownBy(() -> transactionService.updateTransaction(userId, portfolioId, buyId, moveBuyLater))
        .isInstanceOf(pl.investassist.common.exception.InsufficientPositionException.class);
    verify(transactionRepository, never()).save(any());
  }

  @Test
  void stockWithNonBlankSeriesNameSharesFifoQueueOnSellValidation() {
    TransactionRepository localTxRepo = org.mockito.Mockito.mock(TransactionRepository.class);
    PortfolioRepository localPfRepo = org.mockito.Mockito.mock(PortfolioRepository.class);
    PortfolioService localPfService = org.mockito.Mockito.mock(PortfolioService.class);
    pl.investassist.portfolio.port.MarketDataPort localMarketData = org.mockito.Mockito.mock(pl.investassist.portfolio.port.MarketDataPort.class);
    InstrumentService localInstService = org.mockito.Mockito.mock(InstrumentService.class);
    PortfolioCacheService localCacheService = org.mockito.Mockito.mock(PortfolioCacheService.class);
    org.springframework.transaction.PlatformTransactionManager localTxManager =
        org.mockito.Mockito.mock(org.springframework.transaction.PlatformTransactionManager.class);
    when(localTxManager.getTransaction(any()))
        .thenAnswer(inv -> new org.springframework.transaction.support.SimpleTransactionStatus());

    TransactionService localService = new TransactionService(
        localTxRepo, localPfRepo, localPfService, localMarketData, localInstService, localCacheService, localTxManager);

    UUID userId = UUID.randomUUID();
    UUID portfolioId = UUID.randomUUID();
    UUID instrumentId = UUID.randomUUID();

    Portfolio portfolio = new Portfolio();
    portfolio.setId(portfolioId);
    portfolio.setUserId(userId);
    portfolio.setName("Main");
    portfolio.setBaseCurrency(Currency.PLN);

    Transaction existingBuy = new Transaction();
    existingBuy.setId(UUID.randomUUID());
    existingBuy.setPortfolio(portfolio);
    existingBuy.setInstrumentId(instrumentId);
    existingBuy.setTransactionType(TransactionType.BUY);
    existingBuy.setQuantity(new BigDecimal("10"));
    existingBuy.setPrice(new BigDecimal("100"));
    existingBuy.setCommission(BigDecimal.ZERO);
    existingBuy.setCurrency(Currency.PLN);
    existingBuy.setExchangeRate(BigDecimal.ONE);
    existingBuy.setBaseAmount(new BigDecimal("1000"));
    existingBuy.setExchangeRateToBase(BigDecimal.ONE);
    existingBuy.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));
    existingBuy.setSeriesName("TRANCHE-A");

    InstrumentResponse stock = new InstrumentResponse(
        instrumentId, "PKN.WA", "Orlen", InstrumentType.STOCK, Currency.PLN);

    when(localPfService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(localPfRepo.findByIdWithLock(portfolioId)).thenReturn(Optional.of(portfolio));
    when(localInstService.getInstrument(instrumentId)).thenReturn(stock);
    when(localTxRepo.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(existingBuy));
    when(localTxRepo.save(any(Transaction.class))).thenAnswer(inv -> {
      Transaction t = inv.getArgument(0);
      t.setId(UUID.randomUUID());
      return t;
    });

    CreateTransactionRequest sellWithoutSeries = new CreateTransactionRequest(
        instrumentId,
        TransactionType.SELL,
        new BigDecimal("110"),
        new BigDecimal("10"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 2, 10, 10, 0),
        null, null, null, null, null);

    TransactionResponse created = localService.createTransaction(userId, portfolioId, sellWithoutSeries);
    assertThat(created.quantity()).isEqualByComparingTo("10");
    verify(localTxRepo).findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId);
    verify(localTxRepo).save(any(Transaction.class));
  }

  @Test
  void authRequests_rejectMultiBytePasswordExceeding72Utf8Bytes() {
    jakarta.validation.Validator validator =
        jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();
    String thirtySevenMultiByte = "ą".repeat(37);

    pl.investassist.auth.dto.RegisterRequest registerReq =
        new pl.investassist.auth.dto.RegisterRequest(
            "user@example.com", thirtySevenMultiByte, "Jan", "Kowalski", Currency.PLN);
    pl.investassist.auth.dto.LoginRequest loginReq =
        new pl.investassist.auth.dto.LoginRequest("user@example.com", thirtySevenMultiByte);

    assertThat(validator.validate(registerReq)).isNotEmpty();
    assertThat(validator.validate(loginReq)).isNotEmpty();
  }

  @Test
  void createAndUpdateTransaction_crossCurrencyBuyWithCustomRateInBaseCurrency_usesCustomRateWithoutFxLookup() {
    InstrumentResponse usdInstrument = new InstrumentResponse(
        instrumentId, "AAPL", "Apple Inc.", InstrumentType.STOCK, Currency.USD);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(usdInstrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> {
      Transaction tx = inv.getArgument(0);
      if (tx.getId() == null) {
        tx.setId(UUID.randomUUID());
      }
      return tx;
    });

    CreateTransactionRequest createReq = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("425.00"),
        new BigDecimal("2"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 2, 10, 10, 0),
        null, null, null, null, null,
        new BigDecimal("4.250000"));

    TransactionResponse created = transactionService.createTransaction(userId, portfolioId, createReq);
    assertThat(created.exchangeRate()).isEqualByComparingTo("4.250000");
    assertThat(created.exchangeRateToBase()).isEqualByComparingTo("1");
    assertThat(created.baseAmount()).isEqualByComparingTo("850.00");

    UUID txId = created.id();
    Transaction existing = new Transaction();
    existing.setId(txId);
    existing.setPortfolio(portfolio);
    existing.setInstrumentId(instrumentId);
    existing.setTransactionType(TransactionType.BUY);
    existing.setQuantity(new BigDecimal("2"));
    existing.setPrice(new BigDecimal("425.00"));
    existing.setCommission(BigDecimal.ZERO);
    existing.setCurrency(Currency.PLN);
    existing.setExchangeRate(new BigDecimal("4.250000"));
    existing.setBaseAmount(new BigDecimal("850.00"));
    existing.setExchangeRateToBase(BigDecimal.ONE);
    existing.setExecutedAt(LocalDateTime.of(2025, 2, 10, 10, 0));

    when(transactionRepository.findByIdAndPortfolioId(txId, portfolioId)).thenReturn(Optional.of(existing));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(existing));

    CreateTransactionRequest updateReq = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("430.00"),
        new BigDecimal("2"),
        BigDecimal.ZERO,
        Currency.PLN,
        null,
        null, null, null, null, null,
        new BigDecimal("4.300000"));

    TransactionResponse updated = transactionService.updateTransaction(userId, portfolioId, txId, updateReq);
    assertThat(updated.exchangeRate()).isEqualByComparingTo("4.300000");
    assertThat(updated.exchangeRateToBase()).isEqualByComparingTo("1");
    assertThat(updated.baseAmount()).isEqualByComparingTo("860.00");
    verifyNoInteractions(marketDataClient);
  }

  @Test
  void createTransaction_whenSameBaseCurrencySellAndDividendReceiveCustomRate_forcesRateToBaseOne() {
    InstrumentResponse instrument = new InstrumentResponse(
        instrumentId, "SXR8.DE", "iShares Core S&P 500", InstrumentType.ETF, Currency.EUR);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(instrument);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    Transaction priorBuy = new Transaction();
    priorBuy.setId(UUID.randomUUID());
    priorBuy.setPortfolio(portfolio);
    priorBuy.setInstrumentId(instrumentId);
    priorBuy.setTransactionType(TransactionType.BUY);
    priorBuy.setQuantity(new BigDecimal("5"));
    priorBuy.setPrice(new BigDecimal("90.00"));
    priorBuy.setCommission(BigDecimal.ZERO);
    priorBuy.setCurrency(Currency.PLN);
    priorBuy.setExchangeRate(new BigDecimal("4.250000"));
    priorBuy.setExchangeRateToBase(BigDecimal.ONE);
    priorBuy.setBaseAmount(new BigDecimal("450.00"));
    priorBuy.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(priorBuy));
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
      Transaction tx = i.getArgument(0);
      tx.setId(UUID.randomUUID());
      return tx;
    });

    CreateTransactionRequest sellReq = new CreateTransactionRequest(
        instrumentId,
        TransactionType.SELL,
        new BigDecimal("100.00"),
        new BigDecimal("1"),
        new BigDecimal("5.00"),
        Currency.PLN,
        LocalDateTime.of(2025, 3, 10, 10, 0),
        null, null, null, null, null,
        new BigDecimal("4.250000"));

    TransactionResponse sellRes = transactionService.createTransaction(userId, portfolioId, sellReq);
    assertThat(sellRes.exchangeRateToBase()).isEqualByComparingTo("1");
    assertThat(sellRes.baseAmount()).isEqualByComparingTo("95.00");

    CreateTransactionRequest divReq = new CreateTransactionRequest(
        instrumentId,
        TransactionType.DIVIDEND,
        new BigDecimal("50.00"),
        new BigDecimal("2"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 4, 10, 10, 0),
        null, null, null, null, null,
        new BigDecimal("4.250000"));

    TransactionResponse divRes = transactionService.createTransaction(userId, portfolioId, divReq);
    assertThat(divRes.exchangeRateToBase()).isEqualByComparingTo("1");
    assertThat(divRes.baseAmount()).isEqualByComparingTo("100.00");
  }

  @Test
  void updateTransaction_whenChangingSeriesNameOnStockWithSubsequentSell_succeeds() {
    InstrumentResponse stock = new InstrumentResponse(
        instrumentId, "CDR.WA", "CD Projekt", InstrumentType.STOCK, Currency.PLN);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(stock);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);

    UUID buyId = UUID.randomUUID();
    Transaction existingBuy = new Transaction();
    existingBuy.setId(buyId);
    existingBuy.setPortfolio(portfolio);
    existingBuy.setInstrumentId(instrumentId);
    existingBuy.setTransactionType(TransactionType.BUY);
    existingBuy.setQuantity(new BigDecimal("10"));
    existingBuy.setPrice(new BigDecimal("150.00"));
    existingBuy.setCommission(BigDecimal.ZERO);
    existingBuy.setCurrency(Currency.PLN);
    existingBuy.setExchangeRate(BigDecimal.ONE);
    existingBuy.setBaseAmount(new BigDecimal("1500.00"));
    existingBuy.setExchangeRateToBase(BigDecimal.ONE);
    existingBuy.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    Transaction existingSell = new Transaction();
    existingSell.setId(UUID.randomUUID());
    existingSell.setPortfolio(portfolio);
    existingSell.setInstrumentId(instrumentId);
    existingSell.setTransactionType(TransactionType.SELL);
    existingSell.setQuantity(new BigDecimal("5"));
    existingSell.setPrice(new BigDecimal("180.00"));
    existingSell.setCommission(BigDecimal.ZERO);
    existingSell.setCurrency(Currency.PLN);
    existingSell.setExchangeRate(BigDecimal.ONE);
    existingSell.setBaseAmount(new BigDecimal("900.00"));
    existingSell.setExchangeRateToBase(BigDecimal.ONE);
    existingSell.setExecutedAt(LocalDateTime.of(2025, 2, 10, 10, 0));

    when(transactionRepository.findByIdAndPortfolioId(buyId, portfolioId))
        .thenReturn(Optional.of(existingBuy));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(existingBuy, existingSell));
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    CreateTransactionRequest updateReq = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("150.00"),
        new BigDecimal("10"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2025, 1, 10, 10, 0),
        "TRANCHE-2",
        null, null, null, null, null);

    TransactionResponse updated = transactionService.updateTransaction(userId, portfolioId, buyId, updateReq);
    assertThat(updated.seriesName()).isEqualTo("TRANCHE-2");
    assertThat(updated.quantity()).isEqualByComparingTo("10");
  }

  @Test
  void createTransaction_whenUnknownBondSeriesOrMismatchedTerm_throwsIllegalArgument() {
    InstrumentResponse bondInst = new InstrumentResponse(
        instrumentId, "PL_BOND", "Polska obligacja", InstrumentType.BOND, Currency.PLN);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(bondInst);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);

    CreateTransactionRequest unknownSeries = new CreateTransactionRequest(
        instrumentId, TransactionType.BUY, new BigDecimal("100"), new BigDecimal("10"),
        BigDecimal.ZERO, Currency.PLN, LocalDateTime.of(2025, 6, 1, 10, 0),
        "XYZ0130", new BigDecimal("6.0"), new BigDecimal("1.5"), 120, null);
    assertThatThrownBy(() -> transactionService.createTransaction(userId, portfolioId, unknownSeries))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("XYZ0130");

    CreateTransactionRequest mismatchedTerm = new CreateTransactionRequest(
        instrumentId, TransactionType.BUY, new BigDecimal("100"), new BigDecimal("10"),
        BigDecimal.ZERO, Currency.PLN, LocalDateTime.of(2025, 6, 1, 10, 0),
        "EDO0635", new BigDecimal("6.0"), new BigDecimal("1.5"), 48, null);
    assertThatThrownBy(() -> transactionService.createTransaction(userId, portfolioId, mismatchedTerm))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("termMonths=120");
  }

  @Test
  void createTransaction_appliesFxFeePctOnBuySellDividendAndEnforcesBounds() throws Exception {
    tools.jackson.databind.ObjectMapper mapper = new tools.jackson.databind.ObjectMapper();
    InstrumentResponse usdEtf = new InstrumentResponse(
        instrumentId, "CSPX.L", "iShares Core S&P 500", InstrumentType.ETF, Currency.USD);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(usdEtf);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(marketDataClient.getNbpTaxRate(eq(Currency.USD), eq(Currency.PLN), any()))
        .thenReturn(new BigDecimal("4.0000"));
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> {
      Transaction tx = i.getArgument(0);
      if (tx.getId() == null) {
        tx.setId(UUID.randomUUID());
      }
      return tx;
    });

    String buyJson = """
        {
          "instrumentId": "%s",
          "transactionType": "BUY",
          "price": 100.00,
          "quantity": 10,
          "commission": 0,
          "currency": "USD",
          "executedAt": "2025-01-15T10:00:00",
          "fxFeePct": 0.50
        }
        """.formatted(instrumentId);
    CreateTransactionRequest buyReq = mapper.readValue(buyJson, CreateTransactionRequest.class);
    TransactionResponse buyResp = transactionService.createTransaction(userId, portfolioId, buyReq);
    assertThat(buyResp.baseAmount()).isEqualByComparingTo("4020.0000");
    assertThat(buyResp.exchangeRateToBase()).isEqualByComparingTo("4.0000");
    assertThat(mapper.valueToTree(buyResp).path("fxFeePct").decimalValue())
        .isEqualByComparingTo("0.50");

    Transaction priorBuy = new Transaction();
    priorBuy.setId(UUID.randomUUID());
    priorBuy.setPortfolio(portfolio);
    priorBuy.setInstrumentId(instrumentId);
    priorBuy.setTransactionType(TransactionType.BUY);
    priorBuy.setQuantity(new BigDecimal("10"));
    priorBuy.setPrice(new BigDecimal("100"));
    priorBuy.setCommission(BigDecimal.ZERO);
    priorBuy.setCurrency(Currency.USD);
    priorBuy.setBaseAmount(new BigDecimal("4020.0000"));
    priorBuy.setExecutedAt(LocalDateTime.of(2025, 1, 15, 10, 0));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(priorBuy));

    String sellJson = """
        {
          "instrumentId": "%s",
          "transactionType": "SELL",
          "price": 100.00,
          "quantity": 10,
          "commission": 0,
          "currency": "USD",
          "executedAt": "2025-02-15T10:00:00",
          "fxFeePct": 0.50
        }
        """.formatted(instrumentId);
    CreateTransactionRequest sellReq = mapper.readValue(sellJson, CreateTransactionRequest.class);
    TransactionResponse sellResp = transactionService.createTransaction(userId, portfolioId, sellReq);
    assertThat(sellResp.baseAmount()).isEqualByComparingTo("3980.0000");
    assertThat(mapper.valueToTree(sellResp).path("fxFeePct").decimalValue())
        .isEqualByComparingTo("0.50");

    String divJson = """
        {
          "instrumentId": "%s",
          "transactionType": "DIVIDEND",
          "price": 10.00,
          "quantity": 10,
          "commission": 0,
          "currency": "USD",
          "executedAt": "2025-03-01T10:00:00",
          "fxFeePct": 0.50
        }
        """.formatted(instrumentId);
    CreateTransactionRequest divReq = mapper.readValue(divJson, CreateTransactionRequest.class);
    TransactionResponse divResp = transactionService.createTransaction(userId, portfolioId, divReq);
    assertThat(divResp.baseAmount()).isEqualByComparingTo("398.0000");

    String sellMaxFeeJson = """
        {
          "instrumentId": "%s",
          "transactionType": "SELL",
          "price": 100.00,
          "quantity": 10,
          "commission": 0,
          "currency": "USD",
          "executedAt": "2025-02-15T10:00:00",
          "fxFeePct": 100.00
        }
        """.formatted(instrumentId);
    TransactionResponse zeroProceeds = transactionService.createTransaction(
        userId, portfolioId, mapper.readValue(sellMaxFeeJson, CreateTransactionRequest.class));
    assertThat(zeroProceeds.baseAmount()).isEqualByComparingTo("0.0000");

    Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
    CreateTransactionRequest negFee = mapper.readValue(
        buyJson.replace("0.50", "-0.01"), CreateTransactionRequest.class);
    CreateTransactionRequest overFee = mapper.readValue(
        buyJson.replace("0.50", "100.01"), CreateTransactionRequest.class);
    assertThat(validator.validate(negFee)).isNotEmpty();
    assertThat(validator.validate(overFee)).isNotEmpty();
  }

  @Test
  void updateTransaction_updatesFxFeePctAndRecomputesBaseAmount() {
    InstrumentResponse usdEtf = new InstrumentResponse(
        instrumentId, "CSPX.L", "iShares Core S&P 500", InstrumentType.ETF, Currency.USD);
    when(instrumentService.getInstrument(instrumentId)).thenReturn(usdEtf);
    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(marketDataClient.getNbpTaxRate(eq(Currency.USD), eq(Currency.PLN), any()))
        .thenReturn(new BigDecimal("4.0000"));
    when(transactionRepository.save(any(Transaction.class))).thenAnswer(i -> i.getArgument(0));

    UUID buyId = UUID.randomUUID();
    Transaction existingBuy = new Transaction();
    existingBuy.setId(buyId);
    existingBuy.setPortfolio(portfolio);
    existingBuy.setInstrumentId(instrumentId);
    existingBuy.setTransactionType(TransactionType.BUY);
    existingBuy.setQuantity(new BigDecimal("10"));
    existingBuy.setPrice(new BigDecimal("100.00"));
    existingBuy.setCommission(BigDecimal.ZERO);
    existingBuy.setCurrency(Currency.USD);
    existingBuy.setExchangeRate(BigDecimal.ONE);
    existingBuy.setExchangeRateToBase(new BigDecimal("4.0000"));
    existingBuy.setBaseAmount(new BigDecimal("4020.0000"));
    existingBuy.setFxFeePct(new BigDecimal("0.50"));
    existingBuy.setExecutedAt(LocalDateTime.of(2025, 1, 15, 10, 0));

    when(transactionRepository.findByIdAndPortfolioId(buyId, portfolioId)).thenReturn(Optional.of(existingBuy));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(existingBuy));

    CreateTransactionRequest clearFeeReq = new CreateTransactionRequest(
        instrumentId, TransactionType.BUY, new BigDecimal("100.00"), new BigDecimal("10"),
        BigDecimal.ZERO, Currency.USD, LocalDateTime.of(2025, 1, 15, 10, 0),
        null, null, null, null, null, null, null, null, BigDecimal.ZERO);
    TransactionResponse cleared = transactionService.updateTransaction(userId, portfolioId, buyId, clearFeeReq);
    assertThat(cleared.fxFeePct()).isEqualByComparingTo("0.00");
    assertThat(cleared.baseAmount()).isEqualByComparingTo("4000.0000");

    CreateTransactionRequest applyFeeReq = new CreateTransactionRequest(
        instrumentId, TransactionType.BUY, new BigDecimal("100.00"), new BigDecimal("10"),
        BigDecimal.ZERO, Currency.USD, LocalDateTime.of(2025, 1, 15, 10, 0),
        null, null, null, null, null, null, null, null, new BigDecimal("0.50"));
    TransactionResponse reapplied = transactionService.updateTransaction(userId, portfolioId, buyId, applyFeeReq);
    assertThat(reapplied.fxFeePct()).isEqualByComparingTo("0.50");
    assertThat(reapplied.baseAmount()).isEqualByComparingTo("4020.0000");

    UUID sellId = UUID.randomUUID();
    Transaction existingSell = new Transaction();
    existingSell.setId(sellId);
    existingSell.setPortfolio(portfolio);
    existingSell.setInstrumentId(instrumentId);
    existingSell.setTransactionType(TransactionType.SELL);
    existingSell.setQuantity(new BigDecimal("10"));
    existingSell.setPrice(new BigDecimal("100.00"));
    existingSell.setCommission(BigDecimal.ZERO);
    existingSell.setCurrency(Currency.USD);
    existingSell.setExchangeRate(BigDecimal.ONE);
    existingSell.setExchangeRateToBase(new BigDecimal("4.0000"));
    existingSell.setBaseAmount(new BigDecimal("4000.0000"));
    existingSell.setFxFeePct(BigDecimal.ZERO);
    existingSell.setExecutedAt(LocalDateTime.of(2025, 2, 15, 10, 0));

    when(transactionRepository.findByIdAndPortfolioId(sellId, portfolioId)).thenReturn(Optional.of(existingSell));
    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolioId, instrumentId))
        .thenReturn(java.util.List.of(existingBuy, existingSell));

    CreateTransactionRequest sellFeeReq = new CreateTransactionRequest(
        instrumentId, TransactionType.SELL, new BigDecimal("100.00"), new BigDecimal("10"),
        BigDecimal.ZERO, Currency.USD, LocalDateTime.of(2025, 2, 15, 10, 0),
        null, null, null, null, null, null, null, null, new BigDecimal("0.50"));
    TransactionResponse sellUpdated = transactionService.updateTransaction(userId, portfolioId, sellId, sellFeeReq);
    assertThat(sellUpdated.fxFeePct()).isEqualByComparingTo("0.50");
    assertThat(sellUpdated.baseAmount()).isEqualByComparingTo("3980.0000");
  }

  @Test
  void createTransactionRequest_allowsTodayWarsawNoonBefore12Utc() {
    Validator utcMorningValidator = Validation.byDefaultProvider()
        .configure()
        .clockProvider(() -> java.time.Clock.fixed(
            java.time.Instant.parse("2026-06-15T10:00:00Z"), java.time.ZoneOffset.UTC))
        .buildValidatorFactory()
        .getValidator();

    CreateTransactionRequest warsawNoonToday = new CreateTransactionRequest(
        instrumentId,
        TransactionType.BUY,
        new BigDecimal("100.00"),
        new BigDecimal("5"),
        BigDecimal.ZERO,
        Currency.PLN,
        LocalDateTime.of(2026, 6, 15, 12, 0),
        null, null, null, null, null);

    assertThat(utcMorningValidator.validate(warsawNoonToday)).isEmpty();
  }

  @Test
  void v4Migration_addsFxFeePctVersionAndCascadeForeignKey() throws Exception {
    java.nio.file.Path v4Path = java.nio.file.Path.of(
        "src/main/resources/db/migration/V4__fx_conversion_fee_and_audit_fixes.sql");
    if (!java.nio.file.Files.exists(v4Path)) {
      v4Path = java.nio.file.Path.of(
          "backend/src/main/resources/db/migration/V4__fx_conversion_fee_and_audit_fixes.sql");
    }
    assertThat(v4Path).exists();
    String sql = java.nio.file.Files.readString(v4Path);
    assertThat(sql).containsIgnoringCase("fx_fee_pct");
    assertThat(sql).containsIgnoringCase("version");
    assertThat(sql).containsIgnoringCase("fk_transaction_portfolio");
    assertThat(sql).containsIgnoringCase("ON DELETE CASCADE");
  }
}
