package pl.investassist.portfolio.client;

import java.time.Duration;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import pl.investassist.portfolio.dto.response.InstrumentResponse;
import pl.investassist.portfolio.exception.ResourceNotFoundException;

@Component
public class InstrumentClient {
  private final RestClient restClient;

  public InstrumentClient(
      @Value("${services.instrument-service.url}") String instrumentServiceUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(10));

    this.restClient = RestClient.builder()
        .baseUrl(instrumentServiceUrl)
        .requestFactory(factory)
        .build();
  }

  public InstrumentResponse getInstrumentById(UUID id) {
    try {
      return restClient
          .get()
          .uri("/api/instruments/{id}", id)
          .retrieve()
          .body(InstrumentResponse.class);
    } catch (HttpClientErrorException.NotFound e) {
      throw new ResourceNotFoundException("Instrument not found");
    } catch (Exception e) {
      throw new IllegalStateException("Instrument service is not available", e);
    }
  }
}
