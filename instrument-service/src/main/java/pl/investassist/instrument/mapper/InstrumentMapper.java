package pl.investassist.instrument.mapper;

import pl.investassist.instrument.dto.request.CreateInstrumentRequest;
import pl.investassist.instrument.dto.response.InstrumentResponse;
import pl.investassist.instrument.entity.Instrument;

public class InstrumentMapper {

    public static Instrument toEntity(CreateInstrumentRequest request) {
        if (request == null) {
            return null;
        }
        Instrument instrument = new Instrument();
        instrument.setSymbol(request.symbol());
        instrument.setName(request.name());
        instrument.setType(request.type());
        instrument.setCurrency(request.currency());
        return instrument;
    }

    public static InstrumentResponse toResponse(Instrument entity) {
        if (entity == null) {
            return null;
        }
        return new InstrumentResponse(
                entity.getId(),
                entity.getSymbol(),
                entity.getName(),
                entity.getType(),
                entity.getCurrency()
        );
    }
}
