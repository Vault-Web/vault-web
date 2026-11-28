package vaultWeb.security.pat;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Format helpers for personal access tokens.
 *
 * <p>Format: {@code vw_pat_<publicId>_<secret>} where {@code publicId} is 12 base62 characters
 * (non-secret lookup id) and {@code secret} is 32 bytes from {@link SecureRandom}, base64url
 * encoded without padding (43 characters).
 */
public final class PatTokens {

  public static final String PREFIX = "vw_pat_";
  public static final int PUBLIC_ID_LENGTH = 12;
  public static final int SECRET_BYTES = 32;
  private static final int SECRET_LENGTH = 43;
  public static final int TOKEN_LENGTH = PREFIX.length() + PUBLIC_ID_LENGTH + 1 + SECRET_LENGTH;

  private static final char[] BASE62 =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
  private static final Pattern PUBLIC_ID = Pattern.compile("[0-9A-Za-z]{" + PUBLIC_ID_LENGTH + "}");
  private static final Pattern SECRET = Pattern.compile("[A-Za-z0-9_-]{" + SECRET_LENGTH + "}");

  private PatTokens() {}

  /** True if the value carries the PAT prefix; does not validate it. */
  public static boolean hasPrefix(String token) {
    return token != null && token.startsWith(PREFIX);
  }

  /** True if an {@code Authorization} header value is a Bearer PAT. */
  public static boolean isBearerPat(String authorizationHeader) {
    return authorizationHeader != null && authorizationHeader.startsWith("Bearer " + PREFIX);
  }

  public static String newPublicId(SecureRandom random) {
    char[] out = new char[PUBLIC_ID_LENGTH];
    for (int i = 0; i < out.length; i++) {
      out[i] = BASE62[random.nextInt(BASE62.length)];
    }
    return new String(out);
  }

  public static String newSecret(SecureRandom random) {
    byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  public static String format(String publicId, String secret) {
    return PREFIX + publicId + "_" + secret;
  }

  /**
   * Extracts the public id from a well-formed token. Returns empty for anything malformed. The
   * result is only a lookup key; callers must still verify the digest.
   */
  public static Optional<String> parsePublicId(String token) {
    if (token == null || token.length() != TOKEN_LENGTH || !hasPrefix(token)) {
      return Optional.empty();
    }
    int idStart = PREFIX.length();
    int sep = idStart + PUBLIC_ID_LENGTH;
    String publicId = token.substring(idStart, sep);
    if (token.charAt(sep) != '_'
        || !PUBLIC_ID.matcher(publicId).matches()
        || !SECRET.matcher(token.substring(sep + 1)).matches()) {
      return Optional.empty();
    }
    return Optional.of(publicId);
  }
}
