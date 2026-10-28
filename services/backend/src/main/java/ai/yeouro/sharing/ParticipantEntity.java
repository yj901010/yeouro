package ai.yeouro.sharing;

import ai.yeouro.api.ApiModels.Role;
import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name="participants")
public class ParticipantEntity {
    @Id public UUID id;
    @Column(nullable=false) public UUID tripId;
    @Column(nullable=false,length=40) public String displayName;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public Role role;
    public boolean ready;
    public boolean active;
    protected ParticipantEntity() {}
    public ParticipantEntity(UUID tripId, String displayName, Role role) {
        id = UUID.randomUUID(); this.tripId = tripId; this.displayName = displayName.strip();
        this.role = role; active = true;
    }
}
