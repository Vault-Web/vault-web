package vaultWeb.models;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "chat_images")
public class ChatImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "createdon")
    private OffsetDateTime createdOn;

    @Column(name = "file_name")
    private String fileName;

    @Column(name = "content_type")
    private String contentType;

    @NotNull
    @Column(name = "sender_id")
    private Long senderId;

    @NotNull
    @Column(name = "receiver_id")
    private Long receiverId;

    @Column(name = "image_path")
    private String imagePath;
}
