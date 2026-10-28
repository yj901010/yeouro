package ai.yeouro.trip;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TripRepository extends JpaRepository<TripEntity,UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from TripEntity t where t.id = :id")
    Optional<TripEntity> lockById(@Param("id") UUID id);
    @Modifying @Query("delete from TripEntity t where t.expiresAt <= :now")
    int deleteExpired(@Param("now") Instant now);
}
