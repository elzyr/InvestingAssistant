package pl.investassist.analytics.service;

import pl.investassist.analytics.dto.request.InvestmentProjectionRequest;
import pl.investassist.analytics.dto.request.MonteCarloRequest;
import pl.investassist.analytics.dto.response.InvestmentProjectionResponse;
import pl.investassist.analytics.dto.response.MonteCarloResponse;

public interface ProjectionService {
  InvestmentProjectionResponse calculateProjection(InvestmentProjectionRequest request);

  MonteCarloResponse calculateMonteCarlo(MonteCarloRequest request);
}
