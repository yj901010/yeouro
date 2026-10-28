package ai.yeouro.trip;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProposalRepository extends JpaRepository<ProposalEntity,UUID> {
    List<ProposalEntity> findByTripIdOrderById(UUID tripId);
    Optional<ProposalEntity> findByParticipantIdAndRequestId(UUID participantId, UUID requestId);
}
