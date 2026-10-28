package ai.yeouro.sharing;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParticipantRepository extends JpaRepository<ParticipantEntity,UUID> {
    List<ParticipantEntity> findByTripIdOrderById(UUID tripId);
}
