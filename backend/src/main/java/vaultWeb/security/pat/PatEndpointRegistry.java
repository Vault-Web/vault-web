package vaultWeb.security.pat;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.stereotype.Component;
import vaultWeb.models.enums.PatScope;

/**
 * The documented allow-list of core REST operations reachable with a personal access token.
 *
 * <p>Anything not listed here is denied for PAT callers, including token lifecycle, session/refresh
 * endpoints, administration, chat, groups, devices (E2EE key material), polls and media. Keep
 * {@code backend/docs/personal-access-tokens.md} in sync when adding entries.
 */
@Component
public class PatEndpointRegistry {

  public record Rule(HttpMethod method, String pattern, PatScope scope) {}

  private static final List<Rule> RULES =
      List.of(
          new Rule(HttpMethod.GET, "/api/dashboard/me", PatScope.PROFILE_READ),
          new Rule(HttpMethod.GET, "/api/auth/profile-picture", PatScope.PROFILE_READ),
          new Rule(HttpMethod.GET, "/api/auth/security-activity", PatScope.SECURITY_READ));

  private record CompiledRule(RequestMatcher matcher, PatScope scope) {}

  private final List<CompiledRule> compiled =
      RULES.stream()
          .map(
              r ->
                  new CompiledRule(
                      PathPatternRequestMatcher.withDefaults().matcher(r.method(), r.pattern()),
                      r.scope()))
          .toList();

  /** Scope required for this request, or empty if PATs may not call it at all. */
  public Optional<PatScope> requiredScope(HttpServletRequest request) {
    return compiled.stream()
        .filter(c -> c.matcher().matches(request))
        .map(CompiledRule::scope)
        .findFirst();
  }

  public List<Rule> rules() {
    return RULES;
  }
}
