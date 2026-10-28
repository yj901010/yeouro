package ai.yeouro.trip;

import ai.yeouro.api.ApiModels.ProposalStatus;
import jakarta.persistence.*;
import java.util.UUID;

@Entity @Table(name="proposals", uniqueConstraints=@UniqueConstraint(columnNames={"participant_id","request_id"}))
public class ProposalEntity {
    @Id public UUID id;
    @Column(nullable=false) public UUID tripId;
    @Column(nullable=false) public UUID participantId;
    @Column(nullable=false) public UUID requestId;
    @Embedded public PlaceValue place;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=24) public ProposalStatus status;
    public long revision;
    @Column(length=200) public String resultReason;
    protected ProposalEntity() {}
    public ProposalEntity(UUID tripId, UUID participantId, UUID requestId, PlaceValue place) {
        this.id = UUID.randomUUID(); this.tripId = tripId; this.participantId = participantId;
        this.requestId = requestId; this.place = place; this.status = ProposalStatus.PENDING;
    }
}
