package vaultWeb.repositories;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vaultWeb.models.ProjectResourceLink;

public interface ProjectResourceRepository extends JpaRepository<ProjectResourceLink, Long> {
  List<ProjectResourceLink> findByProjectIdOrderByIdAsc(Long projectId);

  void deleteByProjectId(Long projectId);
}
