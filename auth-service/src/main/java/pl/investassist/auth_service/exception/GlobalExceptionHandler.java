package pl.investassist.auth_service.exception;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(UserAlreadyExistsException.class)
  public ResponseEntity<Map<String, Object>> handleUserAlreadyExists(
      UserAlreadyExistsException ex) {
    return buildResponse(HttpStatus.CONFLICT, ex.getMessage());
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  public ResponseEntity<Map<String, Object>> handleInvalidCredentials(
      InvalidCredentialsException ex) {
    return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
  }

  @ExceptionHandler(InvalidTokenException.class)
  public ResponseEntity<Map<String, Object>> handleInvalidToken(InvalidTokenException ex) {
    return buildResponse(HttpStatus.UNAUTHORIZED, ex.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ResponseEntity<Map<String, Object>> handleValidationExceptions(
      MethodArgumentNotValidException ex) {
    Map<String, String> fieldErrors = new HashMap<>();
    for (FieldError error : ex.getBindingResult().getFieldErrors()) {
      fieldErrors.put(error.getField(), error.getDefaultMessage());
    }

    Map<String, Object> body = new HashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", HttpStatus.BAD_REQUEST.value());
    body.put("error", "Validation Failed");
    body.put("validationErrors", fieldErrors);

    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
  }

  private ResponseEntity<Map<String, Object>> buildResponse(HttpStatus status, String message) {
    Map<String, Object> body = new HashMap<>();
    body.put("timestamp", Instant.now().toString());
    body.put("status", status.value());
    body.put("error", status.getReasonPhrase());
    body.put("message", message);
    return ResponseEntity.status(status).body(body);
  }
}
