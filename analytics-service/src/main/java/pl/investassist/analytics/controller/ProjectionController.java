package pl.investassist.analytics.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.investassist.analytics.dto.request.InvestmentProjectionRequest;
import pl.investassist.analytics.dto.request.MonteCarloRequest;
import pl.investassist.analytics.dto.response.InvestmentProjectionResponse;
import pl.investassist.analytics.dto.response.MonteCarloResponse;
import pl.investassist.analytics.service.ProjectionService;

@RestController
@RequestMapping("/api/analytics")
public class ProjectionController {
  private final ProjectionService projectionService;

  public ProjectionController(ProjectionService projectionService) {
    this.projectionService = projectionService;
  }

  @PostMapping("/projections")
  public ResponseEntity<InvestmentProjectionResponse> createProjection(
      @Valid @RequestBody InvestmentProjectionRequest projection) {
    return ResponseEntity.ok(projectionService.calculateProjection(projection));
  }

  @PostMapping("/monte-carlo")
  public ResponseEntity<MonteCarloResponse> createMonteCarlo(
      @Valid @RequestBody MonteCarloRequest monteCarlo) {
    return ResponseEntity.ok(projectionService.calculateMonteCarlo(monteCarlo));
  }
}
