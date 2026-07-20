package pl.investassist.portfolio.client;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pl.investassist.portfolio.dto.response.ExchangeRateResponse;
import pl.investassist.portfolio.enums.Currency;

@Component
public class MarketDataClient {
  private final RestClient restClient;

  public MarketDataClient(
      @Value("${services.market-data-service.url}") String marketDataServiceUrl) {
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(Duration.ofSeconds(5));
    factory.setReadTimeout(Duration.ofSeconds(10));

    this.restClient = RestClient.builder()
        .baseUrl(marketDataServiceUrl)
        .requestFactory(factory)
        .build();
  }

  public BigDecimal getExchangeRate(Currency from, Currency to) {
    try {
      ExchangeRateResponse response =
          restClient
              .get()
              .uri(
                  "/api/currencies/rate?from_currency={from}&to_currency={to}",
                  from.name(),
                  to.name())
              .retrieve()
              .body(ExchangeRateResponse.class);
      if (response == null || response.rate() == null) {
        throw new IllegalStateException("Failed to fetch exchange rate");
      }
      return response.rate();
    } catch (Exception e) {
      throw new IllegalStateException("Market data service is not available", e);
    }
  }
}
