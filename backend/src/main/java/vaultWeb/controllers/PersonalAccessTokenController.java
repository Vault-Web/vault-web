package vaultWeb.controllers;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import vaultWeb.dtos.pat.CreatePersonalAccessTokenRequest;
import vaultWeb.dtos.pat.CreatedPersonalAccessTokenDto;
import vaultWeb.dtos.pat.PatScopeDto;
import vaultWeb.dtos.pat.PersonalAccessTokenDto;
import vaultWeb.exceptions.UnauthorizedException;
import vaultWeb.models.User;
import vaultWeb.security.annotations.ApiRateLimit;
import vaultWeb.security.annotations.SecurityEventType;
import vaultWeb.services.UserService;
import vaultWeb.services.auth.AuthService;
import vaultWeb.services.auth.PersonalAccessTokenService;

/**
 * Lifecycle API for personal access tokens. Requires an interactive (JWT) session; requests
 * authenticated with a PAT are rejected by the security chain and again by the service.
 */
@RestController
@RequestMapping("/api/auth/tokens")
@RequiredArgsConstructor
@Tag(name = "Personal Access Tokens", description = "Scoped tokens for CLI and automation clients")
public class PersonalAccessTokenController {

  private static final String SUCCESS = "SUCCESS";
  private static final String FAILURE = "FAILURE";

  private final PersonalAccessTokenService tokenService;
  private final AuthService authService;
  private final UserService userService;

  @GetMapping("/scopes")
  @Operation(summary = "List the scopes that can be granted to a personal access token")
  public ResponseEntity<List<PatScopeDto>> scopes() {
    requireUser();
    return ResponseEntity.ok(tokenService.supportedScopes());
  }

  @GetMapping
  @Operation(summary = "List the caller's personal access tokens (metadata only)")
  public ResponseEntity<List<PersonalAccessTokenDto>> list() {
    return ResponseEntity.ok(tokenService.list(requireUser()));
  }

  @PostMapping
  @ApiRateLimit(capacity = 5, refillTokens = 5, refillDurationMinutes = 1, useIpAddress = true)
  @Operation(
      summary = "Create a personal access token",
      description =
          "Requires the current password. The token is returned once in this response and "
              + "cannot be retrieved again.")
  @ApiResponse(responseCode = "201", description = "Token created")
  @ApiResponse(responseCode = "400", description = "Invalid name, scope, lifetime or limit")
  @ApiResponse(responseCode = "401", description = "Not authenticated or wrong password")
  public ResponseEntity<CreatedPersonalAccessTokenDto> create(
      @Valid @RequestBody CreatePersonalAccessTokenRequest request,
      HttpServletRequest httpRequest) {
    User user = requireUser();
    CreatedPersonalAccessTokenDto created;
    try {
      created = tokenService.create(user, request);
    } catch (UnauthorizedException e) {
      userService.logSecurityEvent(user, SecurityEventType.PAT_CREATED, FAILURE, httpRequest, null);
      throw e;
    }
    userService.logSecurityEvent(user, SecurityEventType.PAT_CREATED, SUCCESS, httpRequest, null);
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .header("Pragma", "no-cache")
        .body(created);
  }

  @DeleteMapping("/{publicId}")
  @Operation(summary = "Revoke one of the caller's personal access tokens")
  @ApiResponse(responseCode = "200", description = "Token revoked (idempotent)")
  @ApiResponse(responseCode = "404", description = "No such token for this user")
  public ResponseEntity<PersonalAccessTokenDto> revoke(
      @PathVariable String publicId, HttpServletRequest httpRequest) {
    User user = requireUser();
    PersonalAccessTokenDto revoked = tokenService.revoke(user, publicId);
    userService.logSecurityEvent(user, SecurityEventType.PAT_REVOKED, SUCCESS, httpRequest, null);
    return ResponseEntity.ok(revoked);
  }

  private User requireUser() {
    User user = authService.getCurrentUser();
    if (user == null) {
      throw new UnauthorizedException("User not authenticated");
    }
    return user;
  }
}
