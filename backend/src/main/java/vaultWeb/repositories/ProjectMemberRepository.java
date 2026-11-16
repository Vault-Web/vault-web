package vaultWeb.repositories;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import vaultWeb.models.ProjectMember;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {
  boolean existsByProjectIdAndUserId(Long projectId, Long userId);

  Optional<ProjectMember> findByProjectIdAndUserId(Long projectId, Long userId);

  List<ProjectMember> findByProjectIdOrderByIdAsc(Long projectId);

  long countByProjectIdAndRole(Long projectId, ProjectMember.Role role);

  void deleteByProjectId(Long projectId);
}
