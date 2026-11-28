package vaultWeb.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import vaultWeb.security.exception.JwtAuthenticationException;
import vaultWeb.services.auth.MyUserDetailsService;

/**
 * JWT authentication filter that intercepts incoming HTTP requests and validates JWT tokens.
 *
 * <p>This filter extracts the JWT token from the "Authorization" header (Bearer scheme), validates
 * it using {@link JwtUtil}, and sets the authenticated user in the Spring Security context.
 * Requests to "/api/auth/**" are excluded from authentication.
 *
 * <p>This filter extends {@link OncePerRequestFilter}, ensuring it is executed once per request.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

  private final JwtUtil jwtUtil;
  private final MyUserDetailsService userDetailsService;

  private final Set<String> PUBLIC_PATHS =
      new HashSet<String>(
          List.of(
              "/api/auth/login",
              "/api/auth/register",
              "/api/auth/check-username",
              "/api/auth/refresh",
              "/api/auth/refresh/logout",
              "/api/auth/logout"));

  /**
   * Filters each HTTP request, performing JWT-based authentication.
   *
   * <p>This filter runs once per request and is responsible for extracting and validating JWT
   * access tokens from the {@code Authorization} header.
   *
   * <p>Processing steps:
   *
   * <ol>
   *   <li>Skip requests targeting public authentication endpoints.
   *   <li>Extract the JWT from the {@code Authorization} header if it uses the {@code Bearer}
   *       scheme.
   *   <li>Validate the token and extract the username.
   *   <li>Load user details and populate the {@link SecurityContextHolder} with an authenticated
   *       {@link UsernamePasswordAuthenticationToken}.
   * </ol>
   *
   * <p>If the JWT is invalid or expired, this filter throws a {@link
   * vaultWeb.security.exception.JwtAuthenticationException}. The exception is handled by Spring
   * Security’s {@link org.springframework.security.web.AuthenticationEntryPoint}, which results in
   * a {@code 401 Unauthorized} response.
   *
   * @param request the incoming HTTP request
   * @param response the HTTP response
   * @param filterChain the remaining filter chain
   * @throws ServletException if a servlet-related error occurs
   * @throws IOException if an I/O error occurs
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String path = request.getServletPath();
    if (PUBLIC_PATHS.contains(path)) {
      filterChain.doFilter(request, response);
      return;
    }

    final String authHeader = request.getHeader("Authorization");
    String username = null;
    String jwt;

    if (authHeader != null && authHeader.startsWith("Bearer ")) {
      jwt = authHeader.substring(7);
      try {
        username = jwtUtil.extractUsername(jwt);
      } catch (JwtException e) {
        throw new JwtAuthenticationException("Invalid or expired token");
      }
    }

    if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
      UserDetails userDetails = userDetailsService.loadUserByUsername(username);
      UsernamePasswordAuthenticationToken authToken =
          new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());

      authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
      SecurityContextHolder.getContext().setAuthentication(authToken);
    }

    filterChain.doFilter(request, response);
  }
}
