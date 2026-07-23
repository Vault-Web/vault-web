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
  REPLAY_ATTACK_DETECTED
}
