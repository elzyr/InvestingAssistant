package pl.investassist.common.util;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

public final class PaginationUtils {

  public static final int DEFAULT_PAGE_SIZE = 20;
  public static final int MAX_PAGE_SIZE = 100;

  private PaginationUtils() {}

  public static Pageable toPageable(Integer page, Integer size) {
    if (page == null && size == null) {
      return Pageable.unpaged();
    }
    int resolvedPage = page != null ? page : 0;
    int resolvedSize = size != null ? size : DEFAULT_PAGE_SIZE;
    if (resolvedPage < 0) {
      throw new IllegalArgumentException("Page index must not be less than 0");
    }
    if (resolvedSize < 1 || resolvedSize > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("Page size must be between 1 and " + MAX_PAGE_SIZE);
    }
    return PageRequest.of(resolvedPage, resolvedSize);
  }
}
