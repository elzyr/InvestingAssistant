package pl.investassist.analytics.kafka;

import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import pl.investassist.analytics.config.KafkaConfig;
import pl.investassist.analytics.dto.event.MonteCarloCompletedEvent;
import pl.investassist.analytics.dto.event.ProjectionCompletedEvent;

@Component
public class SimulationEventProducer {

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public SimulationEventProducer(KafkaTemplate<String, Object> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  public void sendMonteCarloCompleted(BigDecimal worstCase, BigDecimal medianCase, BigDecimal bestCase) {
    MonteCarloCompletedEvent event =
        new MonteCarloCompletedEvent(
            "MONTE_CARLO", worstCase, medianCase, bestCase, Instant.now());
    kafkaTemplate.send(KafkaConfig.SIMULATION_COMPLETED_TOPIC, event.simulationType(), event);
  }

  public void sendProjectionCompleted(BigDecimal nominalValue, BigDecimal realValue, BigDecimal profit) {
    ProjectionCompletedEvent event =
        new ProjectionCompletedEvent(
            "INVESTMENT_PROJECTION", nominalValue, realValue, profit, Instant.now());
    kafkaTemplate.send(KafkaConfig.SIMULATION_COMPLETED_TOPIC, event.simulationType(), event);
  }
}
