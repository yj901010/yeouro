package ai.yeouro.sharing;

import ai.yeouro.api.ApiModels.Role;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity @Table(name="access_tokens")
public class AccessToken {
    @Id @Column(length=64) public String hash;
    @Column(nullable=false) public UUID tripId;
    public UUID participantId;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public Role role;
    @Column(nullable=false) public Instant expiresAt;
    public boolean revoked;
    protected AccessToken() {}
    public AccessToken(String hash, UUID tripId, UUID participantId, Role role, Instant expiresAt) {
        this.hash = hash; this.tripId = tripId; this.participantId = participantId;
        this.role = role; this.expiresAt = expiresAt;
    }
}
