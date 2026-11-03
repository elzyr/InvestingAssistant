package pl.investassist.portfolio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.InsufficientPositionException;
import pl.investassist.common.util.SeriesUtils;
import pl.investassist.portfolio.dto.response.PositionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Position;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.TransactionType;
import pl.investassist.portfolio.repository.PositionRepository;
import pl.investassist.portfolio.repository.TransactionRepository;

@Service
public class PositionService {

  private static final Comparator<Transaction> TX_ORDER =
      Comparator.comparing(Transaction::getExecutedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparingInt(t -> t.getTransactionType() == TransactionType.BUY ? 0 : (t.getTransactionType() == TransactionType.DIVIDEND ? 2 : 1))
          .thenComparing(Transaction::getId, Comparator.nullsLast(Comparator.naturalOrder()));

  private final PositionRepository positionRepository;
  private final PortfolioService portfolioService;
  private final TransactionRepository transactionRepository;

  public PositionService(
      PositionRepository positionRepository,
      PortfolioService portfolioService,
      TransactionRepository transactionRepository) {
    this.positionRepository = positionRepository;
    this.portfolioService = portfolioService;
    this.transactionRepository = transactionRepository;
  }

  @Transactional(readOnly = true)
  public List<PositionResponse> getPositions(UUID userId, UUID portfolioId) {
    portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);
    return positionRepository.findAllByPortfolioId(portfolioId).stream()
        .map(PositionResponse::from)
        .toList();
  }

  @Transactional
  public void recalculatePosition(
      Portfolio portfolio,
      UUID instrumentId,
      Currency instrumentCurrency,
      String seriesName) {
    String cleanSeries = SeriesUtils.normalize(seriesName);

    List<Transaction> transactions = null;
    if (cleanSeries != null) {
      try {
        transactions = transactionRepository.findAllByPortfolioIdAndInstrumentIdAndSeriesNameOrderByExecutedAtAsc(
            portfolio.getId(), instrumentId, cleanSeries);
      } catch (Exception ignored) {
      }
    }
    if (transactions == null || transactions.isEmpty()) {
      transactions = transactionRepository.findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(
          portfolio.getId(), instrumentId);
    }

    transactions = transactions.stream()
        .filter(t -> Objects.equals(SeriesUtils.normalize(t.getSeriesName()), cleanSeries))
        .sorted(TX_ORDER)
        .toList();

    Optional<Position> posOpt = (cleanSeries != null)
        ? positionRepository.findFirstByPortfolioIdAndInstrumentIdAndSeriesName(portfolio.getId(), instrumentId, cleanSeries)
        : positionRepository.findFirstByPortfolioIdAndInstrumentIdAndSeriesNameIsNull(portfolio.getId(), instrumentId);

    if (transactions.isEmpty()) {
      posOpt.ifPresent(positionRepository::delete);
      return;
    }

    Currency resolvedCurrency = instrumentCurrency;
    if (resolvedCurrency == null) {
      resolvedCurrency = posOpt.map(Position::getCurrency).orElse(transactions.get(0).getCurrency());
    }

    List<BuyLot> buyLots = new ArrayList<>();
    BigDecimal currentTotalQuantity = BigDecimal.ZERO;

    for (Transaction tx : transactions) {
      if (tx.getTransactionType() == TransactionType.BUY) {
        BigDecimal qty = tx.getQuantity();
        BigDecimal resolvedPrice = tx.getPrice();
        BigDecimal comm = tx.getCommission() != null ? tx.getCommission() : BigDecimal.ZERO;
        if (tx.getExchangeRate() != null
            && tx.getExchangeRate().compareTo(BigDecimal.ONE) != 0
            && tx.getExchangeRate().compareTo(BigDecimal.ZERO) > 0) {
          resolvedPrice = tx.getPrice().divide(tx.getExchangeRate(), 6, RoundingMode.HALF_UP);
          comm = comm.divide(tx.getExchangeRate(), 6, RoundingMode.HALF_UP);
        }
        BigDecimal costNative = resolvedPrice.multiply(qty).add(comm);
        BigDecimal costBase = tx.getBaseAmount() != null ? tx.getBaseAmount() : costNative;

        buyLots.add(new BuyLot(
            qty,
            costNative,
            costBase,
            tx.getExecutedAt(),
            tx.getBondRate(),
            tx.getBondMargin(),
            tx.getTermMonths()
        ));
        currentTotalQuantity = currentTotalQuantity.add(qty);
      } else if (tx.getTransactionType() == TransactionType.SELL) {
        BigDecimal sellQty = tx.getQuantity();
        if (currentTotalQuantity.compareTo(sellQty) < 0) {
          BigDecimal diff = currentTotalQuantity.subtract(sellQty);
          throw new InsufficientPositionException(
              "Insufficient quantity: transaction on " + tx.getExecutedAt() + " causes negative balance (" + diff + ")");
        }

        BigDecimal toDeduct = sellQty;
        for (BuyLot lot : buyLots) {
          if (lot.remainingQuantity.compareTo(BigDecimal.ZERO) > 0) {
            if (lot.remainingQuantity.compareTo(toDeduct) <= 0) {
              toDeduct = toDeduct.subtract(lot.remainingQuantity);
              lot.remainingQuantity = BigDecimal.ZERO;
            } else {
              lot.remainingQuantity = lot.remainingQuantity.subtract(toDeduct);
              toDeduct = BigDecimal.ZERO;
              break;
            }
          }
        }
        currentTotalQuantity = currentTotalQuantity.subtract(sellQty);
      }
    }

    List<BuyLot> activeLots = buyLots.stream()
        .filter(lot -> lot.remainingQuantity.compareTo(BigDecimal.ZERO) > 0)
        .toList();

    if (activeLots.isEmpty() || currentTotalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      posOpt.ifPresent(positionRepository::delete);
      return;
    }

    BigDecimal totalQuantity = BigDecimal.ZERO;
    BigDecimal totalCostNative = BigDecimal.ZERO;
    BigDecimal totalCostBase = BigDecimal.ZERO;
    BigDecimal bondRate = null;
    BigDecimal bondMargin = null;
    Integer termMonths = null;

    for (BuyLot lot : activeLots) {
      totalQuantity = totalQuantity.add(lot.remainingQuantity);
      BigDecimal lotCostNative = lot.initialCostNative.multiply(lot.remainingQuantity)
          .divide(lot.initialQuantity, 10, RoundingMode.HALF_UP);
      BigDecimal lotCostBase = lot.initialCostBase.multiply(lot.remainingQuantity)
          .divide(lot.initialQuantity, 10, RoundingMode.HALF_UP);
      totalCostNative = totalCostNative.add(lotCostNative);
      totalCostBase = totalCostBase.add(lotCostBase);

      if (lot.bondRate != null) bondRate = lot.bondRate;
      if (lot.bondMargin != null) bondMargin = lot.bondMargin;
      if (lot.termMonths != null) termMonths = lot.termMonths;
    }

    Position position = posOpt.orElseGet(() -> {
      Position p = new Position();
      p.setPortfolio(portfolio);
      p.setInstrumentId(instrumentId);
      return p;
    });

    BigDecimal averageBuyPrice = totalCostNative.divide(totalQuantity, 6, RoundingMode.HALF_UP);
    position.setQuantity(totalQuantity);
    position.setAverageBuyPrice(averageBuyPrice);
    position.setTotalCostBase(totalCostBase.setScale(4, RoundingMode.HALF_UP));
    position.setCurrency(resolvedCurrency);
    position.setSeriesName(cleanSeries);
    position.setPurchaseDate(activeLots.get(0).executedAt != null ? activeLots.get(0).executedAt : LocalDateTime.now());
    position.setBondRate(bondRate);
    position.setBondMargin(bondMargin);
    position.setTermMonths(termMonths);

    positionRepository.save(position);
  }

  private static class BuyLot {
    final BigDecimal initialQuantity;
    BigDecimal remainingQuantity;
    final BigDecimal initialCostNative;
    final BigDecimal initialCostBase;
    final LocalDateTime executedAt;
    final BigDecimal bondRate;
    final BigDecimal bondMargin;
    final Integer termMonths;

    BuyLot(
        BigDecimal quantity,
        BigDecimal initialCostNative,
        BigDecimal initialCostBase,
        LocalDateTime executedAt,
        BigDecimal bondRate,
        BigDecimal bondMargin,
        Integer termMonths) {
      this.initialQuantity = quantity;
      this.remainingQuantity = quantity;
      this.initialCostNative = initialCostNative;
      this.initialCostBase = initialCostBase;
      this.executedAt = executedAt;
      this.bondRate = bondRate;
      this.bondMargin = bondMargin;
      this.termMonths = termMonths;
    }
  }
}
