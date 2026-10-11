package pl.investassist.instrument.service;

import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import pl.investassist.instrument.dto.CreateInstrumentRequest;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.enums.InstrumentType;
import pl.investassist.instrument.repository.InstrumentRepository;

@Service
public class InstrumentService {

  private final InstrumentRepository instrumentRepository;

  public InstrumentService(InstrumentRepository instrumentRepository) {
    this.instrumentRepository = instrumentRepository;
  }

  public InstrumentResponse createInstrument(CreateInstrumentRequest request) {
    Instrument instrument = request.toEntity();
    instrumentRepository.save(instrument);
    return InstrumentResponse.from(instrument);
  }

  public InstrumentResponse getInstrument(String symbol) {
    return InstrumentResponse.from(
        instrumentRepository
            .findBySymbolIgnoreCase(symbol)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Instrument not found with symbol: " + symbol)));
  }

  public InstrumentResponse getInstrument(UUID uuid) {
    return InstrumentResponse.from(
        instrumentRepository
            .findById(uuid)
            .orElseThrow(
                () ->
                    new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Instrument not found with UUID: " + uuid)));
  }

  public List<InstrumentResponse> getInstruments(String search, InstrumentType type) {
    String searchQuery = (search != null) ? search : "";
    return instrumentRepository.searchInstruments(type, searchQuery).stream()
        .map(InstrumentResponse::from)
        .toList();
  }
}
