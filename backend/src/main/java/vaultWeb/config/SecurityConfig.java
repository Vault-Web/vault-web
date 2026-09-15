package vaultWeb.config;

import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.builders.AuthenticationManagerBuilder;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import vaultWeb.security.JwtAuthFilter;
import vaultWeb.security.JwtAuthenticationEntryPoint;
import vaultWeb.services.auth.MyUserDetailsService;

@Configuration
@EnableWebSecurity
@RequiredArgsConstructor
public class SecurityConfig {

  private final MyUserDetailsService userDetailsService;
  private final JwtAuthFilter jwtAuthFilter;
  private final CorsConfig corsConfig;
  private final JwtAuthenticationEntryPoint jwtAuthenticationEntryPoint;

  /**
   * Defines the PasswordEncoder bean used for hashing passwords. Here, BCryptPasswordEncoder is
   * used, which is a strong hashing algorithm that adds salt and is computationally expensive to
   * resist brute force attacks.
   *
   * <p>This encoder is used both when registering users (to hash their password) and when
   * authenticating users (to verify raw password against stored hash).
   */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  /**
   * Configures and provides the AuthenticationManager bean. This method obtains the
   * AuthenticationManagerBuilder from the HttpSecurity object, which is used to configure
   * authentication mechanisms.
   *
   * <p>It sets the custom UserDetailsService (userDetailsService) to load user-specific data (such
   * as username, password, and roles) from the database. It also sets the PasswordEncoder
   * (passwordEncoder) to handle password hashing and verification, ensuring that plaintext
   * passwords can be compared securely against stored hashes.
   *
   * <p>Finally, it builds and returns the AuthenticationManager instance, which is the core
   * component used during authentication attempts (e.g., during login)
   *
   * @param http the HttpSecurity object, providing access to shared objects including the
   *     AuthenticationManagerBuilder
   * @param passwordEncoder the PasswordEncoder bean used for hashing and verifying passwords
   * @return the configured AuthenticationManager instance
   * @throws Exception if an error occurs during building the AuthenticationManager
   */
  @Bean
  public AuthenticationManager authenticationManager(
      HttpSecurity http, PasswordEncoder passwordEncoder) throws Exception {
    AuthenticationManagerBuilder auth = http.getSharedObject(AuthenticationManagerBuilder.class);
    auth.userDetailsService(userDetailsService).passwordEncoder(passwordEncoder);
    return auth.build();
  }

  /**
   * Configures the security filter chain for HTTP requests. This method sets up the security
   * policies for the application, including:
   *
   * <p>- Disabling CSRF protection because the app is stateless and typically uses tokens (like
   * JWT). - Configuring the session management to be stateless, meaning the server does not keep
   * any session data between requests. - Defining authorization rules: * The specified endpoints
   * for authentication (/login, /register) and API documentation (Swagger UI and OpenAPI docs) are
   * publicly accessible without authentication. * All other requests require authentication.
   *
   * <p>This configuration ensures that only authorized users can access protected endpoints, while
   * allowing free access to login, registration, and API docs.
   *
   * @param http the HttpSecurity object used to configure web based security for specific http
   *     requests
   * @return the configured SecurityFilterChain instance
   * @throws Exception if an error occurs while building the security filter chain
   */
  @Bean
  public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
    http.cors(cors -> cors.configurationSource(corsConfig.corsConfigurationSource()))
        .csrf(csrf -> csrf.disable())
        .sessionManagement(sess -> sess.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .exceptionHandling(ex -> ex.authenticationEntryPoint(jwtAuthenticationEntryPoint))
        .authorizeHttpRequests(
            auth ->
                auth.requestMatchers(
                        "/api/auth/login",
                        "/api/auth/register",
                        "/api/auth/check-username",
                        "/api/auth/refresh",
                        "/api/auth/logout",
                        "/api/media/stream/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                        "/swagger-ui.html",
                        "/swagger-resources/**",
                        "/webjars/**",
                        "/docs/**",
                        "/ws-chat/**",
                        "/uploads/profile-pictures/**")
                    .permitAll()
                    .anyRequest()
                    .authenticated())
        .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }
}
