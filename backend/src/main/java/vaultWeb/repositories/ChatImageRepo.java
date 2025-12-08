// java
// Add native insert to `ChatImageRepo` (assumes image_content is bytea)
package vaultWeb.repositories;

import org.springframework.data.jpa.repository.JpaRepository;
import vaultWeb.models.ChatImage;

public interface ChatImageRepo extends JpaRepository<ChatImage, Long> {
    // rely on inherited CRUD methods
}
