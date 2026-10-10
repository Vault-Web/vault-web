package vaultWeb.dtos;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import vaultWeb.models.Notification;

@Data
@AllArgsConstructor
public class NotificationDto {
  private Long id;
  private String source;
  private String type;
  private String title;
  private String message;
  private String linkUrl;
  private String referenceId;
  private Instant createdAt;
  private Instant readAt;

  public static NotificationDto from(Notification item) {
    return new NotificationDto(
        item.getId(),
        item.getSource(),
        item.getType(),
        item.getTitle(),
        item.getMessage(),
        item.getLinkUrl(),
        item.getReferenceId(),
        item.getCreatedAt(),
        item.getReadAt());
  }
}
