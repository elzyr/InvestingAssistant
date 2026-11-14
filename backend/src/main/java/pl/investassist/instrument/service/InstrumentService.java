package pl.investassist.instrument.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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

  public InstrumentService(
      InstrumentRepository instrumentRepository,
      MarketDataPort marketDataClient) {
    this.instrumentRepository = instrumentRepository;
    this.marketDataClient = marketDataClient;
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

  @Transactional
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

    List<Instrument> newInstruments = new ArrayList<>();
    Set<String> pendingSymbols = new HashSet<>();
    for (InstrumentResponse ext : externalList) {
      if (ext.symbol() == null || ext.symbol().isBlank()) {
        continue;
      }
      String symUpper = ext.symbol().trim().toUpperCase(Locale.ROOT);
      Instrument existing = existingMap.get(symUpper);

      if (existing != null) {
        addIfMatching(existing, type, searchQuery, existingSymbols, added);
      } else if (ext.currency() != null && pendingSymbols.add(symUpper)) {
        Instrument instrument = new Instrument();
        instrument.setSymbol(symUpper);
        instrument.setName(ext.name() != null && !ext.name().isBlank() ? ext.name() : ext.symbol());
        instrument.setType(ext.type() != null ? ext.type() : InstrumentType.STOCK);
        instrument.setCurrency(ext.currency());
        instrument.setAssetCategory(ext.assetCategory());
        newInstruments.add(instrument);
      }
    }

    if (newInstruments.isEmpty()) {
      return added;
    }

    try {
      List<Instrument> savedAll = instrumentRepository.saveAll(newInstruments);
      if (savedAll != null) {
        for (Instrument saved : savedAll) {
          addIfMatching(saved, type, searchQuery, existingSymbols, added);
        }
      }
    } catch (DataIntegrityViolationException e) {
      log.debug("Batch instrument insert hit concurrent duplicate, falling back to per-symbol upsert: {}", e.getMessage());
      for (Instrument inst : newInstruments) {
        saveOrFetchSingle(inst, type, searchQuery, existingSymbols, added);
      }
    }

    return added;
  }

  private void saveOrFetchSingle(
      Instrument inst,
      InstrumentType type,
      String searchQuery,
      Set<String> existingSymbols,
      List<Instrument> added) {
    try {
      Instrument saved = instrumentRepository.save(inst);
      if (saved != null) {
        addIfMatching(saved, type, searchQuery, existingSymbols, added);
      }
    } catch (DataIntegrityViolationException ex) {
      instrumentRepository
          .findBySymbolIgnoreCase(inst.getSymbol())
          .ifPresent(existing -> addIfMatching(existing, type, searchQuery, existingSymbols, added));
    }
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
