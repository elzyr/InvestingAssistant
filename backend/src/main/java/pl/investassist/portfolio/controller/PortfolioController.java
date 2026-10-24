package pl.investassist.portfolio.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.investassist.portfolio.dto.request.CreatePortfolioRequest;
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
}
