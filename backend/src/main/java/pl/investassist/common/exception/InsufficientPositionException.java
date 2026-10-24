package pl.investassist.common.exception;

public class InsufficientPositionException extends RuntimeException {

  public InsufficientPositionException(String message) {
    super(message);
  }
}
