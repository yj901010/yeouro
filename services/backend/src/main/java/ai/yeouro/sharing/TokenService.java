package ai.yeouro.sharing;

import ai.yeouro.api.*;
import ai.yeouro.api.ApiModels.*;
import ai.yeouro.trip.TripEntity;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

@Service
public class TokenService {
    private final AccessTokenRepository tokens;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    public TokenService(AccessTokenRepository tokens, Clock clock) { this.tokens = tokens; this.clock = clock; }
    public String issue(TripEntity trip, UUID participantId, Role role) {
        String raw = randomSecret(32);
        tokens.save(new AccessToken(hash(raw), trip.id, participantId, role, trip.expiresAt));
        return raw;
    }
    public AccessToken authorize(TripEntity trip, String header, Role... allowed) {
        if (header == null || !header.startsWith("Bearer ") || header.length() > 128) throw ApiException.unauthorized();
        AccessToken token = tokens.findById(hash(header.substring(7))).orElseThrow(ApiException::unauthorized);
        if (token.revoked || !token.expiresAt.isAfter(clock.instant())) throw ApiException.unauthorized();
        if (!token.tripId.equals(trip.id)) throw ApiException.forbidden();
        if (token.role != Role.OWNER && (!trip.sharingEnabled || trip.status == TripStatus.ENDED)) throw ApiException.unauthorized();
        if (allowed.length > 0 && !Arrays.asList(allowed).contains(token.role)) throw ApiException.forbidden();
        return token;
    }
    public void revokeParticipants(UUID tripId) {
        tokens.findByTripId(tripId).stream().filter(t -> t.role != Role.OWNER).forEach(t -> t.revoked = true);
    }
    public String randomSecret(int bytes) {
        byte[] buffer = new byte[bytes]; random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
    public static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }
}
