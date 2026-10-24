package pl.investassist.instrument.controller;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.common.exception.AccessDeniedException;
import pl.investassist.instrument.dto.CreateInstrumentRequest;
import pl.investassist.instrument.dto.InstrumentResponse;
import pl.investassist.instrument.service.InstrumentService;

@RestController
@RequestMapping("/api/instruments")
public class InstrumentController {
  private final InstrumentService instrumentService;

  public InstrumentController(InstrumentService instrumentService) {
    this.instrumentService = instrumentService;
  }

  @PostMapping
  public ResponseEntity<InstrumentResponse> createInstrument(
      @RequestHeader(value = "X-User-Role", required = false) String role,
      @Valid @RequestBody CreateInstrumentRequest request) {
    if (!"ADMIN".equalsIgnoreCase(role)) {
      throw new AccessDeniedException("Only ADMIN users can create instruments");
    }
    InstrumentResponse response = instrumentService.createInstrument(request);
    return ResponseEntity.status(HttpStatus.CREATED).body(response);
  }

  @GetMapping
  public ResponseEntity<List<InstrumentResponse>> getInstruments(
      @RequestParam(required = false) String search,
      @RequestParam(required = false) InstrumentType type) {
    List<InstrumentResponse> responses = instrumentService.getInstruments(search, type);
    return ResponseEntity.ok(responses);
  }

  @GetMapping("/{id}")
  public ResponseEntity<InstrumentResponse> getInstrumentById(@PathVariable UUID id) {
    InstrumentResponse response = instrumentService.getInstrument(id);
    return ResponseEntity.ok(response);
  }
}
