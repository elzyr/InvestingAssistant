package pl.investassist.instrument.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import pl.investassist.instrument.enums.Currency;
import pl.investassist.instrument.enums.InstrumentType;

public record CreateInstrumentRequest(
    @NotBlank String symbol, @NotBlank String name, @NotNull InstrumentType type, @NotNull Currency currency) {}
