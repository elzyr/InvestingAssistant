package pl.investassist.analytics.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;
import org.springframework.stereotype.Service;
import pl.investassist.analytics.dto.MonteCarloPoint;
import pl.investassist.analytics.dto.request.MonteCarloRequest;
import pl.investassist.analytics.dto.response.MonteCarloResponse;

@Service
public class MonteCarloService {

  public MonteCarloResponse calculateMonteCarlo(MonteCarloRequest request) {
    double monthlyReturn = request.expectedAnnualReturn() / 12;
    double monthlyVolatility = request.annualVolatility() / Math.sqrt(12);
    double drift = monthlyReturn - (0.5 * Math.pow(monthlyVolatility, 2));

    double[][] yearlyResults = new double[request.timeHorizonYears()][request.simulationsCount()];

    IntStream.range(0, request.simulationsCount())
        .parallel()
        .forEach(
            simIndex -> {
              double currentPortfolio = request.initialInvestment().doubleValue();
              double monthlyContribution = request.monthlyContribution().doubleValue();

              for (int i = 0; i < request.timeHorizonYears() * 12; i++) {
                double gaussian = ThreadLocalRandom.current().nextGaussian();
                double growthFactor = Math.exp(drift + (monthlyVolatility * gaussian));
                currentPortfolio = (currentPortfolio * growthFactor) + monthlyContribution;

                if ((i + 1) % 12 == 0) {
                  yearlyResults[i / 12][simIndex] = currentPortfolio;
                }
              }
            });

    List<MonteCarloPoint> monteCarloPoints = new ArrayList<>();
    int p10Index = (int) Math.floor((request.simulationsCount() - 1) * 0.10);
    int p50Index = (int) Math.floor((request.simulationsCount() - 1) * 0.50);
    int p90Index = (int) Math.floor((request.simulationsCount() - 1) * 0.90);

    for (int i = 0; i < request.timeHorizonYears(); i++) {
      Arrays.sort(yearlyResults[i]);

      BigDecimal worstCase =
          BigDecimal.valueOf(yearlyResults[i][p10Index]).setScale(2, RoundingMode.HALF_UP);
      BigDecimal medianCase =
          BigDecimal.valueOf(yearlyResults[i][p50Index]).setScale(2, RoundingMode.HALF_UP);
      BigDecimal bestCase =
          BigDecimal.valueOf(yearlyResults[i][p90Index]).setScale(2, RoundingMode.HALF_UP);

      monteCarloPoints.add(new MonteCarloPoint(i + 1, worstCase, medianCase, bestCase));
    }

    MonteCarloPoint lastPoint = monteCarloPoints.getLast();

    return new MonteCarloResponse(
        lastPoint.worstCase(), lastPoint.medianCase(), lastPoint.bestCase(), monteCarloPoints);
  }
}
