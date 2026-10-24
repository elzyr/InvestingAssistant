package pl.investassist.instrument.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
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

  @Transactional
  public List<InstrumentResponse> getInstruments(String search, InstrumentType type) {
    String searchQuery = (search != null) ? search : "";
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
        for (InstrumentResponse ext : externalList) {
          if (ext.symbol() == null || ext.symbol().isBlank()) {
            continue;
          }
          String symUpper = ext.symbol().trim().toUpperCase(Locale.ROOT);
          Optional<Instrument> existingOpt = instrumentRepository.findBySymbolIgnoreCase(ext.symbol());
          if (existingOpt.isPresent()) {
            Instrument existing = existingOpt.get();
            if (matchesFilter(existing, type, searchQuery) && existingSymbols.add(symUpper)) {
              dbInstruments.add(existing);
            }
          } else {
            try {
              Instrument instrument = new Instrument();
              instrument.setSymbol(symUpper);
              instrument.setName(ext.name() != null && !ext.name().isBlank() ? ext.name() : ext.symbol());
              instrument.setType(ext.type() != null ? ext.type() : InstrumentType.STOCK);
              instrument.setCurrency(ext.currency() != null ? ext.currency() : Currency.USD);
              Instrument saved = instrumentRepository.save(instrument);
              if (matchesFilter(saved, type, searchQuery) && existingSymbols.add(symUpper)) {
                dbInstruments.add(saved);
              }
            } catch (Exception ignored) {
            }
          }
        }
      } catch (Exception ignored) {
      }
    }

    return dbInstruments.stream()
        .map(InstrumentResponse::from)
        .toList();
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
