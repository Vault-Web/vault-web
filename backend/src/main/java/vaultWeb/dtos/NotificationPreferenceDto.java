package vaultWeb.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class NotificationPreferenceDto {
  private String source;
  private boolean muted;
}

// Source preferences are scoped to the authenticated user.
