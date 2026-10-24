package pl.investassist.instrument.service;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.ResourceNotFoundException;
import pl.investassist.instrument.dto.CreateInstrumentRequest;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.repository.InstrumentRepository;

@Service
public class InstrumentService {

  private final InstrumentRepository instrumentRepository;

  public InstrumentService(InstrumentRepository instrumentRepository) {
    this.instrumentRepository = instrumentRepository;
  }

  @Transactional
  public InstrumentResponse createInstrument(CreateInstrumentRequest request) {
    String symbol = request.symbol().trim().toUpperCase(java.util.Locale.ROOT);
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
  public List<InstrumentResponse> getInstruments(String search, InstrumentType type) {
    String searchQuery = (search != null) ? search : "";
    return instrumentRepository.searchInstruments(type, searchQuery).stream()
        .map(InstrumentResponse::from)
        .toList();
  }
}
