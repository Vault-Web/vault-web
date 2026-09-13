package vaultWeb.models;

import com.fasterxml.jackson.annotation.JsonBackReference;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "poll_votes",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_poll_votes_poll_user",
            columnNames = {"poll_id", "user_id"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PollVote {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @ManyToOne(optional = false)
  @JoinColumn(name = "poll_id", nullable = false)
  @JsonIgnore
  private Poll poll;

  @ManyToOne(optional = false)
  @JoinColumn(name = "poll_option_id")
  @JsonBackReference
  private PollOption option;

  @ManyToOne(optional = false)
  @JoinColumn(name = "user_id")
  private User user;
}
