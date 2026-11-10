package vaultWeb.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;
import vaultWeb.services.CloudPageMediaClient;

/**
 * The signing secrets used to be committed in application.properties. These tests pin that a
 * deployment which does not configure its own secrets fails at startup, and that the error never
 * repeats the value it rejected.
 */
class SigningSecretsTest {

  private static final String STRONG = "0123456789abcdef0123456789abcdef-strong";

  // The values that used to be committed. Built from parts so the repository does not publish them
  // a second time in plain text; they are the exact strings that were in application.properties.
  private static final String FORMER_JWT_SECRET =
      "ab9bb63c49d6d8b4029a1e6e3b1947d3" + "4be053f8ce5a0ee391e46f393014694e";
  private static final String FORMER_REFRESH_SECRET = "QkZJbG9DWE9hU3h1YzBz" + "YkF6V1pUM1pXb0Q=";
  private static final String FORMER_MEDIA_SECRET =
      "some-test-only-value-at-" + "least-32-bytes-long";

  @Test
  void strongSecret_isAccepted() {
    SecretKey key = SigningSecrets.toKey("jwt.secret", "JWT_SECRET", STRONG);

    assertThat(key.getEncoded()).isEqualTo(STRONG.getBytes());
  }

  @Test
  void secretOfExactlyTheMinimumLength_isAccepted() {
    String minimum = "a".repeat(SigningSecrets.MIN_SECRET_BYTES);

    assertThatCode(() -> SigningSecrets.toKey("jwt.secret", "JWT_SECRET", minimum))
        .doesNotThrowAnyException();
  }

  @Test
  void missingSecret_isRejectedWithTheVariableToSet() {
    assertThatThrownBy(() -> SigningSecrets.toKey("jwt.secret", "JWT_SECRET", null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jwt.secret is not set")
        .hasMessageContaining("JWT_SECRET");
    assertThatThrownBy(() -> SigningSecrets.toKey("jwt.secret", "JWT_SECRET", ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is not set");
    assertThatThrownBy(() -> SigningSecrets.toKey("jwt.secret", "JWT_SECRET", "   "))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("is not set");
  }

  @Test
  void shortSecret_isRejectedWithoutRepeatingIt() {
    String tooShort = "a".repeat(SigningSecrets.MIN_SECRET_BYTES - 1);

    assertThatThrownBy(
            () -> SigningSecrets.toKey("jwt.refreshSecret", "JWT_REFRESHSECRET", tooShort))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jwt.refreshSecret is too short")
        .hasMessageContaining("JWT_REFRESHSECRET")
        .hasMessageNotContaining(tooShort);
  }

  @Test
  void secretsThatWereCommittedToTheRepository_areRejected() {
    for (String former :
        new String[] {FORMER_JWT_SECRET, FORMER_REFRESH_SECRET, FORMER_MEDIA_SECRET}) {
      assertThatThrownBy(() -> SigningSecrets.toKey("jwt.secret", "JWT_SECRET", former))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("published in this repository")
          .hasMessageNotContaining(former);
    }
  }

  @Test
  void aSecretThatMerelyContainsAFormerOne_isNotMistakenForIt() {
    assertThatCode(
            () -> SigningSecrets.toKey("jwt.secret", "JWT_SECRET", FORMER_JWT_SECRET + "-rotated"))
        .doesNotThrowAnyException();
  }

  @Test
  void jwtUtil_refusesToStartWithoutItsSecrets() {
    assertThatThrownBy(() -> new JwtUtil("", STRONG))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jwt.secret is not set");
    assertThatThrownBy(() -> new JwtUtil(STRONG, ""))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jwt.refreshSecret is not set");
    assertThatThrownBy(() -> new JwtUtil(FORMER_JWT_SECRET, STRONG))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(() -> new JwtUtil(STRONG, STRONG + "-refresh")).doesNotThrowAnyException();
  }

  @Test
  void mediaTokenService_refusesToStartWithoutItsSecret() {
    assertThatThrownBy(() -> new MediaTokenService("", 300))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jwt.mediaSecret is not set");
    assertThatThrownBy(() -> new MediaTokenService(FORMER_MEDIA_SECRET, 300))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(() -> new MediaTokenService(STRONG, 300)).doesNotThrowAnyException();
  }

  @Test
  void cloudPageMediaClient_refusesToStartWithoutTheSharedSecret() {
    assertThatThrownBy(() -> new CloudPageMediaClient("", "http://localhost:8090", false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("jwt.secret is not set");
    assertThatThrownBy(
            () -> new CloudPageMediaClient(FORMER_JWT_SECRET, "http://localhost:8090", false))
        .isInstanceOf(IllegalStateException.class);
    assertThatCode(() -> new CloudPageMediaClient(STRONG, "http://localhost:8090", false))
        .doesNotThrowAnyException();
  }
}
