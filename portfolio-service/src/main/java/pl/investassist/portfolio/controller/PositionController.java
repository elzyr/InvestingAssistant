package pl.investassist.portfolio.controller;

import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
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
  public ResponseEntity<List<PositionResponse>> getAllPositions(
      @RequestHeader("X-User-Id") UUID userId, @PathVariable UUID portfolioId) {
    return ResponseEntity.ok().body(positionService.getPositions(userId, portfolioId));
  }
}
