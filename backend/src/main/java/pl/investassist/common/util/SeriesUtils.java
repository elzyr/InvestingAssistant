package pl.investassist.common.util;

public final class SeriesUtils {

  private SeriesUtils() {}

  public static String normalize(String seriesName) {
    return (seriesName != null && !seriesName.trim().isBlank()) ? seriesName.trim() : null;
  }
}
