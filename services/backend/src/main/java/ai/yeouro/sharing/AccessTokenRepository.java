package ai.yeouro.sharing;

import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AccessTokenRepository extends JpaRepository<AccessToken,String> {
    List<AccessToken> findByTripId(UUID tripId);
}
