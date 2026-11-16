package vaultWeb.security;

import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import javax.crypto.SecretKey;

/**
 * Turns a configured signing secret into a key, refusing to start with one that is missing, too
 * short or known to be public.
 *
 * <p>The signing secrets used to be committed in {@code application.properties}, so anyone who
 * could read the repository could mint a valid token for any user. They now come from the
 * environment, and this class makes a deployment that forgot to set them (or kept the old values)
 * fail at startup with a message that says what to do, instead of running with a key that everybody
 * knows.
 *
 * <p>The exception message names the property and the environment variable, never the value.
 */
public final class SigningSecrets {

  /** HS256 needs a key of at least 256 bits. */
  static final int MIN_SECRET_BYTES = 32;

  /**
   * SHA-256 of every signing secret that was ever committed to this repository. The hashes, not the
   * values, are kept here so the values are not published a second time; a deployment still
   * configured with one of them is refused.
   */
  private static final Set<String> PUBLISHED_SECRET_HASHES =
      Set.of(
          "abc9f07a42d4bdcf0caa7b011a8ac5eedcb25c799c0997d7f43b66e2399094e9",
          "81f73c47f803e5265bd44ecac66df33765c0951e26b2f89124edb3d1a4cae075",
          "6759e6e780e91644c90b8b290fccc7df2c232e0c71e7bd8611599ef8d1e71126");

  private SigningSecrets() {
    throw new IllegalStateException("Utility class");
  }

  /**
   * @param property the configuration property the value came from, for example {@code jwt.secret}
   * @param environmentVariable the environment variable that sets it, for example {@code
   *     JWT_SECRET}
   * @param secret the configured value
   * @return an HMAC key derived from the secret's bytes
   * @throws IllegalStateException if the secret is blank, shorter than 32 bytes, or one of the
   *     values that used to be committed to the repository
   */
  public static SecretKey toKey(String property, String environmentVariable, String secret) {
    String how =
        " Set the "
            + environmentVariable
            + " environment variable to a random value of at least "
            + MIN_SECRET_BYTES
            + " bytes, for example the output of `openssl rand -hex 32`.";
    if (secret == null || secret.isBlank()) {
      throw new IllegalStateException(property + " is not set." + how);
    }
    byte[] bytes = secret.getBytes(StandardCharsets.UTF_8);
    if (bytes.length < MIN_SECRET_BYTES) {
      throw new IllegalStateException(property + " is too short." + how);
    }
    if (PUBLISHED_SECRET_HASHES.contains(sha256Hex(bytes))) {
      throw new IllegalStateException(
          property
              + " is a value that was published in this repository, so anyone can sign tokens"
              + " with it."
              + how);
    }
    return Keys.hmacShaKeyFor(bytes);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 is not available", e);
    }
  }
}
