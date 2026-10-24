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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import pl.investassist.portfolio.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

  @Mock
  private TransactionRepository transactionRepository;

  @Mock
  private PortfolioService portfolioService;

  @Mock
  private PositionService positionService;

  @Mock
  private MarketDataClient marketDataClient;

  @Mock
  private InstrumentService instrumentService;

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
        portfolioService,
        positionService,
        marketDataClient,
        instrumentService,
        transactionManager);

    userId = UUID.randomUUID();
    portfolioId = UUID.randomUUID();
    instrumentId = UUID.randomUUID();

    portfolio = new Portfolio();
    portfolio.setId(portfolioId);
    portfolio.setUserId(userId);
    portfolio.setBaseCurrency(Currency.PLN);
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

    verify(positionService).recalculatePosition(portfolio, instrumentId, Currency.PLN, "EDO1034");
  }

  @Test
  void createTransaction_whenSellTransaction_subtractsCommissionFromBaseAmount() {
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
        TransactionType.SELL,
        new BigDecimal("100"),
        new BigDecimal("5"),
        new BigDecimal("10"),
        Currency.PLN,
        LocalDateTime.of(2025, 2, 15, 10, 0),
        null,
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
    when(marketDataClient.getExchangeRate(eq(Currency.USD), eq(Currency.PLN), any()))
        .thenReturn(new BigDecimal("4.0000"));
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
    assertThat(response.baseAmount()).isEqualByComparingTo("2008.0000");

    verify(positionService).recalculatePosition(portfolio, instrumentId, Currency.USD, null);
  }

  @Test
  void deleteTransaction_whenFound_callsInstrumentServiceAndRecalculatesPosition() {
    UUID txId = UUID.randomUUID();
    Transaction tx = new Transaction();
    tx.setId(txId);
    tx.setInstrumentId(instrumentId);
    tx.setSeriesName("EDO1034");

    when(portfolioService.validateAndGetPortfolioEntity(userId, portfolioId)).thenReturn(portfolio);
    when(transactionRepository.findByIdAndPortfolioId(txId, portfolioId)).thenReturn(Optional.of(tx));
    when(instrumentService.getInstrument(instrumentId)).thenReturn(
        new InstrumentResponse(instrumentId, "PL_BOND", "Bond", InstrumentType.BOND, Currency.PLN));

    transactionService.deleteTransaction(userId, portfolioId, txId);

    verify(transactionRepository).delete(tx);
    verify(positionService).recalculatePosition(portfolio, instrumentId, Currency.PLN, "EDO1034");
  }

  @Test
  void updateTransaction_whenSeriesChanged_updatesTransactionAndPerformsDualPositionRecalculation() {
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

    verify(positionService).recalculatePosition(portfolio, instrumentId, Currency.PLN, "EDO1034");
    verify(positionService).recalculatePosition(portfolio, instrumentId, Currency.PLN, "COI1028");
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
    when(marketDataClient.getExchangeRate(eq(Currency.USD), eq(Currency.PLN), eq(java.time.LocalDate.of(2024, 5, 10))))
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
    verify(marketDataClient).getExchangeRate(eq(Currency.USD), eq(Currency.PLN), eq(java.time.LocalDate.of(2024, 5, 10)));
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
}
