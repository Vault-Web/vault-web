package vaultWeb.dtos;
import lombok.*;


@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class imageDto {
    private Long id;
    private String file_name;
    private String content_type; // image/png, image/jpeg, etc.
    @Column(name = "sender_id")
    private int senderId;
    private byte[] data;
}

