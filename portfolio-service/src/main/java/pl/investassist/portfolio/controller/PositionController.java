package pl.investassist.portfolio.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.investassist.portfolio.dto.response.PositionResponse;
import pl.investassist.portfolio.service.PositionService;

@RestController
@RequestMapping("/api/portfolios/{portfolioId}/positions")
public class PositionController {
  private final PositionService positionService;

  public PositionController(PositionService positionService) {
    this.positionService = positionService;
  }

  @GetMapping
  public List<PositionResponse> getAllPositions(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID portfolioId) {
    return positionService.getPositions(userId, portfolioId);
  }
}
