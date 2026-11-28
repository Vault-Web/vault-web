package vaultWeb.services.auth;

import org.springframework.http.ResponseCookie;

public record LoginResult(String accessToken, ResponseCookie refreshCookie) {}
