package pl.investassist.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.InsufficientPositionException;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Position;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.TransactionType;
import pl.investassist.portfolio.repository.PositionRepository;
import pl.investassist.portfolio.repository.TransactionRepository;

@ExtendWith(MockitoExtension.class)
class PositionServiceTest {

  @Mock
  private PositionRepository positionRepository;

  @Mock
  private PortfolioService portfolioService;

  @Mock
  private TransactionRepository transactionRepository;

  private PositionService positionService;

  private Portfolio portfolio;
  private UUID instrumentId;

  @BeforeEach
  void setUp() {
    positionService = new PositionService(positionRepository, portfolioService, transactionRepository);
    portfolio = new Portfolio();
    portfolio.setId(UUID.randomUUID());
    portfolio.setBaseCurrency(Currency.PLN);
    instrumentId = UUID.randomUUID();
  }

  @Test
  void recalculatePosition_whenBuyTransactions_calculatesAveragePriceAndCost() {
    Transaction tx1 = new Transaction();
    tx1.setTransactionType(TransactionType.BUY);
    tx1.setQuantity(new BigDecimal("10"));
    tx1.setPrice(new BigDecimal("100"));
    tx1.setBaseAmount(new BigDecimal("400"));
    tx1.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    Transaction tx2 = new Transaction();
    tx2.setTransactionType(TransactionType.BUY);
    tx2.setQuantity(new BigDecimal("10"));
    tx2.setPrice(new BigDecimal("120"));
    tx2.setBaseAmount(new BigDecimal("480"));
    tx2.setExecutedAt(LocalDateTime.of(2025, 2, 10, 10, 0));

    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolio.getId(), instrumentId))
        .thenReturn(List.of(tx1, tx2));
    when(positionRepository.findFirstByPortfolioIdAndInstrumentIdAndSeriesNameIsNull(portfolio.getId(), instrumentId))
        .thenReturn(Optional.empty());

    positionService.recalculatePosition(portfolio, instrumentId, Currency.USD, null);

    ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
    verify(positionRepository).save(captor.capture());
    Position saved = captor.getValue();

    assertThat(saved.getQuantity()).isEqualByComparingTo("20");
    assertThat(saved.getAverageBuyPrice()).isEqualByComparingTo("110.000000");
    assertThat(saved.getTotalCostBase()).isEqualByComparingTo("880.0000");
    assertThat(saved.getCurrency()).isEqualTo(Currency.USD);
  }

  @Test
  void recalculatePosition_whenPartialSell_reducesQuantityAndCostProportionately() {
    Transaction buyTx = new Transaction();
    buyTx.setTransactionType(TransactionType.BUY);
    buyTx.setQuantity(new BigDecimal("10"));
    buyTx.setPrice(new BigDecimal("100"));
    buyTx.setBaseAmount(new BigDecimal("400"));
    buyTx.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    Transaction sellTx = new Transaction();
    sellTx.setTransactionType(TransactionType.SELL);
    sellTx.setQuantity(new BigDecimal("4"));
    sellTx.setPrice(new BigDecimal("150"));
    sellTx.setExecutedAt(LocalDateTime.of(2025, 2, 10, 10, 0));

    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolio.getId(), instrumentId))
        .thenReturn(List.of(buyTx, sellTx));
    when(positionRepository.findFirstByPortfolioIdAndInstrumentIdAndSeriesNameIsNull(portfolio.getId(), instrumentId))
        .thenReturn(Optional.empty());

    positionService.recalculatePosition(portfolio, instrumentId, Currency.USD, null);

    ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
    verify(positionRepository).save(captor.capture());
    Position saved = captor.getValue();

    assertThat(saved.getQuantity()).isEqualByComparingTo("6");
    assertThat(saved.getAverageBuyPrice()).isEqualByComparingTo("100.000000");
    assertThat(saved.getTotalCostBase()).isEqualByComparingTo("240.0000");
  }

  @Test
  void recalculatePosition_whenEmptyTransactions_deletesPositionIfExists() {
    Position existingPosition = new Position();
    existingPosition.setId(UUID.randomUUID());

    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolio.getId(), instrumentId))
        .thenReturn(Collections.emptyList());
    when(positionRepository.findFirstByPortfolioIdAndInstrumentIdAndSeriesNameIsNull(portfolio.getId(), instrumentId))
        .thenReturn(Optional.of(existingPosition));

    positionService.recalculatePosition(portfolio, instrumentId, Currency.USD, null);

    verify(positionRepository).delete(existingPosition);
    verify(positionRepository, never()).save(any());
  }

  @Test
  void recalculatePosition_whenBondWithSeries_setsBondMetadataAndFiltersBySeries() {
    Transaction bondTx1 = new Transaction();
    bondTx1.setTransactionType(TransactionType.BUY);
    bondTx1.setQuantity(new BigDecimal("10"));
    bondTx1.setPrice(new BigDecimal("100"));
    bondTx1.setBaseAmount(new BigDecimal("1000"));
    bondTx1.setSeriesName("EDO1034");
    bondTx1.setBondRate(new BigDecimal("6.50"));
    bondTx1.setBondMargin(new BigDecimal("1.50"));
    bondTx1.setTermMonths(120);
    bondTx1.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    Transaction bondTxOtherSeries = new Transaction();
    bondTxOtherSeries.setTransactionType(TransactionType.BUY);
    bondTxOtherSeries.setQuantity(new BigDecimal("5"));
    bondTxOtherSeries.setPrice(new BigDecimal("100"));
    bondTxOtherSeries.setBaseAmount(new BigDecimal("500"));
    bondTxOtherSeries.setSeriesName("COI1028");
    bondTxOtherSeries.setExecutedAt(LocalDateTime.of(2025, 1, 15, 10, 0));

    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolio.getId(), instrumentId))
        .thenReturn(List.of(bondTx1, bondTxOtherSeries));
    when(positionRepository.findFirstByPortfolioIdAndInstrumentIdAndSeriesName(portfolio.getId(), instrumentId, "EDO1034"))
        .thenReturn(Optional.empty());

    positionService.recalculatePosition(portfolio, instrumentId, Currency.PLN, "EDO1034");

    ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
    verify(positionRepository).save(captor.capture());
    Position saved = captor.getValue();

    assertThat(saved.getQuantity()).isEqualByComparingTo("10");
    assertThat(saved.getSeriesName()).isEqualTo("EDO1034");
    assertThat(saved.getBondRate()).isEqualByComparingTo("6.50");
    assertThat(saved.getBondMargin()).isEqualByComparingTo("1.50");
    assertThat(saved.getTermMonths()).isEqualTo(120);
    assertThat(saved.getTotalCostBase()).isEqualByComparingTo("1000.0000");
  }

  @Test
  void recalculatePosition_whenSellExceedsQuantity_throwsException() {
    Transaction buyTx = new Transaction();
    buyTx.setTransactionType(TransactionType.BUY);
    buyTx.setQuantity(new BigDecimal("5"));
    buyTx.setPrice(new BigDecimal("100"));

    Transaction sellTx = new Transaction();
    sellTx.setTransactionType(TransactionType.SELL);
    sellTx.setQuantity(new BigDecimal("10"));
    sellTx.setPrice(new BigDecimal("120"));

    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolio.getId(), instrumentId))
        .thenReturn(List.of(buyTx, sellTx));
    when(positionRepository.findFirstByPortfolioIdAndInstrumentIdAndSeriesNameIsNull(portfolio.getId(), instrumentId))
        .thenReturn(Optional.empty());

    assertThatThrownBy(() -> positionService.recalculatePosition(portfolio, instrumentId, Currency.USD, null))
        .isInstanceOf(InsufficientPositionException.class);
  }

  @Test
  void recalculatePosition_whenMultiCurrencyWithExchangeRate_dividesPriceByRate() {
    Transaction tx = new Transaction();
    tx.setTransactionType(TransactionType.BUY);
    tx.setQuantity(new BigDecimal("10"));
    tx.setPrice(new BigDecimal("400"));
    tx.setExchangeRate(new BigDecimal("4.0"));
    tx.setBaseAmount(new BigDecimal("1000.0000"));
    tx.setExecutedAt(LocalDateTime.of(2025, 1, 10, 10, 0));

    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolio.getId(), instrumentId))
        .thenReturn(List.of(tx));
    when(positionRepository.findFirstByPortfolioIdAndInstrumentIdAndSeriesNameIsNull(portfolio.getId(), instrumentId))
        .thenReturn(Optional.empty());

    positionService.recalculatePosition(portfolio, instrumentId, Currency.USD, null);

    ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
    verify(positionRepository).save(captor.capture());
    Position saved = captor.getValue();

    assertThat(saved.getQuantity()).isEqualByComparingTo("10");
    assertThat(saved.getAverageBuyPrice()).isEqualByComparingTo("100.000000");
    assertThat(saved.getTotalCostBase()).isEqualByComparingTo("1000.0000");
    assertThat(saved.getCurrency()).isEqualTo(Currency.USD);
  }

  @Test
  void recalculatePosition_whenPositionLiquidatedAndReopened_resetsPurchaseDateAndBondAttributes() {
    Transaction bondBuy = new Transaction();
    bondBuy.setTransactionType(TransactionType.BUY);
    bondBuy.setQuantity(new BigDecimal("10"));
    bondBuy.setPrice(new BigDecimal("100"));
    bondBuy.setBaseAmount(new BigDecimal("1000"));
    bondBuy.setBondRate(new BigDecimal("6.50"));
    bondBuy.setBondMargin(new BigDecimal("1.50"));
    bondBuy.setTermMonths(120);
    bondBuy.setExecutedAt(LocalDateTime.of(2024, 1, 1, 10, 0));

    Transaction fullSell = new Transaction();
    fullSell.setTransactionType(TransactionType.SELL);
    fullSell.setQuantity(new BigDecimal("10"));
    fullSell.setPrice(new BigDecimal("110"));
    fullSell.setExecutedAt(LocalDateTime.of(2024, 6, 1, 10, 0));

    Transaction newBuy = new Transaction();
    newBuy.setTransactionType(TransactionType.BUY);
    newBuy.setQuantity(new BigDecimal("5"));
    newBuy.setPrice(new BigDecimal("200"));
    newBuy.setBaseAmount(new BigDecimal("1000"));
    newBuy.setExecutedAt(LocalDateTime.of(2025, 1, 1, 10, 0));

    when(transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolio.getId(), instrumentId))
        .thenReturn(List.of(bondBuy, fullSell, newBuy));
    when(positionRepository.findFirstByPortfolioIdAndInstrumentIdAndSeriesNameIsNull(portfolio.getId(), instrumentId))
        .thenReturn(Optional.empty());

    positionService.recalculatePosition(portfolio, instrumentId, Currency.PLN, null);

    ArgumentCaptor<Position> captor = ArgumentCaptor.forClass(Position.class);
    verify(positionRepository).save(captor.capture());
    Position saved = captor.getValue();

    assertThat(saved.getQuantity()).isEqualByComparingTo("5");
    assertThat(saved.getAverageBuyPrice()).isEqualByComparingTo("200.000000");
    assertThat(saved.getPurchaseDate()).isEqualTo(LocalDateTime.of(2025, 1, 1, 10, 0));
    assertThat(saved.getBondRate()).isNull();
    assertThat(saved.getBondMargin()).isNull();
    assertThat(saved.getTermMonths()).isNull();
  }
}
