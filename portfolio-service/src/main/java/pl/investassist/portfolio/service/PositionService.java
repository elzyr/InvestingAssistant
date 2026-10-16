package pl.investassist.portfolio.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.investassist.portfolio.dto.response.PositionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Position;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.Currency;
import pl.investassist.portfolio.enums.TransactionType;
import pl.investassist.portfolio.exception.InsufficientPositionException;
import pl.investassist.portfolio.repository.PositionRepository;
import pl.investassist.portfolio.repository.TransactionRepository;

@Service
public class PositionService {

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
    String cleanSeries = (seriesName != null && !seriesName.trim().isBlank()) ? seriesName.trim() : null;

    List<Transaction> transactions = transactionRepository
        .findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(portfolio.getId(), instrumentId)
        .stream()
        .filter(t -> {
          String s = (t.getSeriesName() != null && !t.getSeriesName().trim().isBlank()) ? t.getSeriesName().trim() : null;
          return Objects.equals(s, cleanSeries);
        })
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

    BigDecimal totalQuantity = BigDecimal.ZERO;
    BigDecimal totalCostNative = BigDecimal.ZERO;
    BigDecimal totalCostBase = BigDecimal.ZERO;
    LocalDateTime firstPurchaseDate = null;
    BigDecimal bondRate = null;
    BigDecimal bondMargin = null;
    Integer termMonths = null;

    for (Transaction tx : transactions) {
      if (tx.getTransactionType() == TransactionType.BUY) {
        BigDecimal qty = tx.getQuantity();
        BigDecimal resolvedPrice = tx.getPrice();
        if (tx.getExchangeRate() != null && tx.getExchangeRate().compareTo(BigDecimal.ZERO) > 0) {
          resolvedPrice = tx.getPrice().divide(tx.getExchangeRate(), 6, RoundingMode.HALF_UP);
        }
        BigDecimal costNative = resolvedPrice.multiply(qty);
        BigDecimal costBase = tx.getBaseAmount() != null ? tx.getBaseAmount() : costNative;

        totalQuantity = totalQuantity.add(qty);
        totalCostNative = totalCostNative.add(costNative);
        totalCostBase = totalCostBase.add(costBase);

        if (firstPurchaseDate == null && tx.getExecutedAt() != null) {
          firstPurchaseDate = tx.getExecutedAt();
        }
        if (tx.getBondRate() != null) bondRate = tx.getBondRate();
        if (tx.getBondMargin() != null) bondMargin = tx.getBondMargin();
        if (tx.getTermMonths() != null) termMonths = tx.getTermMonths();
      } else if (tx.getTransactionType() == TransactionType.SELL) {
        BigDecimal sellQty = tx.getQuantity();
        BigDecimal remaining = totalQuantity.subtract(sellQty);
        if (remaining.compareTo(BigDecimal.ZERO) < 0) {
          throw new InsufficientPositionException(
              "Insufficient quantity: transaction on " + tx.getExecutedAt() + " causes negative balance (" + remaining + ")");
        }
        if (totalQuantity.compareTo(BigDecimal.ZERO) > 0) {
          BigDecimal fraction = sellQty.divide(totalQuantity, 10, RoundingMode.HALF_UP);
          totalCostNative = totalCostNative.subtract(totalCostNative.multiply(fraction));
          totalCostBase = totalCostBase.subtract(totalCostBase.multiply(fraction));
          totalQuantity = remaining;

          if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            totalQuantity = BigDecimal.ZERO;
            totalCostNative = BigDecimal.ZERO;
            totalCostBase = BigDecimal.ZERO;
          }
        }
      }
    }

    if (totalQuantity.compareTo(BigDecimal.ZERO) <= 0) {
      posOpt.ifPresent(positionRepository::delete);
      return;
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
    position.setPurchaseDate(firstPurchaseDate != null ? firstPurchaseDate : LocalDateTime.now());
    position.setBondRate(bondRate);
    position.setBondMargin(bondMargin);
    position.setTermMonths(termMonths);

    positionRepository.save(position);
  }
}
