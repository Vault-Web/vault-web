package vaultWeb.exceptions.notfound;

/**
 * Thrown when a PAT does not exist or is not owned by the caller (indistinguishable on purpose).
 */
public class PersonalAccessTokenNotFoundException extends RuntimeException {
  public PersonalAccessTokenNotFoundException(String message) {
    super(message);
  }
}
