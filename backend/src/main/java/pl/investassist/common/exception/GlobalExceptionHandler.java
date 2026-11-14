package pl.investassist.common.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.MessageSource;
import org.springframework.context.NoSuchMessageException;
import org.springframework.context.i18n.LocaleContextHolder;
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

  private final MessageSource messageSource;

  public GlobalExceptionHandler(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  private String resolveMessage(String messageOrKey, String defaultCode) {
    Locale locale = LocaleContextHolder.getLocale();
    if (messageOrKey != null && !messageOrKey.isBlank()) {
      try {
        return messageSource.getMessage(messageOrKey, null, locale);
      } catch (NoSuchMessageException ignored) {
        if (!messageOrKey.startsWith("error.")) {
          return messageOrKey;
        }
      }
    }
    if (defaultCode != null) {
      try {
        return messageSource.getMessage(defaultCode, null, locale);
      } catch (NoSuchMessageException ignored) {
      }
    }
    return messageOrKey != null ? messageOrKey : "";
  }

  private ResponseEntity<ErrorResponse> buildError(HttpStatusCode status, String message) {
    return ResponseEntity.status(status).body(new ErrorResponse(message, Instant.now().toEpochMilli()));
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  public ResponseEntity<ErrorResponse> handleResourceNotFound(ResourceNotFoundException ex) {
    return buildError(HttpStatus.NOT_FOUND, resolveMessage(ex.getMessage(), "error.resource_not_found"));
  }

  @ExceptionHandler(AccessDeniedException.class)
  public ResponseEntity<ErrorResponse> handleAccessDenied(AccessDeniedException ex) {
    return buildError(HttpStatus.FORBIDDEN, resolveMessage(ex.getMessage(), "error.access_denied"));
  }

  @ExceptionHandler(InsufficientPositionException.class)
  public ResponseEntity<ErrorResponse> handleInsufficientPosition(InsufficientPositionException ex) {
    return buildError(HttpStatus.BAD_REQUEST, resolveMessage(ex.getMessage(), "error.insufficient_position"));
  }

  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
    return buildError(HttpStatus.BAD_REQUEST, resolveMessage(ex.getMessage(), "error.bad_request"));
  }

  @ExceptionHandler(ConstraintViolationException.class)
  public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
    return buildError(HttpStatus.BAD_REQUEST, resolveMessage(ex.getMessage(), "error.bad_request"));
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  public ResponseEntity<ErrorResponse> handleDataIntegrityViolation(DataIntegrityViolationException ex) {
    return buildError(HttpStatus.CONFLICT, resolveMessage(null, "error.database_integrity"));
  }

  @ExceptionHandler(MarketDataUnavailableException.class)
  public ResponseEntity<ErrorResponse> handleMarketDataUnavailable(MarketDataUnavailableException ex) {
    log.error("Market data service error:", ex);
    return buildError(HttpStatus.SERVICE_UNAVAILABLE, resolveMessage(ex.getMessage(), "error.market_data_unavailable"));
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
    String raw = ex.getReason() != null ? ex.getReason() : ex.getMessage();
    return buildError(ex.getStatusCode(), resolveMessage(raw, null));
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    Map<String, String> fieldErrors = new LinkedHashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage());
    }
    Locale locale = LocaleContextHolder.getLocale();
    String message;
    try {
      message = messageSource.getMessage("error.validation_failed", new Object[] {fieldErrors}, locale);
    } catch (NoSuchMessageException ignored) {
      message = "Validation failed: " + fieldErrors;
    }
    ErrorResponse body = new ErrorResponse(message, Instant.now().toEpochMilli(), fieldErrors);
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @Override
  protected ResponseEntity<Object> handleHttpMessageNotReadable(
      HttpMessageNotReadableException ex,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    String message = resolveMessage("error.malformed_json", "error.bad_request");
    ErrorResponse body = new ErrorResponse(message, Instant.now().toEpochMilli());
    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ErrorResponse> handleGeneralException(Exception ex) {
    log.error("Unhandled exception:", ex);
    return buildError(HttpStatus.INTERNAL_SERVER_ERROR, resolveMessage(null, "error.unexpected"));
  }

  @JsonInclude(JsonInclude.Include.NON_NULL)
  public record ErrorResponse(String message, long timestamp, Map<String, String> validationErrors) {
    public ErrorResponse(String message, long timestamp) {
      this(message, timestamp, null);
    }
  }
}
