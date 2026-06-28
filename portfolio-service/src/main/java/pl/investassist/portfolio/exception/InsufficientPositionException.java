package pl.investassist.portfolio.exception;

public class InsufficientPositionException extends RuntimeException {

  public InsufficientPositionException(String message) {
    super(message);
  }
}
