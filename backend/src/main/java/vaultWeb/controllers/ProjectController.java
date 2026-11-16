package vaultWeb.controllers;

import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import vaultWeb.dtos.ProjectDto;
import vaultWeb.services.ProjectService;

@RestController
@RequestMapping("/api/projects")
@RequiredArgsConstructor
public class ProjectController {
  private final ProjectService projects;

  @GetMapping
  public List<ProjectDto> list() {
    return projects.list();
  }

  @PostMapping
  public ResponseEntity<Map<String, Long>> create(@RequestBody ProjectDto.Update input) {
    return ResponseEntity.status(201).body(Map.of("id", projects.create(input)));
  }

  @GetMapping("/{id}")
  public ProjectDto overview(
      @PathVariable Long id,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    return projects.overview(id, authorization);
  }

  @PatchMapping("/{id}")
  public void update(@PathVariable Long id, @RequestBody ProjectDto.Update input) {
    projects.update(id, input);
  }

  @DeleteMapping("/{id}")
  public void delete(@PathVariable Long id) {
    projects.delete(id);
  }

  @PutMapping("/{id}/members")
  public void member(@PathVariable Long id, @RequestBody ProjectDto.Membership input) {
    projects.member(id, input);
  }

  @DeleteMapping("/{id}/members/{userId}")
  public void removeMember(@PathVariable Long id, @PathVariable Long userId) {
    projects.removeMember(id, userId, false);
  }

  @DeleteMapping("/{id}/leave")
  public void leave(@PathVariable Long id) {
    projects.removeMember(id, null, true);
  }

  @PostMapping("/{id}/resources")
  public void link(
      @PathVariable Long id,
      @RequestBody ProjectDto.Link input,
      @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
    projects.link(id, input, authorization);
  }

  @DeleteMapping("/{id}/resources/{linkId}")
  public void unlink(@PathVariable Long id, @PathVariable Long linkId) {
    projects.unlink(id, linkId);
  }
}
