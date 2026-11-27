package vaultWeb.security.pat;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Authorization policy for requests that present a Bearer PAT: permitted only when the request
 * matches the {@link PatEndpointRegistry} and the token holds the required scope. Deny by default.
 *
 * <p>Wired into the filter chain as an {@code AuthorizationManager} lambda in {@code
 * SecurityConfig}, which keeps this class independent of the Spring Security major version.
 */
@Component
@RequiredArgsConstructor
public class PatAccessPolicy {

  private final PatEndpointRegistry registry;

  public boolean isPermitted(Authentication authentication, HttpServletRequest request) {
    if (!(authentication instanceof PatAuthenticationToken pat)) {
      return false;
    }
    return registry.requiredScope(request).map(pat::hasScope).orElse(false);
  }
}
