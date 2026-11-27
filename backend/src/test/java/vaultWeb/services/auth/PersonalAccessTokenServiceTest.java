package vaultWeb.services.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import vaultWeb.dtos.pat.CreatePersonalAccessTokenRequest;
import vaultWeb.dtos.pat.CreatedPersonalAccessTokenDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.exceptions.notfound.PersonalAccessTokenNotFoundException;
import vaultWeb.models.PersonalAccessToken;
import vaultWeb.models.User;
import vaultWeb.models.enums.PatScope;
import vaultWeb.repositories.PersonalAccessTokenRepository;
import vaultWeb.security.TokenHashUtil;
import vaultWeb.security.pat.PatAuthenticationToken;
import vaultWeb.security.pat.PatEndpointRegistry;

@ExtendWith(MockitoExtension.class)
class PersonalAccessTokenServiceTest {

  @Mock private PersonalAccessTokenRepository repository;
  @Mock private PasswordEncoder passwordEncoder;

  private PersonalAccessTokenService service;
  private User owner;

  @BeforeEach
  void setUp() {
    service =
        new PersonalAccessTokenService(repository, passwordEncoder, new PatEndpointRegistry());
    owner = new User();
    owner.setId(7L);
    owner.setUsername("alice");
    owner.setPassword("hashed");
  }

  @AfterEach
  void clearContext() {
    SecurityContextHolder.clearContext();
  }

  private CreatePersonalAccessTokenRequest request(List<String> scopes, int days) {
    CreatePersonalAccessTokenRequest r = new CreatePersonalAccessTokenRequest();
    r.setName("  cli  ");
    r.setScopes(scopes);
    r.setExpiresInDays(days);
    r.setCurrentPassword("pw");
    return r;
  }

  private void passwordOk() {
    when(passwordEncoder.matches("pw", "hashed")).thenReturn(true);
  }

  @Test
  void create_persistsDigestOnly_andReturnsSecretOnce() {
    passwordOk();
    when(repository.existsByPublicId(anyString())).thenReturn(false);

    CreatedPersonalAccessTokenDto created =
        service.create(owner, request(List.of("profile:read"), 30));

    ArgumentCaptor<PersonalAccessToken> saved = ArgumentCaptor.forClass(PersonalAccessToken.class);
    verify(repository).save(saved.capture());
    PersonalAccessToken token = saved.getValue();

    assertEquals(TokenHashUtil.sha256(created.token()), token.getTokenHash());
    assertNotEquals(created.token(), token.getTokenHash());
    assertEquals("cli", token.getName());
    assertEquals(Set.of(PatScope.PROFILE_READ), token.getScopes());
    assertEquals(PersonalAccessToken.AUDIENCE_CORE, token.getAudience());
    assertEquals(Duration.ofDays(30), Duration.between(token.getCreatedAt(), token.getExpiresAt()));
    assertTrue(created.token().contains(token.getPublicId()));
    assertFalse(created.toString().contains(created.token()), "toString must redact the secret");
  }

  @Test
  void create_withWrongPassword_throwsAndPersistsNothing() {
    when(passwordEncoder.matches("pw", "hashed")).thenReturn(false);
    assertThrows(
        UnauthorizedException.class,
        () -> service.create(owner, request(List.of("profile:read"), 30)));
    verify(repository, never()).save(any());
  }

  @Test
  void create_rejectsUnknownScope() {
    passwordOk();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(owner, request(List.of("cloud:read"), 30)));
  }

  @Test
  void create_rejectsLifetimeAboveMax() {
    passwordOk();
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(owner, request(List.of("profile:read"), 366)));
  }

  @Test
  void create_rejectsWhenActiveLimitReached() {
    passwordOk();
    when(repository.countActiveByUser(eq(owner), any()))
        .thenReturn((long) PersonalAccessTokenService.MAX_ACTIVE_TOKENS_PER_USER);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.create(owner, request(List.of("profile:read"), 30)));
  }

  @Test
  void lifecycle_refusesPatAuthenticatedCaller() {
    SecurityContextHolder.getContext()
        .setAuthentication(
            new PatAuthenticationToken(
                new org.springframework.security.core.userdetails.User("alice", "x", List.of()),
                1L,
                "AAAAAAAAAAAA",
                7L,
                EnumSet.allOf(PatScope.class)));

    assertThrows(
        AccessDeniedException.class,
        () -> service.create(owner, request(List.of("profile:read"), 30)));
    assertThrows(AccessDeniedException.class, () -> service.list(owner));
    assertThrows(AccessDeniedException.class, () -> service.revoke(owner, "AAAAAAAAAAAA"));
    verify(passwordEncoder, never()).matches(any(), any());
  }

  @Test
  void revoke_foreignOrUnknownToken_isNotFound() {
    when(repository.findByPublicIdAndUser("AAAAAAAAAAAA", owner)).thenReturn(Optional.empty());
    assertThrows(
        PersonalAccessTokenNotFoundException.class, () -> service.revoke(owner, "AAAAAAAAAAAA"));
  }

  private PersonalAccessToken stored(String raw, Instant expiresAt, Instant revokedAt) {
    PersonalAccessToken t = new PersonalAccessToken();
    t.setId(1L);
    t.setPublicId(raw.substring(7, 19));
    t.setUser(owner);
    t.setName("cli");
    t.setTokenHash(TokenHashUtil.sha256(raw));
    t.setScopes(Set.of(PatScope.PROFILE_READ));
    t.setCreatedAt(Instant.now().minusSeconds(60));
    t.setExpiresAt(expiresAt);
    t.setRevokedAt(revokedAt);
    return t;
  }

  private static final String RAW = "vw_pat_" + "B".repeat(12) + "_" + "C".repeat(43);

  @Test
  void authenticate_validToken_yieldsPatPrincipal_andTouchesLastUsed() {
    when(repository.findByPublicId("BBBBBBBBBBBB"))
        .thenReturn(Optional.of(stored(RAW, Instant.now().plusSeconds(60), null)));

    PatAuthenticationToken auth = service.authenticate(RAW).orElseThrow();

    assertEquals("alice", auth.getPrincipal().getUsername());
    assertTrue(auth.hasScope(PatScope.PROFILE_READ));
    assertFalse(auth.hasScope(PatScope.SECURITY_READ));
    verify(repository).touchLastUsed(eq(1L), any(), any());
  }

  @Test
  void authenticate_rejectsWrongSecret() {
    String other = "vw_pat_" + "B".repeat(12) + "_" + "D".repeat(43);
    when(repository.findByPublicId("BBBBBBBBBBBB"))
        .thenReturn(Optional.of(stored(RAW, Instant.now().plusSeconds(60), null)));
    assertTrue(service.authenticate(other).isEmpty());
    verify(repository, never()).touchLastUsed(any(), any(), any());
  }

  @Test
  void authenticate_rejectsExpiredAndRevoked() {
    when(repository.findByPublicId("BBBBBBBBBBBB"))
        .thenReturn(Optional.of(stored(RAW, Instant.now().minusSeconds(1), null)));
    assertTrue(service.authenticate(RAW).isEmpty());

    when(repository.findByPublicId("BBBBBBBBBBBB"))
        .thenReturn(
            Optional.of(stored(RAW, Instant.now().plusSeconds(60), Instant.now().minusSeconds(1))));
    assertTrue(service.authenticate(RAW).isEmpty());
  }

  @Test
  void authenticate_rejectsForeignAudience() {
    PersonalAccessToken t = stored(RAW, Instant.now().plusSeconds(60), null);
    t.setAudience("cloud");
    when(repository.findByPublicId("BBBBBBBBBBBB")).thenReturn(Optional.of(t));
    assertTrue(service.authenticate(RAW).isEmpty());
  }

  @Test
  void authenticate_malformed_doesNotHitDatabase() {
    assertTrue(service.authenticate("vw_pat_nope").isEmpty());
    assertTrue(service.authenticate(null).isEmpty());
    verify(repository, never()).findByPublicId(any());
  }
}
