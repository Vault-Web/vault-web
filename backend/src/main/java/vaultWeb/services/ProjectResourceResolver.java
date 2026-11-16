package vaultWeb.services;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import vaultWeb.dtos.ProjectDto;
import vaultWeb.models.ProjectResourceLink;
import vaultWeb.models.User;

/** Resolves only exact existing shares; project membership itself grants no Cloud access. */
@Service
@RequiredArgsConstructor
public class ProjectResourceResolver {
  private final CloudPageMediaClient cloud;
  private final ObjectMapper mapper;

  public java.util.List<ProjectDto.Resource> resolveAll(
      java.util.List<ProjectResourceLink> links, User caller, String authorization) {
    var context = new ResolutionContext();
    return links.stream().map(link -> resolve(link, caller, authorization, context)).toList();
  }

  private static class ResolutionContext {
    private boolean loaded;
    private com.fasterxml.jackson.databind.JsonNode shares;
    private final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(10).toNanos();
  }

  public ProjectDto.Resource resolve(ProjectResourceLink link, User caller, String authorization) {
    return resolve(link, caller, authorization, new ResolutionContext());
  }

  private ProjectDto.Resource resolve(
      ProjectResourceLink link, User caller, String authorization, ResolutionContext context) {
    var unavailable = new ProjectDto.Resource(link.getId(), false, null, null, null, null);
    if (authorization == null || !authorization.startsWith("Bearer ")) return unavailable;
    try {
      if (link.getOwner().getId().equals(caller.getId())) {
        String endpoint = link.isFolder() ? "/api/folders/path?path=" : "/api/files/view?path=";
        if (accessible(
            endpoint + URLEncoder.encode(link.getPath(), StandardCharsets.UTF_8),
            authorization,
            context)) {
          return new ProjectDto.Resource(
              link.getId(), true, caller.getUsername(), link.getPath(), link.isFolder(), null);
        }
      } else {
        if (!context.loaded) {
          context.loaded = true;
          var response = fetch("/api/shares/shared-with-me", authorization, context);
          try (var body = response.body()) {
            if (response.statusCode() == 200) {
              byte[] json = body.readNBytes(1_048_577);
              if (json.length <= 1_048_576) context.shares = mapper.readTree(json);
            }
          }
        }
        var shares = context.shares;
        if (shares == null || !shares.isArray()) return unavailable;
        for (var share : shares) {
          String id = share.path("id").asText();
          if (!id.matches("[a-zA-Z0-9-]+")
              || share.path("revoked").asBoolean(true)
              || !share.path("ownerUsername").asText().equals(link.getOwner().getUsername())
              || !share.path("recipientUsername").asText().equals(caller.getUsername())
              || !canonical(share.path("relativePath").asText()).equals(link.getPath())
              || !share.path("resourceType").asText().equals(link.isFolder() ? "FOLDER" : "FILE"))
            continue;
          boolean view = false;
          for (var permission : share.path("permissions"))
            view |= permission.asText().equals("VIEW");
          if (view
              && accessible(
                  "/api/shares/" + id + (link.isFolder() ? "/content" : "/view"),
                  authorization,
                  context)) {
            return new ProjectDto.Resource(
                link.getId(),
                true,
                link.getOwner().getUsername(),
                link.getPath(),
                link.isFolder(),
                id);
          }
        }
      }
    } catch (InterruptedException ex) {
      Thread.currentThread().interrupt();
    } catch (Exception ex) {
      // Unavailable, revoked, moved and offline references are equally private.
    }
    return unavailable;
  }

  private java.net.http.HttpResponse<java.io.InputStream> fetch(
      String endpoint, String authorization, ResolutionContext context) throws Exception {
    long remaining = context.deadline - System.nanoTime();
    if (remaining <= 0) throw new java.net.http.HttpTimeoutException("Reference budget exhausted");
    return cloud.fetchProjectReference(
        endpoint, authorization, java.time.Duration.ofNanos(remaining));
  }

  private boolean accessible(String endpoint, String authorization, ResolutionContext context)
      throws Exception {
    var response = fetch(endpoint, authorization, context);
    try (var body = response.body()) {
      // Cloud validates ownership/share permissions before reporting an empty file's range.
      boolean fileView = endpoint.startsWith("/api/files/view?") || endpoint.endsWith("/view");
      return response.statusCode() == 200
          || response.statusCode() == 206
          || (fileView
              && response.statusCode() == 416
              && response
                  .headers()
                  .allValues("Content-Range")
                  .equals(java.util.List.of("bytes */0")));
    }
  }

  public static String canonical(String input) {
    if (input == null
        || input.isBlank()
        || input.length() > 1024
        || input.indexOf('\\') >= 0
        || input.chars().anyMatch(c -> c < 32))
      throw new IllegalArgumentException("Invalid Cloud path");
    String path = input.startsWith("/") ? input.substring(1) : input;
    if (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    if (path.isEmpty()) return "/";
    for (String segment : path.split("/", -1)) {
      if (segment.isEmpty()
          || segment.equals(".")
          || segment.equals("..")
          || segment.equals(".trash")) throw new IllegalArgumentException("Invalid Cloud path");
    }
    return "/" + path;
  }
}
