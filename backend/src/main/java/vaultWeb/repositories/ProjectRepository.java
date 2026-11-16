package vaultWeb.repositories;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import vaultWeb.models.Project;

public interface ProjectRepository extends JpaRepository<Project, Long> {
  @Query(
      "select m.project from ProjectMember m where m.user.id = :userId order by m.project.id desc")
  List<Project> findForUser(@Param("userId") Long userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select p from Project p where p.id = :id")
  Optional<Project> lockById(@Param("id") Long id);
}
