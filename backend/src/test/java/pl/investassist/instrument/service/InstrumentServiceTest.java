package pl.investassist.instrument.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
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
    instrumentService = new InstrumentService(instrumentRepository, marketDataClient);
  }

  @Test
  void getInstruments_whenSearchHasAtLeastTwoCharsAndNotInDb_callsMarketDataClientAndSavesBatchWithoutNPlusOne() {
    when(instrumentRepository.searchInstruments(eq(null), eq("AMZN"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of()));
    when(marketDataClient.searchInstruments("AMZN"))
        .thenReturn(
            List.of(
                new InstrumentResponse(null, "AMZN", "Amazon.com Inc.", InstrumentType.STOCK, Currency.USD)));
    when(instrumentRepository.findBySymbolInIgnoreCase(Set.of("AMZN"))).thenReturn(List.of());
    when(instrumentRepository.saveAll(any()))
        .thenAnswer(
            i -> {
              List<Instrument> items = i.getArgument(0);
              items.forEach(inst -> inst.setId(UUID.randomUUID()));
              return items;
            });

    List<InstrumentResponse> results = instrumentService.getInstruments("AMZN", null, null, null);

    verify(marketDataClient).searchInstruments("AMZN");
    verify(instrumentRepository, never()).findBySymbolIgnoreCase(anyString());
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<Instrument>> captor = ArgumentCaptor.forClass(List.class);
    verify(instrumentRepository).saveAll(captor.capture());
    assertThat(captor.getValue()).hasSize(1);
    assertThat(captor.getValue().get(0).getSymbol()).isEqualTo("AMZN");
    assertThat(results).extracting(InstrumentResponse::symbol).containsExactly("AMZN");
  }

  @Test
  void getInstruments_whenSearchLengthLessThanTwoChars_doesNotCallMarketDataClient() {
    when(instrumentRepository.searchInstruments(eq(null), eq("A"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of()));

    List<InstrumentResponse> results = instrumentService.getInstruments("A", null, null, null);

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

    when(instrumentRepository.searchInstruments(eq(null), eq("AMZN"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of()));
    when(marketDataClient.searchInstruments("AMZN"))
        .thenReturn(
            List.of(
                new InstrumentResponse(null, "AMZN", "Amazon.com Inc.", InstrumentType.STOCK, Currency.USD)));
    when(instrumentRepository.findBySymbolInIgnoreCase(Set.of("AMZN"))).thenReturn(List.of(existing));

    List<InstrumentResponse> results = instrumentService.getInstruments("AMZN", null, null, null);

    verify(marketDataClient).searchInstruments("AMZN");
    verify(instrumentRepository, never()).saveAll(any());
    verify(instrumentRepository, never()).save(any(Instrument.class));
    assertThat(results).extracting(InstrumentResponse::symbol).containsExactly("AMZN");
  }

  @Test
  void getInstruments_whenMarketDataClientThrowsException_returnsExistingDbItemsWithoutFailing() {
    Instrument local = new Instrument();
    local.setId(UUID.randomUUID());
    local.setSymbol("AMZ_LOCAL");
    local.setName("Local Amazon");
    local.setType(InstrumentType.STOCK);
    local.setCurrency(Currency.USD);

    when(instrumentRepository.searchInstruments(eq(null), eq("AMZ"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of(local)));
    when(marketDataClient.searchInstruments("AMZ"))
        .thenThrow(new RuntimeException("Market data service offline"));

    List<InstrumentResponse> results = instrumentService.getInstruments("AMZ", null, null, null);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).symbol()).isEqualTo("AMZ_LOCAL");
  }

  @Test
  void getInstruments_whenExternalInstrumentsDiscovered_fetchesInBatchAndSavesViaSaveAll() {
    when(instrumentRepository.searchInstruments(eq(null), eq("TECH"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of()));
    when(marketDataClient.searchInstruments("TECH"))
        .thenReturn(
            List.of(
                new InstrumentResponse(null, "AAPL", "Apple Inc.", InstrumentType.STOCK, Currency.USD),
                new InstrumentResponse(null, "MSFT", "Microsoft Corp.", InstrumentType.STOCK, Currency.USD)));
    when(instrumentRepository.findBySymbolInIgnoreCase(any())).thenReturn(List.of());

    instrumentService.getInstruments("TECH", null, null, null);

    verify(instrumentRepository).findBySymbolInIgnoreCase(any());
    verify(instrumentRepository).saveAll(any());
    verify(instrumentRepository, never()).save(any(Instrument.class));
  }

  @Test
  void getInstruments_whenConcurrentDuplicateOnBatchInsert_savesRemainingInstrumentsIndividually() {
    Instrument existingAapl = new Instrument();
    existingAapl.setId(UUID.randomUUID());
    existingAapl.setSymbol("AAPL");
    existingAapl.setName("Apple Inc.");
    existingAapl.setType(InstrumentType.STOCK);
    existingAapl.setCurrency(Currency.USD);

    when(instrumentRepository.searchInstruments(eq(null), eq("AA"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of()));
    when(marketDataClient.searchInstruments("AA"))
        .thenReturn(
            List.of(
                new InstrumentResponse(null, "AAPL", "Apple Inc.", InstrumentType.STOCK, Currency.USD),
                new InstrumentResponse(null, "AAOI", "Applied Optoelectronics", InstrumentType.STOCK, Currency.USD)));
    when(instrumentRepository.findBySymbolInIgnoreCase(any())).thenReturn(List.of());
    when(instrumentRepository.saveAll(any()))
        .thenThrow(new DataIntegrityViolationException("duplicate key"));
    when(instrumentRepository.save(any(Instrument.class)))
        .thenAnswer(invocation -> {
          Instrument inst = invocation.getArgument(0);
          if ("AAPL".equals(inst.getSymbol())) {
            throw new DataIntegrityViolationException("duplicate key");
          }
          inst.setId(UUID.randomUUID());
          return inst;
        });
    when(instrumentRepository.findBySymbolIgnoreCase("AAPL")).thenReturn(Optional.of(existingAapl));

    List<InstrumentResponse> results = instrumentService.getInstruments("AA", null, null, null);

    assertThat(results).extracting(InstrumentResponse::symbol).containsExactlyInAnyOrder("AAPL", "AAOI");
  }

  @Test
  void getInstruments_whenInvalidPagination_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> instrumentService.getInstruments("AA", null, -1, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> instrumentService.getInstruments("AA", null, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> instrumentService.getInstruments("AA", null, 0, 101))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
