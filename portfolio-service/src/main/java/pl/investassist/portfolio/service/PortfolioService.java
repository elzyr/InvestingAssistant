package pl.investassist.portfolio.service;

import java.util.List;
import java.util.UUID;
import pl.investassist.portfolio.dto.request.CreatePortfolioRequest;
import pl.investassist.portfolio.dto.request.UpdatePortfolioRequest;
import pl.investassist.portfolio.dto.response.PortfolioResponse;
import pl.investassist.portfolio.entity.Portfolio;

public interface PortfolioService {
  PortfolioResponse createPortfolio(UUID userId, CreatePortfolioRequest request);

  PortfolioResponse getPortfolio(UUID userId, UUID id);

  Portfolio validateAndGetPortfolioEntity(UUID userId, UUID portfolioId);

  List<PortfolioResponse> getAllPortfolios(UUID userId);

  PortfolioResponse updatePortfolio(UUID userId, UUID id, UpdatePortfolioRequest request);

  void deletePortfolio(UUID userId, UUID id);
}
