package pl.investassist.portfolio.client;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.MarketDataUnavailableException;
import pl.investassist.portfolio.dto.response.ExchangeRateResponse;

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

  public BigDecimal getExchangeRate(Currency from, Currency to, LocalDate date) {
    if (from.equals(to)) {
      return BigDecimal.ONE;
    }
    try {
      String uri = date != null
          ? String.format("/api/currencies/rate?from_currency=%s&to_currency=%s&date=%s", from.name(), to.name(), date)
          : String.format("/api/currencies/rate?from_currency=%s&to_currency=%s", from.name(), to.name());
      ExchangeRateResponse response =
          restClient
              .get()
              .uri(uri)
              .retrieve()
              .body(ExchangeRateResponse.class);
      if (response == null || response.rate() == null) {
        throw new MarketDataUnavailableException("Failed to fetch exchange rate");
      }
      return response.rate();
    } catch (MarketDataUnavailableException e) {
      throw e;
    } catch (Exception e) {
      throw new MarketDataUnavailableException("Market data service is not available", e);
    }
  }
}
