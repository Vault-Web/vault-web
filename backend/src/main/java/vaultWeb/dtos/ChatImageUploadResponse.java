package vaultWeb.dtos;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChatImageUploadResponse {
  private String message;
  private Long imageId;
}
