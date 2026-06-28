package pl.investassist.portfolio.service.impl;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.PositionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Position;
import pl.investassist.portfolio.enums.TransactionType;
import pl.investassist.portfolio.exception.InsufficientPositionException;
import pl.investassist.portfolio.mapper.PositionMapper;
import pl.investassist.portfolio.repository.PositionRepository;
import pl.investassist.portfolio.service.PortfolioService;
import pl.investassist.portfolio.service.PositionService;

@Service
public class PositionServiceImpl implements PositionService {

  private final PositionRepository positionRepository;
  private final PortfolioService portfolioService;

  public PositionServiceImpl(
      PositionRepository positionRepository, PortfolioService portfolioService) {
    this.positionRepository = positionRepository;
    this.portfolioService = portfolioService;
  }

  @Override
  public List<PositionResponse> getPositions(UUID userId, UUID portfolioId) {
    portfolioService.validateAndGetPortfolioEntity(userId, portfolioId);

    return positionRepository.findAllByPortfolioId(portfolioId).stream()
        .map(PositionMapper::toResponse)
        .toList();
  }

  @Override
  public void updatePosition(Portfolio portfolio, CreateTransactionRequest request) {
    Position position =
        positionRepository
            .findByPortfolioIdAndInstrumentId(portfolio.getId(), request.instrumentId())
            .orElseGet(() -> createPosition(portfolio, request));

    if (request.transactionType().equals(TransactionType.BUY)) {
      operateBuy(position, request);
    } else if (request.transactionType().equals(TransactionType.SELL)) {
      operateSell(position, request);
    } else {
      throw new IllegalArgumentException("This transaction type does not exist");
    }
  }

  private Position createPosition(Portfolio portfolio, CreateTransactionRequest request) {
    Position position = new Position();
    position.setPortfolio(portfolio);
    position.setInstrumentId(request.instrumentId());
    position.setQuantity(BigDecimal.ZERO);
    position.setAverageBuyPrice(BigDecimal.ZERO);
    position.setCurrency(request.currency());
    return position;
  }

  private void operateBuy(Position position, CreateTransactionRequest request) {
    BigDecimal newQuantity = position.getQuantity().add(request.quantity());
    BigDecimal transactionValue = request.price().multiply(request.quantity());
    BigDecimal currentPositionValue =
        position.getAverageBuyPrice().multiply(position.getQuantity());
    BigDecimal newAveragePrice =
        currentPositionValue.add(transactionValue).divide(newQuantity, 6, RoundingMode.HALF_UP);
    position.setAverageBuyPrice(newAveragePrice);
    position.setQuantity(newQuantity);
    positionRepository.save(position);
  }

  private void operateSell(Position position, CreateTransactionRequest request) {
    int comparison = position.getQuantity().compareTo(request.quantity());
    if (comparison < 0) {
      throw new InsufficientPositionException("You do not have enough quantity");
    } else if (comparison == 0) {
      positionRepository.delete(position);
    } else {
      position.setQuantity(position.getQuantity().subtract(request.quantity()));
      positionRepository.save(position);
    }
  }
}
