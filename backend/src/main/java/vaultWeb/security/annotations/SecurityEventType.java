package vaultWeb.security.annotations;

/** Enumeration of security event types for audit logging. */
public enum SecurityEventType {
  LOGIN,
  LOGOUT,
  REGISTER,
  TOKEN_REFRESH,
  PASSWORD_CHANGE,
  NEW_DEVICE_DETECTED,
  VAULT_UNLOCKED,
  VAULT_LOCKED,
  TOKEN_REPLAY_DETECTED,
  PAT_CREATED,
  PAT_REVOKED,
  PAT_REVOKED_ALL
}
