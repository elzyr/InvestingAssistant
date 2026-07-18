package pl.investassist.instrument.service;

import java.util.List;
import pl.investassist.instrument.dto.request.CreateInstrumentRequest;
import pl.investassist.instrument.dto.response.InstrumentResponse;
import pl.investassist.instrument.enums.InstrumentType;

public interface InstrumentService {
  InstrumentResponse createInstrument(CreateInstrumentRequest request);

  InstrumentResponse getInstrument(String symbol);

  List<InstrumentResponse> getInstruments(String search, InstrumentType type);
}
