package vaultWeb.repositories;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import vaultWeb.models.ChatImage;

@Repository
public interface ChatImageRepo extends CrudRepository<ChatImage, Long> {

  @Transactional
  @Query(
      value =
          "INSERT INTO chat_images (image_content, receiver_id, sender_id, createdon) "
              + "VALUES (CAST(:imageData AS bytea), :receiverId, :senderId, :createdon) RETURNING id",
      nativeQuery = true)
  Long saveImage(
      @Param("imageData") byte[] imageData,
      @Param("senderId") Integer senderId,
      @Param("receiverId") Integer receiverId,
      @Param("createdon") OffsetDateTime createdon);

  List<ChatImage> createdon(OffsetDateTime createdon);
}
