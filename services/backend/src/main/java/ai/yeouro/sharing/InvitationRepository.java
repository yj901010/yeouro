package ai.yeouro.sharing;

import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface InvitationRepository extends JpaRepository<Invitation,String> {
    @Query("select i.tripId from Invitation i where i.codeHash = :hash")
    Optional<UUID> findTripId(@Param("hash") String hash);
    List<Invitation> findByTripId(UUID tripId);
}
