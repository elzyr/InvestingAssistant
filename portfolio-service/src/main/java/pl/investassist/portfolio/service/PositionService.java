package pl.investassist.portfolio.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.PositionResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.enums.Currency;

public interface PositionService {
  List<PositionResponse> getPositions(UUID userId, UUID portfolioId);

  void updatePosition(
      Portfolio portfolio,
      CreateTransactionRequest request,
      Currency instrumentCurrency,
      BigDecimal resolvedPrice);
}
