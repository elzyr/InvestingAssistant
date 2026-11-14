package pl.investassist.common.util;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class AsyncFutureUtils {

  private AsyncFutureUtils() {}

  public static <T> T joinUnwrapping(CompletableFuture<T> future) {
    try {
      return future.join();
    } catch (CompletionException ex) {
      if (ex.getCause() instanceof RuntimeException runtimeEx) {
        throw runtimeEx;
      }
      throw ex;
    }
  }
}
