package pl.investassist.instrument.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.common.util.PaginationUtils;
import pl.investassist.instrument.dto.CreateInstrumentRequest;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.repository.InstrumentRepository;
import pl.investassist.portfolio.port.MarketDataPort;

@Service
public class InstrumentService {

  private static final Logger log = LoggerFactory.getLogger(InstrumentService.class);

  private final InstrumentRepository instrumentRepository;
  private final MarketDataPort marketDataClient;
  private final TransactionTemplate transactionTemplate;

  public InstrumentService(
      InstrumentRepository instrumentRepository,
      MarketDataPort marketDataClient) {
    this(instrumentRepository, marketDataClient, null);
  }

  @Autowired
  public InstrumentService(
      InstrumentRepository instrumentRepository,
      MarketDataPort marketDataClient,
      PlatformTransactionManager transactionManager) {
    this.instrumentRepository = instrumentRepository;
    this.marketDataClient = marketDataClient;
    this.transactionTemplate = transactionManager != null ? new TransactionTemplate(transactionManager) : null;
  }

  @Transactional
  public InstrumentResponse createInstrument(CreateInstrumentRequest request) {
    String symbol = request.symbol().trim().toUpperCase(Locale.ROOT);
    if (instrumentRepository.findBySymbolIgnoreCase(symbol).isPresent()) {
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "Instrument with symbol " + symbol + " already exists");
    }
    Instrument instrument = request.toEntity();
    instrument.setSymbol(symbol);
    instrumentRepository.save(instrument);
    return InstrumentResponse.from(instrument);
  }

  @Transactional(readOnly = true)
  public InstrumentResponse getInstrument(UUID uuid) {
    return InstrumentResponse.from(
        instrumentRepository
            .findById(uuid)
            .orElseThrow(
                () -> new ResourceNotFoundException("Instrument not found with UUID: " + uuid)));
  }

  @Transactional(readOnly = true)
  public List<InstrumentResponse> getInstruments(String search, InstrumentType type, Integer page, Integer size) {
    Pageable pageable = PaginationUtils.toBoundedPageable(page, size);
    String searchQuery = search != null ? search.trim() : "";
    Page<Instrument> paged = instrumentRepository.searchInstruments(type, searchQuery, pageable);
    return paged != null ? paged.getContent().stream().map(InstrumentResponse::from).toList() : List.of();
  }

  public List<InstrumentResponse> discoverInstruments(String search, InstrumentType type, Integer page, Integer size) {
    Pageable pageable = PaginationUtils.toBoundedPageable(page, size);
    String searchQuery = search != null ? search.trim() : "";

    if (searchQuery.length() < 2) {
      Page<Instrument> paged = instrumentRepository.searchInstruments(type, searchQuery, pageable);
      return paged != null ? paged.getContent().stream().map(InstrumentResponse::from).toList() : List.of();
    }

    Page<Instrument> dbPage = instrumentRepository.searchInstruments(type, searchQuery, Pageable.unpaged());
    List<Instrument> dbInstruments = dbPage != null ? new ArrayList<>(dbPage.getContent()) : new ArrayList<>();
    Set<String> existingSymbols = new HashSet<>();
    for (Instrument inst : dbInstruments) {
      if (inst.getSymbol() != null) {
        existingSymbols.add(inst.getSymbol().toUpperCase(Locale.ROOT));
      }
    }

    try {
      List<InstrumentResponse> externalList = marketDataClient.searchInstruments(searchQuery);
      List<Instrument> savedNew = saveDiscoveredInstruments(externalList, type, searchQuery, existingSymbols);
      dbInstruments.addAll(savedNew);
    } catch (Exception e) {
      log.warn("External instrument discovery failed for query='{}': {}", searchQuery, e.getMessage());
    }

    List<InstrumentResponse> all = dbInstruments.stream()
        .map(InstrumentResponse::from)
        .toList();

    if (pageable.isPaged()) {
      int fromIndex = (int) Math.min(pageable.getOffset(), all.size());
      int toIndex = Math.min(fromIndex + pageable.getPageSize(), all.size());
      return all.subList(fromIndex, toIndex);
    }

    return all;
  }

  public List<Instrument> saveDiscoveredInstruments(
      List<InstrumentResponse> externalList,
      InstrumentType type,
      String searchQuery,
      Set<String> existingSymbols) {
    List<Instrument> added = new ArrayList<>();
    if (externalList == null || externalList.isEmpty()) {
      return added;
    }

    Set<String> symbols = externalList.stream()
        .map(InstrumentResponse::symbol)
        .filter(s -> s != null && !s.isBlank())
        .map(s -> s.trim().toUpperCase(Locale.ROOT))
        .collect(Collectors.toSet());
    if (symbols.isEmpty()) {
      return added;
    }

    List<Instrument> existingInDb = instrumentRepository.findBySymbolInIgnoreCase(symbols);
    Map<String, Instrument> existingMap = new HashMap<>();
    if (existingInDb != null) {
      for (Instrument inst : existingInDb) {
        if (inst.getSymbol() != null) {
          existingMap.put(inst.getSymbol().toUpperCase(Locale.ROOT), inst);
        }
      }
    }

    List<InstrumentResponse> toUpsert = new ArrayList<>();
    Set<String> pendingSymbols = new LinkedHashSet<>();
    for (InstrumentResponse ext : externalList) {
      if (ext.symbol() == null || ext.symbol().isBlank()) {
        continue;
      }
      String symUpper = ext.symbol().trim().toUpperCase(Locale.ROOT);
      Instrument existing = existingMap.get(symUpper);

      if (existing != null) {
        addIfMatching(existing, type, searchQuery, existingSymbols, added);
      } else if (ext.currency() != null && pendingSymbols.add(symUpper)) {
        toUpsert.add(ext);
      }
    }

    if (toUpsert.isEmpty()) {
      return added;
    }

    List<Instrument> fetched = transactionTemplate != null
        ? transactionTemplate.execute(status -> persistAndFetchDiscovered(toUpsert, pendingSymbols))
        : persistAndFetchDiscovered(toUpsert, pendingSymbols);
    if (fetched != null) {
      for (Instrument inst : fetched) {
        addIfMatching(inst, type, searchQuery, existingSymbols, added);
      }
    }

    return added;
  }

  private List<Instrument> persistAndFetchDiscovered(
      List<InstrumentResponse> toUpsert, Set<String> pendingSymbols) {
    for (InstrumentResponse ext : toUpsert) {
      String symUpper = ext.symbol().trim().toUpperCase(Locale.ROOT);
      String name = ext.name() != null && !ext.name().isBlank() ? ext.name() : ext.symbol();
      String instType = (ext.type() != null ? ext.type() : InstrumentType.STOCK).name();
      String assetCategory = ext.assetCategory() != null ? ext.assetCategory().name() : null;
      instrumentRepository.upsertIgnore(
          UUID.randomUUID(),
          symUpper,
          name,
          instType,
          ext.currency().name(),
          assetCategory);
    }
    return instrumentRepository.findBySymbolInIgnoreCase(pendingSymbols);
  }

  private void addIfMatching(
      Instrument inst,
      InstrumentType type,
      String searchQuery,
      Set<String> existingSymbols,
      List<Instrument> added) {
    if (inst.getSymbol() == null) {
      return;
    }
    String symUpper = inst.getSymbol().toUpperCase(Locale.ROOT);
    if (matchesFilter(inst, type, searchQuery) && existingSymbols.add(symUpper)) {
      added.add(inst);
    }
  }

  private boolean matchesFilter(Instrument inst, InstrumentType type, String query) {
    if (type != null && inst.getType() != type) {
      return false;
    }
    if (query == null || query.isBlank()) {
      return true;
    }
    String q = query.toLowerCase(Locale.ROOT);
    boolean matchSymbol = inst.getSymbol() != null && inst.getSymbol().toLowerCase(Locale.ROOT).contains(q);
    boolean matchName = inst.getName() != null && inst.getName().toLowerCase(Locale.ROOT).contains(q);
    return matchSymbol || matchName;
  }
}
