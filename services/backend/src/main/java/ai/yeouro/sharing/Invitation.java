package ai.yeouro.sharing;

import ai.yeouro.api.ApiModels.Role;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="invitations")
public class Invitation {
    @Id @Column(length=64) public String codeHash;
    @Column(nullable=false) public UUID tripId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public Role role;
    @Column(nullable=false) public Instant expiresAt;
    public boolean consumed;
    public boolean revoked;
    protected Invitation() {}
    public Invitation(String codeHash, UUID tripId, Role role, Instant expiresAt) {
        this.codeHash = codeHash; this.tripId = tripId; this.role = role; this.expiresAt = expiresAt;
    }
}
