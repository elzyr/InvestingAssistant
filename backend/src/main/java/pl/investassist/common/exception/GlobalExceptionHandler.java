package pl.investassist.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
    return ResponseEntity.status(HttpStatus.NOT_FOUND)
        .body(new ErrorResponse(ex.getMessage(), Instant.now().toEpochMilli()));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    return ResponseEntity.status(HttpStatus.FORBIDDEN)
        .body(new ErrorResponse(ex.getMessage(), Instant.now().toEpochMilli()));
  }

  @ExceptionHandler(InsufficientPositionException.class)
  public ResponseEntity<ErrorResponse> handleInsufficientPosition(InsufficientPositionException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse(ex.getMessage(), Instant.now().toEpochMilli()));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .body(new ErrorResponse(ex.getMessage(), Instant.now().toEpochMilli()));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
    return ResponseEntity.status(HttpStatus.CONFLICT)
        .body(new ErrorResponse("Database integrity constraint violation", Instant.now().toEpochMilli()));
  }

  @ExceptionHandler(MarketDataUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleMarketDataUnavailable(MarketDataUnavailableException ex) {
    log.error("Market data service error:", ex);
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .body(new ErrorResponse(ex.getMessage(), Instant.now().toEpochMilli()));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
    String message = ex.getReason() != null ? ex.getReason() : ex.getMessage();
    return ResponseEntity.status(ex.getStatusCode())
        .body(new ErrorResponse(message, Instant.now().toEpochMilli()));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    Map<String, String> fieldErrors = new HashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      fieldErrors.put(error.getField(), error.getDefaultMessage());
    }
    String message = "Validation failed: " + fieldErrors;
    ErrorResponse body = new ErrorResponse(message, Instant.now().toEpochMilli(), fieldErrors);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String message = "Malformed JSON request";
    ErrorResponse body = new ErrorResponse(message, Instant.now().toEpochMilli());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
    log.error("Unhandled exception:", ex);
    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .body(new ErrorResponse("An unexpected error occurred", Instant.now().toEpochMilli()));
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ErrorResponse(String message, long timestamp, Map<String, String> validationErrors) {
    public ErrorResponse(String message, long timestamp) {
      this(message, timestamp, null);
    }
  }
}
