package pl.investassist.portfolio.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
  public ResponseEntity<PortfolioResponse> createPortfolio(
      @RequestHeader("X-User-Id") UUID userId, @Valid @RequestBody CreatePortfolioRequest request) {
    return ResponseEntity.ok().body(portfolioService.createPortfolio(userId, request));
  }

  @GetMapping
  public ResponseEntity<List<PortfolioResponse>> getAllPortfolios(
      @RequestHeader("X-User-Id") UUID userId) {
    return ResponseEntity.ok().body(portfolioService.getAllPortfolios(userId));
  }

  @GetMapping("/{id}")
  public ResponseEntity<PortfolioResponse> getPortfolio(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID id) {
    return ResponseEntity.ok().body(portfolioService.getPortfolio(userId, id));
  }

  @PutMapping("/{id}")
  public ResponseEntity<PortfolioResponse> updatePortfolio(
      @RequestHeader("X-User-Id") UUID userId,
      @Valid @RequestBody UpdatePortfolioRequest request,
      @PathVariable UUID id) {
    return ResponseEntity.ok().body(portfolioService.updatePortfolio(userId, id, request));
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> deletePortfolio(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID id) {
    portfolioService.deletePortfolio(userId, id);
    return ResponseEntity.noContent().build();
  }
}
