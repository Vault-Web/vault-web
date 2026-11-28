package vaultWeb.dtos.pat;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import lombok.Data;
import lombok.ToString;

@Data
public class CreatePersonalAccessTokenRequest {

  @NotBlank
  @Size(max = 64)
  private String name;

  @NotEmpty private List<String> scopes;

  @NotNull
  @Min(1)
  @Max(365)
  private Integer expiresInDays;

  /** Re-authentication: required for every token creation. */
  @NotBlank @ToString.Exclude private String currentPassword;
}
