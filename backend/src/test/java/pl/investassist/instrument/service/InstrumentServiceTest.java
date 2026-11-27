package pl.investassist.instrument.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.persistence.TransactionRequiredException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
  void discoverInstruments_whenSearchHasAtLeastTwoCharsAndNotInDb_callsMarketDataClientAndSavesBatchWithoutNPlusOne() {
    Instrument persisted = new Instrument();
    persisted.setId(UUID.randomUUID());
    persisted.setSymbol("AMZN");
    persisted.setName("Amazon.com Inc.");
    persisted.setType(InstrumentType.STOCK);
    persisted.setCurrency(Currency.USD);

    when(instrumentRepository.searchInstruments(eq(null), eq("AMZN"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of()));
    when(marketDataClient.searchInstruments("AMZN"))
        .thenReturn(
            List.of(
                new InstrumentResponse(null, "AMZN", "Amazon.com Inc.", InstrumentType.STOCK, Currency.USD)));
    when(instrumentRepository.findBySymbolInIgnoreCase(Set.of("AMZN")))
        .thenReturn(List.of())
        .thenReturn(List.of(persisted));

    List<InstrumentResponse> results = instrumentService.discoverInstruments("AMZN", null, null, null);

    verify(marketDataClient).searchInstruments("AMZN");
    verify(instrumentRepository, never()).findBySymbolIgnoreCase(anyString());
    verify(instrumentRepository)
        .upsertIgnore(any(UUID.class), eq("AMZN"), eq("Amazon.com Inc."), eq("STOCK"), eq("USD"), eq(null));
    verify(instrumentRepository, never()).save(any());
    verify(instrumentRepository, never()).saveAll(any());
    assertThat(results).extracting(InstrumentResponse::symbol).containsExactly("AMZN");
  }

  @Test
  void getInstruments_neverCallsMarketDataClientEvenWhenQueryHasAtLeastTwoChars() {
    when(instrumentRepository.searchInstruments(eq(null), eq("AMZN"), eq(org.springframework.data.domain.PageRequest.of(0, 20))))
        .thenReturn(new PageImpl<>(List.of()));

    List<InstrumentResponse> results = instrumentService.getInstruments("AMZN", null, null, null);

    verify(marketDataClient, never()).searchInstruments(anyString());
    assertThat(results).isEmpty();
  }

  @Test
  void discoverInstruments_whenSearchLengthLessThanTwoChars_doesNotCallMarketDataClient() {
    when(instrumentRepository.searchInstruments(eq(null), eq("A"), eq(org.springframework.data.domain.PageRequest.of(0, 20))))
        .thenReturn(new PageImpl<>(List.of()));

    List<InstrumentResponse> results = instrumentService.discoverInstruments("A", null, null, null);

    verify(marketDataClient, never()).searchInstruments(anyString());
    assertThat(results).isEmpty();
  }

  @Test
  void discoverInstruments_whenExternalSearchReturnsExistingSymbol_doesNotSaveDuplicate() {
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

    List<InstrumentResponse> results = instrumentService.discoverInstruments("AMZN", null, null, null);

    verify(marketDataClient).searchInstruments("AMZN");
    verify(instrumentRepository, never()).saveAll(any());
    verify(instrumentRepository, never()).save(any(Instrument.class));
    assertThat(results).extracting(InstrumentResponse::symbol).containsExactly("AMZN");
  }

  @Test
  void discoverInstruments_whenMarketDataClientThrowsException_returnsExistingDbItemsWithoutFailing() {
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

    List<InstrumentResponse> results = instrumentService.discoverInstruments("AMZ", null, null, null);

    assertThat(results).hasSize(1);
    assertThat(results.get(0).symbol()).isEqualTo("AMZ_LOCAL");
  }

  @Test
  void discoverInstruments_whenExternalInstrumentsDiscovered_fetchesInBatchAndUpsertsAtomically() {
    when(instrumentRepository.searchInstruments(eq(null), eq("TECH"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of()));
    when(marketDataClient.searchInstruments("TECH"))
        .thenReturn(
            List.of(
                new InstrumentResponse(null, "AAPL", "Apple Inc.", InstrumentType.STOCK, Currency.USD),
                new InstrumentResponse(null, "MSFT", "Microsoft Corp.", InstrumentType.STOCK, Currency.USD)));
    when(instrumentRepository.findBySymbolInIgnoreCase(any())).thenReturn(List.of());

    instrumentService.discoverInstruments("TECH", null, null, null);

    verify(instrumentRepository, times(2)).findBySymbolInIgnoreCase(any());
    verify(instrumentRepository)
        .upsertIgnore(any(UUID.class), eq("AAPL"), eq("Apple Inc."), eq("STOCK"), eq("USD"), eq(null));
    verify(instrumentRepository)
        .upsertIgnore(any(UUID.class), eq("MSFT"), eq("Microsoft Corp."), eq("STOCK"), eq("USD"), eq(null));
    verify(instrumentRepository, never()).saveAll(any());
    verify(instrumentRepository, never()).save(any(Instrument.class));
  }

  @Test
  void discoverInstruments_whenConcurrentDuplicateOnBatchInsert_handlesAtomicUpsertIgnore() {
    Instrument existingAapl = new Instrument();
    existingAapl.setId(UUID.randomUUID());
    existingAapl.setSymbol("AAPL");
    existingAapl.setName("Apple Inc.");
    existingAapl.setType(InstrumentType.STOCK);
    existingAapl.setCurrency(Currency.USD);

    Instrument persistedAaoi = new Instrument();
    persistedAaoi.setId(UUID.randomUUID());
    persistedAaoi.setSymbol("AAOI");
    persistedAaoi.setName("Applied Optoelectronics");
    persistedAaoi.setType(InstrumentType.STOCK);
    persistedAaoi.setCurrency(Currency.USD);

    when(instrumentRepository.searchInstruments(eq(null), eq("AA"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of()));
    when(marketDataClient.searchInstruments("AA"))
        .thenReturn(
            List.of(
                new InstrumentResponse(null, "AAPL", "Apple Inc.", InstrumentType.STOCK, Currency.USD),
                new InstrumentResponse(null, "AAOI", "Applied Optoelectronics", InstrumentType.STOCK, Currency.USD)));
    when(instrumentRepository.findBySymbolInIgnoreCase(any()))
        .thenReturn(List.of())
        .thenReturn(List.of(existingAapl, persistedAaoi));

    List<InstrumentResponse> results = instrumentService.discoverInstruments("AA", null, null, null);

    verify(instrumentRepository)
        .upsertIgnore(any(UUID.class), eq("AAPL"), eq("Apple Inc."), eq("STOCK"), eq("USD"), eq(null));
    verify(instrumentRepository)
        .upsertIgnore(any(UUID.class), eq("AAOI"), eq("Applied Optoelectronics"), eq("STOCK"), eq("USD"), eq(null));
    verify(instrumentRepository, never()).saveAll(any());
    verify(instrumentRepository, never()).save(any());
    assertThat(results).extracting(InstrumentResponse::symbol).containsExactlyInAnyOrder("AAPL", "AAOI");
  }

  @Test
  void getInstruments_whenInvalidPagination_throwsIllegalArgumentException() {
    assertThatThrownBy(() -> instrumentService.getInstruments("AA", null, -1, 10))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> instrumentService.getInstruments("AA", null, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> instrumentService.getInstruments("AA", null, 0, 51))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void discoverInstruments_isNotAnnotatedTransactionalAcrossExternalHttpCall() throws NoSuchMethodException {
    var method = InstrumentService.class.getMethod(
        "discoverInstruments", String.class, InstrumentType.class, Integer.class, Integer.class);
    assertThat(method.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
  }

  @Test
  void discoverInstruments_persistsDiscoveredInstrumentsWithoutOuterServiceTransaction() {
    TrackingTransactionManager txManager = new TrackingTransactionManager();
    InstrumentRepository targetRepo = org.mockito.Mockito.mock(InstrumentRepository.class);
    List<Instrument> persistedStore = new ArrayList<>();

    when(targetRepo.searchInstruments(eq(null), eq("AMZN"), eq(Pageable.unpaged())))
        .thenReturn(new PageImpl<>(List.of()));
    when(targetRepo.findBySymbolInIgnoreCase(Set.of("AMZN")))
        .thenAnswer(inv -> List.copyOf(persistedStore));
    when(targetRepo.upsertIgnore(
        any(UUID.class), eq("AMZN"), eq("Amazon.com Inc."), eq("STOCK"), eq("USD"), eq(null)))
        .thenAnswer(inv -> {
          if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new TransactionRequiredException("Executing an update/delete query");
          }
          Instrument entity = new Instrument();
          entity.setId(inv.getArgument(0));
          entity.setSymbol(inv.getArgument(1));
          entity.setName(inv.getArgument(2));
          entity.setType(InstrumentType.valueOf(inv.getArgument(3)));
          entity.setCurrency(Currency.valueOf(inv.getArgument(4)));
          persistedStore.add(entity);
          return 1;
        });

    ProxyFactory proxyFactory = new ProxyFactory(targetRepo);
    proxyFactory.setInterfaces(InstrumentRepository.class);
    proxyFactory.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
    InstrumentRepository proxiedRepo = (InstrumentRepository) proxyFactory.getProxy();

    when(marketDataClient.searchInstruments("AMZN")).thenAnswer(inv -> {
      assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
      return List.of(new InstrumentResponse(null, "AMZN", "Amazon.com Inc.", InstrumentType.STOCK, Currency.USD));
    });

    InstrumentService serviceWithTxTemplate = new InstrumentService(proxiedRepo, marketDataClient, txManager);
    List<InstrumentResponse> withTemplateResults =
        serviceWithTxTemplate.discoverInstruments("AMZN", null, null, null);
    assertThat(withTemplateResults).extracting(InstrumentResponse::symbol).containsExactly("AMZN");
    assertThat(txManager.commitCount.get()).isEqualTo(1);

    persistedStore.clear();
    InstrumentService serviceWithoutTxTemplate = new InstrumentService(proxiedRepo, marketDataClient);
    List<InstrumentResponse> repoTxResults =
        serviceWithoutTxTemplate.discoverInstruments("AMZN", null, null, null);
    assertThat(repoTxResults).extracting(InstrumentResponse::symbol).containsExactly("AMZN");
    assertThat(txManager.commitCount.get()).isEqualTo(2);
  }

  private static final class TrackingTransactionManager extends AbstractPlatformTransactionManager {
    private final AtomicInteger commitCount = new AtomicInteger();

    @Override
    protected Object doGetTransaction() throws TransactionException {
      return new TxHandle(TransactionSynchronizationManager.isActualTransactionActive());
    }

    @Override
    protected boolean isExistingTransaction(Object transaction) throws TransactionException {
      return ((TxHandle) transaction).active;
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
      commitCount.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
    }

    private record TxHandle(boolean active) {}
  }
}
