package pl.investassist.portfolio.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.investassist.common.exception.AccessDeniedException;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.portfolio.dto.request.CreatePortfolioRequest;
import pl.investassist.portfolio.dto.response.PortfolioResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.repository.PortfolioRepository;

@Service
public class PortfolioService {
  private final PortfolioRepository portfolioRepository;

  public PortfolioService(PortfolioRepository portfolioRepository) {
    this.portfolioRepository = portfolioRepository;
  }

  public PortfolioResponse createPortfolio(UUID userId, CreatePortfolioRequest request) {
    Portfolio savedPortfolio = portfolioRepository.save(request.toEntity(userId));
    return PortfolioResponse.from(savedPortfolio);
  }

  @Transactional(readOnly = true)
  public List<PortfolioResponse> getAllPortfolios(UUID userId) {
    return portfolioRepository.findAllByUserId(userId).stream()
        .map(PortfolioResponse::from)
        .toList();
  }

  @Transactional(readOnly = true)
  public Portfolio validateAndGetPortfolioEntity(UUID userId, UUID portfolioId) {
    Portfolio foundPortfolio =
        portfolioRepository
            .findById(portfolioId)
            .orElseThrow(
                () -> new ResourceNotFoundException("Portfolio not found: " + portfolioId));
    if (!foundPortfolio.getUserId().equals(userId)) {
      throw new AccessDeniedException("You do not have access to this portfolio");
    }
    return foundPortfolio;
  }
}
