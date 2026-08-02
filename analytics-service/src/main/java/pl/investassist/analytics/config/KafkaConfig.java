package pl.investassist.analytics.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaConfig {

    public static final String SIMULATION_COMPLETED_TOPIC = "analytics.simulation.completed";

    @Bean
    public NewTopic simulationCompletedTopic() {
        return TopicBuilder.name(SIMULATION_COMPLETED_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
