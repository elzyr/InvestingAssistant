package pl.investassist.analytics.controller;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.investassist.analytics.dto.request.MonteCarloRequest;
import pl.investassist.analytics.dto.response.MonteCarloResponse;
import pl.investassist.analytics.service.MonteCarloService;

@RestController
@RequestMapping("/api/analytics")
public class ProjectionController {

  private final MonteCarloService monteCarloService;

  public ProjectionController(MonteCarloService monteCarloService) {
    this.monteCarloService = monteCarloService;
  }

  @PostMapping("/monte-carlo")
  public ResponseEntity<MonteCarloResponse> createMonteCarlo(
      @Valid @RequestBody MonteCarloRequest monteCarlo) {
    return ResponseEntity.ok(monteCarloService.calculateMonteCarlo(monteCarlo));
  }
}
