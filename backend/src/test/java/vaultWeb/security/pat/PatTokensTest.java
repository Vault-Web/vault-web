package vaultWeb.security.pat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PatTokensTest {

  private final SecureRandom random = new SecureRandom();

  @Test
  void generatedToken_hasExpectedShape_andRoundTrips() {
    String publicId = PatTokens.newPublicId(random);
    String secret = PatTokens.newSecret(random);
    String token = PatTokens.format(publicId, secret);

    assertEquals(PatTokens.TOKEN_LENGTH, token.length());
    assertTrue(token.startsWith("vw_pat_"));
    assertEquals(publicId, PatTokens.parsePublicId(token).orElseThrow());
  }

  @Test
  void secret_carries256BitsOfEntropy() {
    String secret = PatTokens.newSecret(random);
    assertEquals(32, Base64.getUrlDecoder().decode(secret).length);
  }

  @Test
  void generatedValues_areUnique() {
    Set<String> seen = new HashSet<>();
    for (int i = 0; i < 1_000; i++) {
      assertTrue(seen.add(PatTokens.newSecret(random)));
    }
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(
      strings = {
        "vw_pat_",
        "vw_pat_short_secret",
        "Bearer vw_pat_x",
        "eyJhbGciOiJIUzI1NiJ9.e30.sig",
        "vw_pat_AAAAAAAAAAA!_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"
      })
  void parsePublicId_rejectsMalformed(String token) {
    assertTrue(PatTokens.parsePublicId(token).isEmpty());
  }

  @Test
  void parsePublicId_rejectsWrongSeparatorAndLength() {
    String ok = "vw_pat_" + "A".repeat(12) + "_" + "A".repeat(43);
    assertTrue(PatTokens.parsePublicId(ok).isPresent());
    assertTrue(PatTokens.parsePublicId(ok + "A").isEmpty());
    assertTrue(PatTokens.parsePublicId(ok.replace("A_A", "A-A")).isEmpty());
    assertTrue(PatTokens.parsePublicId(ok.replace("A_A", "A_+")).isEmpty());
  }

  @Test
  void isBearerPat_onlyMatchesExactBearerPrefix() {
    assertTrue(PatTokens.isBearerPat("Bearer vw_pat_abc"));
    assertFalse(PatTokens.isBearerPat("bearer vw_pat_abc"));
    assertFalse(PatTokens.isBearerPat("Bearer eyJ..."));
    assertFalse(PatTokens.isBearerPat(null));
  }
}
