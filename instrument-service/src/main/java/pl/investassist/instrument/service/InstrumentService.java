package pl.investassist.instrument.service;

import java.util.List;
import java.util.UUID;
import pl.investassist.instrument.dto.request.CreateInstrumentRequest;
import pl.investassist.instrument.dto.response.InstrumentResponse;
import pl.investassist.instrument.enums.InstrumentType;

public interface InstrumentService {
  InstrumentResponse createInstrument(CreateInstrumentRequest request);

  InstrumentResponse getInstrument(String symbol);

  InstrumentResponse getInstrument(UUID uuid);

  List<InstrumentResponse> getInstruments(String search, InstrumentType type);
}
