package vaultWeb.security.pat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.mock.web.MockHttpServletRequest;
import vaultWeb.models.enums.PatScope;

class PatEndpointRegistryTest {

  private final PatEndpointRegistry registry = new PatEndpointRegistry();

  private static MockHttpServletRequest request(String method, String path) {
    MockHttpServletRequest request = new MockHttpServletRequest(method, path);
    request.setServletPath(path);
    return request;
  }

  @ParameterizedTest
  @CsvSource({
    "GET, /api/dashboard/me, PROFILE_READ",
    "GET, /api/auth/profile-picture, PROFILE_READ",
    "GET, /api/auth/security-activity, SECURITY_READ"
  })
  void registeredOperations_mapToScope(String method, String path, PatScope scope) {
    assertEquals(Optional.of(scope), registry.requiredScope(request(method, path)));
  }

  @ParameterizedTest
  @CsvSource({
    "POST, /api/dashboard/me",
    "GET, /api/dashboard/me/",
    "GET, /api/dashboard/alice",
    "POST, /api/auth/profile-picture",
    "POST, /api/auth/security-activity/log",
    "GET, /api/auth/tokens",
    "GET, /api/private-chats/devices",
    "GET, /api/groups/1/devices"
  })
  void unregisteredOperations_areDenied(String method, String path) {
    assertTrue(registry.requiredScope(request(method, path)).isEmpty());
  }

  @Test
  void everyScope_hasAtLeastOneEndpoint() {
    for (PatScope scope : PatScope.values()) {
      assertTrue(
          registry.rules().stream().anyMatch(r -> r.scope() == scope),
          "scope without endpoints: " + scope);
    }
  }
}
