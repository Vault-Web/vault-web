package vaultWeb.security.pat;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import vaultWeb.models.enums.PatScope;

/**
 * Authentication produced by a valid personal access token.
 *
 * <p>The principal is the owner's {@link UserDetails}, so existing controllers resolving the
 * current user keep working. It is a distinct type so authorization can restrict PAT requests to
 * the {@link PatEndpointRegistry} allow-list and lifecycle endpoints can refuse PAT callers.
 */
public class PatAuthenticationToken extends AbstractAuthenticationToken {

  private final UserDetails principal;
  private final Long tokenId;
  private final String publicId;
  private final Long userId;
  private final Set<PatScope> scopes;

  public PatAuthenticationToken(
      UserDetails principal, Long tokenId, String publicId, Long userId, Set<PatScope> scopes) {
    super(scopes.stream().map(s -> new SimpleGrantedAuthority("SCOPE_" + s.getValue())).toList());
    this.principal = principal;
    this.tokenId = tokenId;
    this.publicId = publicId;
    this.userId = userId;
    this.scopes =
        Collections.unmodifiableSet(
            scopes.isEmpty() ? EnumSet.noneOf(PatScope.class) : EnumSet.copyOf(scopes));
    setAuthenticated(true);
  }

  @Override
  public Object getCredentials() {
    return null;
  }

  @Override
  public UserDetails getPrincipal() {
    return principal;
  }

  public Long getTokenId() {
    return tokenId;
  }

  public String getPublicId() {
    return publicId;
  }

  public Long getUserId() {
    return userId;
  }

  public Set<PatScope> getScopes() {
    return scopes;
  }

  public boolean hasScope(PatScope scope) {
    return scopes.contains(scope);
  }
}
