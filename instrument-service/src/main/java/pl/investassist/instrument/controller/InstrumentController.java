package pl.investassist.instrument.controller;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.investassist.instrument.dto.request.CreateInstrumentRequest;
import pl.investassist.instrument.dto.response.InstrumentResponse;
import pl.investassist.instrument.enums.InstrumentType;
import pl.investassist.instrument.service.InstrumentService;

import java.util.List;

@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {
  private final InstrumentService instrumentService;

  public InstrumentController(InstrumentService instrumentService) {
    this.instrumentService = instrumentService;
  }

  @PostMapping
  public ResponseEntity<InstrumentResponse> createInstrument(
      @Valid @RequestBody CreateInstrumentRequest request) {
    InstrumentResponse response = instrumentService.createInstrument(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping("/symbol/{symbol}")
  public ResponseEntity<InstrumentResponse> getInstrumentBySymbol(@PathVariable String symbol) {
    InstrumentResponse response = instrumentService.getInstrument(symbol);
    return ResponseEntity.ok(response);
  }

  @GetMapping
  public ResponseEntity<List<InstrumentResponse>> getInstruments(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) InstrumentType type) {
    
    List<InstrumentResponse> responses = instrumentService.getInstruments(search, type);
    return ResponseEntity.ok(responses);
  }
}
