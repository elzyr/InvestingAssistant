package pl.investassist.instrument.service.impl;

import java.util.List;
import org.springframework.stereotype.Service;
import pl.investassist.instrument.dto.request.CreateInstrumentRequest;
import pl.investassist.instrument.dto.response.InstrumentResponse;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.instrument.enums.InstrumentType;
import pl.investassist.instrument.exception.ResourceNotFoundException;
import pl.investassist.instrument.mapper.InstrumentMapper;
import pl.investassist.instrument.repository.InstrumentRepository;
import pl.investassist.instrument.service.InstrumentService;

@Service
public class InstrumentServiceImpl implements InstrumentService {
  private final InstrumentRepository instrumentRepository;

  public InstrumentServiceImpl(InstrumentRepository instrumentRepository) {
    this.instrumentRepository = instrumentRepository;
  }

  @Override
  public InstrumentResponse createInstrument(CreateInstrumentRequest request) {
    Instrument instrument = InstrumentMapper.toEntity(request);
    instrumentRepository.save(instrument);
    return InstrumentMapper.toResponse(instrument);
  }

  @Override
  public InstrumentResponse getInstrument(String symbol) {
    return InstrumentMapper.toResponse(
        instrumentRepository
            .findBySymbolIgnoreCase(symbol)
            .orElseThrow(
                () ->
                    new ResourceNotFoundException("Instrument not found with symbol: " + symbol)));
  }

  @Override
  public List<InstrumentResponse> getInstruments(String search, InstrumentType type) {
    String searchQuery = (search != null) ? search : "";
    return instrumentRepository.searchInstruments(type, searchQuery).stream()
        .map(InstrumentMapper::toResponse)
        .toList();
  }
}
