package dev.rymarovych.event_analytics.domain;

/** Detects NUL and unpaired UTF-16 surrogates. */
public final class StorableCharacters {

  private StorableCharacters() {}

  /**
   * {@code codePoints()} combines valid pairs, leaving only unpaired halves typed as surrogates.
   */
  public static boolean isStorable(String text) {
    return text.codePoints()
        .noneMatch(
            codePoint -> codePoint == 0 || Character.getType(codePoint) == Character.SURROGATE);
  }
}
