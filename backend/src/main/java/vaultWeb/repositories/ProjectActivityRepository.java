package vaultWeb.repositories;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import vaultWeb.models.ProjectActivity;

public interface ProjectActivityRepository extends JpaRepository<ProjectActivity, Long> {
  List<ProjectActivity> findTop50ByProjectIdOrderByIdDesc(Long projectId);

  void deleteByProjectId(Long projectId);
}
