package pl.investassist.instrument.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.instrument.dto.CreateInstrumentRequest;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.repository.InstrumentRepository;
import pl.investassist.portfolio.client.MarketDataClient;

@Service
public class InstrumentService {

  private final InstrumentRepository instrumentRepository;
  private final MarketDataClient marketDataClient;

  public InstrumentService(
      InstrumentRepository instrumentRepository,
      MarketDataClient marketDataClient) {
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

  public List<InstrumentResponse> getInstruments(String search, InstrumentType type) {
    return getInstruments(search, type, null, null);
  }

  public List<InstrumentResponse> getInstruments(String search, InstrumentType type, Integer page, Integer size) {
    String searchQuery = (search != null) ? search : "";
    if ((search == null || search.trim().length() < 2) && page != null && size != null && size > 0) {
      try {
        org.springframework.data.domain.Page<Instrument> paged =
            instrumentRepository.searchInstruments(type, searchQuery, org.springframework.data.domain.PageRequest.of(page, size));
        if (paged != null) {
          return paged.getContent().stream().map(InstrumentResponse::from).toList();
        }
      } catch (Exception ignored) {
      }
    }

    List<Instrument> dbInstruments = new ArrayList<>(
        instrumentRepository.searchInstruments(type, searchQuery)
    );
    Set<String> existingSymbols = new HashSet<>();
    for (Instrument inst : dbInstruments) {
      if (inst.getSymbol() != null) {
        existingSymbols.add(inst.getSymbol().toUpperCase(Locale.ROOT));
      }
    }

    if (search != null && search.trim().length() >= 2) {
      try {
        List<InstrumentResponse> externalList = marketDataClient.searchInstruments(search.trim());
        List<Instrument> savedNew = saveDiscoveredInstruments(externalList, type, searchQuery, existingSymbols);
        dbInstruments.addAll(savedNew);
      } catch (Exception ignored) {
      }
    }

    List<InstrumentResponse> all = dbInstruments.stream()
        .map(InstrumentResponse::from)
        .toList();

    if (page != null && size != null && size > 0) {
      int fromIndex = page * size;
      if (fromIndex >= all.size()) {
        return List.of();
      }
      return all.subList(fromIndex, Math.min(fromIndex + size, all.size()));
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

    List<Instrument> existingInDb = null;
    try {
      existingInDb = instrumentRepository.findBySymbolInIgnoreCase(symbols);
    } catch (Exception ignored) {
    }

    Map<String, Instrument> existingMap = new HashMap<>();
    if (existingInDb != null) {
      for (Instrument inst : existingInDb) {
        if (inst.getSymbol() != null) {
          existingMap.put(inst.getSymbol().toUpperCase(Locale.ROOT), inst);
        }
      }
    }

    List<Instrument> newInstruments = new ArrayList<>();
    for (InstrumentResponse ext : externalList) {
      if (ext.symbol() == null || ext.symbol().isBlank()) {
        continue;
      }
      String symUpper = ext.symbol().trim().toUpperCase(Locale.ROOT);
      Instrument existing = existingMap.get(symUpper);
      if (existing == null && existingMap.isEmpty()) {
        Optional<Instrument> fallbackOpt = instrumentRepository.findBySymbolIgnoreCase(ext.symbol());
        if (fallbackOpt.isPresent()) {
          existing = fallbackOpt.get();
        }
      }

      if (existing != null) {
        if (matchesFilter(existing, type, searchQuery) && existingSymbols.add(symUpper)) {
          added.add(existing);
        }
      } else {
        Instrument instrument = new Instrument();
        instrument.setSymbol(symUpper);
        instrument.setName(ext.name() != null && !ext.name().isBlank() ? ext.name() : ext.symbol());
        instrument.setType(ext.type() != null ? ext.type() : InstrumentType.STOCK);
        instrument.setCurrency(ext.currency() != null ? ext.currency() : Currency.USD);
        newInstruments.add(instrument);
      }
    }

    if (newInstruments.size() == 1) {
      try {
        Instrument saved = instrumentRepository.save(newInstruments.get(0));
        if (saved != null && matchesFilter(saved, type, searchQuery) && existingSymbols.add(saved.getSymbol().toUpperCase(Locale.ROOT))) {
          added.add(saved);
        }
      } catch (Exception ignored) {
      }
    } else if (newInstruments.size() > 1) {
      try {
        List<Instrument> savedAll = instrumentRepository.saveAll(newInstruments);
        if (savedAll != null) {
          for (Instrument s : savedAll) {
            if (matchesFilter(s, type, searchQuery) && existingSymbols.add(s.getSymbol().toUpperCase(Locale.ROOT))) {
              added.add(s);
            }
          }
        }
      } catch (Exception ignored) {
      }
    }

    return added;
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
