package pl.investassist.portfolio.service;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import pl.investassist.portfolio.dto.request.CreatePortfolioRequest;
import pl.investassist.portfolio.dto.request.UpdatePortfolioRequest;
import pl.investassist.portfolio.dto.response.PortfolioResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.exception.AccessDeniedException;
import pl.investassist.portfolio.exception.ResourceNotFoundException;
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

  public PortfolioResponse getPortfolio(UUID userId, UUID id) {
    return PortfolioResponse.from(validateAndGetPortfolioEntity(userId, id));
  }

  public List<PortfolioResponse> getAllPortfolios(UUID userId) {
    return portfolioRepository.findAllByUserId(userId).stream()
        .map(PortfolioResponse::from)
        .toList();
  }

  public PortfolioResponse updatePortfolio(UUID userId, UUID id, UpdatePortfolioRequest request) {
    Portfolio foundPortfolio = validateAndGetPortfolioEntity(userId, id);
    foundPortfolio.setName(request.name());
    foundPortfolio.setDescription(request.description());
    portfolioRepository.save(foundPortfolio);
    return PortfolioResponse.from(foundPortfolio);
  }

  public void deletePortfolio(UUID userId, UUID id) {
    portfolioRepository.delete(validateAndGetPortfolioEntity(userId, id));
  }

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
