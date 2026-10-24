package pl.investassist.instrument.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.repository.InstrumentRepository;
import pl.investassist.portfolio.client.MarketDataClient;

@ExtendWith(MockitoExtension.class)
class InstrumentServiceTest {

  @Mock
  private InstrumentRepository instrumentRepository;

  @Mock
  private MarketDataClient marketDataClient;

  private InstrumentService instrumentService;

  @BeforeEach
  void setUp() {
    instrumentService = createInstrumentService(instrumentRepository, marketDataClient);
  }

  private InstrumentService createInstrumentService(
      InstrumentRepository repository, MarketDataClient client) {
    try {
      return InstrumentService.class
          .getConstructor(InstrumentRepository.class, MarketDataClient.class)
          .newInstance(repository, client);
    } catch (NoSuchMethodException e) {
      try {
        InstrumentService service =
            InstrumentService.class
                .getConstructor(InstrumentRepository.class)
                .newInstance(repository);
        try {
          ReflectionTestUtils.setField(service, "marketDataClient", client);
        } catch (Exception ignored) {
        }
        return service;
      } catch (Exception ex) {
        throw new RuntimeException(ex);
      }
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @Test
  void getInstruments_whenSearchHasAtLeastTwoCharsAndNotInDb_callsMarketDataClientAndSaves() {
    Instrument saved = new Instrument();
    saved.setId(UUID.randomUUID());
    saved.setSymbol("AMZN");
    saved.setName("Amazon.com Inc.");
    saved.setType(InstrumentType.STOCK);
    saved.setCurrency(Currency.USD);

    when(instrumentRepository.searchInstruments(null, "AMZN")).thenReturn(List.of());
    lenient()
        .when(marketDataClient.searchInstruments("AMZN"))
        .thenReturn(
            List.of(
                new InstrumentResponse(null, "AMZN", "Amazon.com Inc.", InstrumentType.STOCK, Currency.USD)));
    lenient().when(instrumentRepository.findBySymbolIgnoreCase("AMZN")).thenReturn(Optional.empty());
    lenient()
        .when(instrumentRepository.save(any(Instrument.class)))
        .thenAnswer(
            i -> {
              Instrument inst = i.getArgument(0);
              inst.setId(UUID.randomUUID());
              return inst;
            });

    List<InstrumentResponse> results = instrumentService.getInstruments("AMZN", null);

    verify(marketDataClient).searchInstruments("AMZN");
    ArgumentCaptor<Instrument> captor = ArgumentCaptor.forClass(Instrument.class);
    verify(instrumentRepository).save(captor.capture());
    assertThat(captor.getValue().getSymbol()).isEqualTo("AMZN");
    assertThat(captor.getValue().getName()).isEqualTo("Amazon.com Inc.");
    assertThat(captor.getValue().getType()).isEqualTo(InstrumentType.STOCK);
    assertThat(captor.getValue().getCurrency()).isEqualTo(Currency.USD);
    assertThat(results).isNotEmpty();
    assertThat(results).extracting(InstrumentResponse::symbol).contains("AMZN");
  }

  @Test
  void getInstruments_whenSearchLengthLessThanTwoChars_doesNotCallMarketDataClient() {
    when(instrumentRepository.searchInstruments(null, "A")).thenReturn(List.of());

    List<InstrumentResponse> results = instrumentService.getInstruments("A", null);

    verify(marketDataClient, never()).searchInstruments(anyString());
    assertThat(results).isEmpty();
  }

  @Test
  void getInstruments_whenExternalSearchReturnsExistingSymbol_doesNotSaveDuplicate() {
    Instrument existing = new Instrument();
    existing.setId(UUID.randomUUID());
    existing.setSymbol("AMZN");
    existing.setName("Amazon.com Inc.");
    existing.setType(InstrumentType.STOCK);
    existing.setCurrency(Currency.USD);

    when(instrumentRepository.searchInstruments(null, "AMZN")).thenReturn(List.of());
    lenient()
        .when(marketDataClient.searchInstruments("AMZN"))
        .thenReturn(
            List.of(
                new InstrumentResponse(null, "AMZN", "Amazon.com Inc.", InstrumentType.STOCK, Currency.USD)));
    lenient().when(instrumentRepository.findBySymbolIgnoreCase("AMZN")).thenReturn(Optional.of(existing));

    List<InstrumentResponse> results = instrumentService.getInstruments("AMZN", null);

    verify(marketDataClient).searchInstruments("AMZN");
    verify(instrumentRepository, never()).save(any(Instrument.class));
    assertThat(results).isNotEmpty();
    assertThat(results).extracting(InstrumentResponse::symbol).contains("AMZN");
  }

  @Test
  void getInstruments_whenMarketDataClientThrowsException_returnsExistingDbItemsWithoutFailing() {
    Instrument local = new Instrument();
    local.setId(UUID.randomUUID());
    local.setSymbol("AMZ_LOCAL");
    local.setName("Local Amazon");
    local.setType(InstrumentType.STOCK);
    local.setCurrency(Currency.USD);

    when(instrumentRepository.searchInstruments(null, "AMZ")).thenReturn(List.of(local));
    lenient()
        .when(marketDataClient.searchInstruments("AMZ"))
        .thenThrow(new RuntimeException("Market data service offline"));

    List<InstrumentResponse> results = instrumentService.getInstruments("AMZ", null);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).symbol()).isEqualTo("AMZ_LOCAL");
  }
}
