package vaultWeb.dtos.pat;

/**
 * Response for token creation. {@code token} is the raw secret and is returned exactly once; it is
 * never persisted or retrievable afterwards.
 */
public record CreatedPersonalAccessTokenDto(String token, PersonalAccessTokenDto metadata) {

  @Override
  public String toString() {
    return "CreatedPersonalAccessTokenDto[token=<redacted>, metadata=" + metadata + "]";
  }
}
