package pl.investassist.portfolio.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.investassist.portfolio.dto.request.CreatePortfolioRequest;
import pl.investassist.portfolio.dto.request.UpdatePortfolioRequest;
import pl.investassist.portfolio.dto.response.PortfolioResponse;
import pl.investassist.portfolio.service.PortfolioService;

@RestController
@RequestMapping("/api/portfolios")
public class PortfolioController {
  private final PortfolioService portfolioService;

  public PortfolioController(PortfolioService portfolioService) {
    this.portfolioService = portfolioService;
  }

  @PostMapping
  public PortfolioResponse createPortfolio(
      @RequestHeader("X-User-Id") UUID userId, @Valid @RequestBody CreatePortfolioRequest request) {
    return portfolioService.createPortfolio(userId, request);
  }

  @GetMapping
  public List<PortfolioResponse> getAllPortfolios(
      @RequestHeader("X-User-Id") UUID userId) {
    return portfolioService.getAllPortfolios(userId);
  }

  @GetMapping("/{id}")
  public PortfolioResponse getPortfolio(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID id) {
    return portfolioService.getPortfolio(userId, id);
  }

  @PutMapping("/{id}")
  public PortfolioResponse updatePortfolio(
      @RequestHeader("X-User-Id") UUID userId,
      @Valid @RequestBody UpdatePortfolioRequest request,
      @PathVariable UUID id) {
    return portfolioService.updatePortfolio(userId, id, request);
  }

  @DeleteMapping("/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void deletePortfolio(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID id) {
    portfolioService.deletePortfolio(userId, id);
  }
}
