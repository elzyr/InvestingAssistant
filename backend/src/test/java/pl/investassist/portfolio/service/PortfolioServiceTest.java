package pl.investassist.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.AccessDeniedException;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.portfolio.dto.request.CreatePortfolioRequest;
import pl.investassist.portfolio.dto.response.PortfolioResponse;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.repository.PortfolioRepository;

@ExtendWith(MockitoExtension.class)
class PortfolioServiceTest {

  @Mock
  private PortfolioRepository portfolioRepository;

  private PortfolioService portfolioService;

  @BeforeEach
  void setUp() {
    portfolioService = new PortfolioService(portfolioRepository);
  }

  @Test
  void createPortfolio_persistsWithUserIdAndBaseCurrency() {
    UUID userId = UUID.randomUUID();
    CreatePortfolioRequest request =
        new CreatePortfolioRequest("Retirement", "Long-term", Currency.PLN);

    when(portfolioRepository.save(any(Portfolio.class)))
        .thenAnswer(
            inv -> {
              Portfolio p = inv.getArgument(0);
              p.setId(UUID.randomUUID());
              return p;
            });

    PortfolioResponse created = portfolioService.createPortfolio(userId, request);

    assertThat(created.id()).isNotNull();
    assertThat(created.name()).isEqualTo("Retirement");
    assertThat(created.baseCurrency()).isEqualTo(Currency.PLN);
    verify(portfolioRepository).save(any(Portfolio.class));
  }

  @Test
  void getAllPortfolios_mapsAllOwnedPortfolios() {
    UUID userId = UUID.randomUUID();
    Portfolio p1 = buildPortfolio(UUID.randomUUID(), userId, "Core", Currency.PLN);
    Portfolio p2 = buildPortfolio(UUID.randomUUID(), userId, "Global", Currency.EUR);
    when(portfolioRepository.findAllByUserId(userId)).thenReturn(List.of(p1, p2));

    List<PortfolioResponse> result = portfolioService.getAllPortfolios(userId);

    assertThat(result).hasSize(2);
    assertThat(result).extracting(PortfolioResponse::name).containsExactly("Core", "Global");
    assertThat(result).extracting(PortfolioResponse::baseCurrency).containsExactly(Currency.PLN, Currency.EUR);
  }

  @Test
  void validateAndGetPortfolioEntity_enforcesExistenceAndOwnership() {
    UUID ownerId = UUID.randomUUID();
    UUID intruderId = UUID.randomUUID();
    UUID portfolioId = UUID.randomUUID();
    Portfolio portfolio = buildPortfolio(portfolioId, ownerId, "Core", Currency.PLN);

    when(portfolioRepository.findById(portfolioId)).thenReturn(Optional.of(portfolio));
    assertThat(portfolioService.validateAndGetPortfolioEntity(ownerId, portfolioId)).isSameAs(portfolio);

    assertThatThrownBy(() -> portfolioService.validateAndGetPortfolioEntity(intruderId, portfolioId))
        .isInstanceOf(AccessDeniedException.class);

    UUID missingId = UUID.randomUUID();
    when(portfolioRepository.findById(missingId)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> portfolioService.validateAndGetPortfolioEntity(ownerId, missingId))
        .isInstanceOf(ResourceNotFoundException.class);
  }

  private static Portfolio buildPortfolio(
      UUID id, UUID userId, String name, Currency baseCurrency) {
    Portfolio portfolio = new Portfolio();
    portfolio.setId(id);
    portfolio.setUserId(userId);
    portfolio.setName(name);
    portfolio.setBaseCurrency(baseCurrency);
    return portfolio;
  }
}
