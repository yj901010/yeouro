package ai.yeouro.v2.identity;

import ai.yeouro.api.ApiException;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

/** Validates the one configured identity issuer. Never accepts a caller-supplied identity header. */
@Service
@ConditionalOnProperty(name="yeouro.v2.enabled",havingValue="true")
public class IdentityService {
    private final JdbcTemplate db;
    private final JwtDecoder decoder;
    private final String audience;

    public IdentityService(JdbcTemplate db,
                           @Value("${yeouro.v2.auth.issuer:}") String issuer,
                           @Value("${yeouro.v2.auth.jwk-set-uri:}") String jwks,
                           @Value("${yeouro.v2.auth.audience:authenticated}") String audience) {
        this.db = db;
        this.audience = audience;
        this.decoder = configuredDecoder(issuer, jwks);
    }

    private JwtDecoder configuredDecoder(String issuer, String jwks) {
        if (issuer.isBlank() || jwks.isBlank()) return null;
        NimbusJwtDecoder configured = NimbusJwtDecoder.withJwkSetUri(jwks)
                .jwsAlgorithms(algorithms -> {
                    algorithms.add(SignatureAlgorithm.RS256);
                    algorithms.add(SignatureAlgorithm.ES256);
                }).build();
        configured.setJwtValidator(JwtValidators.createDefaultWithIssuer(issuer));
        return configured;
    }

    public UUID require(String bearer) {
        if (decoder == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "AUTH_NOT_CONFIGURED",
                    "Configure a trusted phone JWT issuer and JWKS before using phone APIs.");
        }
        if (bearer == null || !bearer.startsWith("Bearer ")) throw ApiException.unauthorized();
        UUID user = validatedSubject(bearer.substring(7));
        ensureActiveAccount(user);
        return user;
    }

    private UUID validatedSubject(String token) {
        try {
            Jwt jwt = decoder.decode(token);
            if (jwt.getExpiresAt() == null || !jwt.getAudience().contains(audience)) {
                throw ApiException.unauthorized();
            }
            return UUID.fromString(jwt.getSubject());
        } catch (JwtException | IllegalArgumentException ex) {
            throw ApiException.unauthorized();
        }
    }

    private void ensureActiveAccount(UUID id) {
        db.update("""
                INSERT INTO app.app_users(id,display_name,status,default_time_zone)
                VALUES (?,?,'ACTIVE','Asia/Seoul') ON CONFLICT(id) DO NOTHING
                """, id, "사용자");
        String status = db.queryForObject("SELECT status FROM app.app_users WHERE id=?", String.class, id);
        if (!"ACTIVE".equals(status)) throw ApiException.unauthorized();
    }
}
