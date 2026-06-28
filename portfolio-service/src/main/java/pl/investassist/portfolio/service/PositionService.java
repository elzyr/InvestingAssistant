package pl.investassist.portfolio.service;

import java.util.List;
import java.util.UUID;
import pl.investassist.portfolio.dto.request.CreateTransactionRequest;
import pl.investassist.portfolio.dto.response.PositionResponse;
import pl.investassist.portfolio.entity.Portfolio;

public interface PositionService {
  List<PositionResponse> getPositions(UUID userId, UUID portfolioId);

  void updatePosition(Portfolio portfolio, CreateTransactionRequest request);
}
