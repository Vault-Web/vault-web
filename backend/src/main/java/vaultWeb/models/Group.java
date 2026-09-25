package vaultWeb.models;

import com.fasterxml.jackson.annotation.JsonManagedReference;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.ToString;
import vaultWeb.dtos.GroupDto;

@Entity
@Data
@AllArgsConstructor
@NoArgsConstructor
@Builder
@Table(name = "group_entity")
public class Group {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  private String name;
  private String description;

  @OneToMany(mappedBy = "group", cascade = CascadeType.ALL, orphanRemoval = true)
  @JsonManagedReference
  @ToString.Exclude
  private List<GroupMember> members;

  @ManyToOne
  @JoinColumn(name = "created_by_id")
  @ToString.Exclude
  private User createdBy;

  private Instant createdAt;
  private Boolean isPublic;

  public Group(GroupDto dto) {
    name = dto.getName();
    description = dto.getDescription();
    this.members = new ArrayList<>();
    isPublic = dto.getIsPublic() != null ? dto.getIsPublic() : true;
    createdAt = Instant.now();
  }
}
