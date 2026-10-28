package ai.yeouro.v2.sharing;

import ai.yeouro.v2.common.IdempotencyService;
import ai.yeouro.v2.identity.IdentityService;
import ai.yeouro.v2.trip.TripAccess;
import ai.yeouro.v2.trip.TripAccess.Permission;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2")
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
@Transactional
public class SharingController {
  private final SharingService sharing;
  private final LocationService location;
  private final TripAccess access;
  private final IdentityService identity;
  private final IdempotencyService idem;

  public SharingController(
      SharingService sharing,
      LocationService location,
      TripAccess access,
      IdentityService identity,
      IdempotencyService idem) {
    this.sharing = sharing;
    this.location = location;
    this.access = access;
    this.identity = identity;
    this.idem = idem;
  }

  public record SessionRequest(List<String> scopes, String consentPolicyVersion) {}

  public record InviteRequest(String role, UUID sessionId, Integer expiresInSeconds) {}

  public record JoinRequest(String code, List<String> consentScopes, String consentPolicyVersion) {}

  public record ConsentRequest(
      Map<String, Boolean> scopes, String consentPolicyVersion, Long expectedVersion) {}

  public record ReadinessRequest(boolean ready, Instant observedAt) {}

  @PostMapping("/trips/{trip}/sharing-sessions")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> start(
      @PathVariable UUID trip,
      @RequestHeader("Authorization") String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody SessionRequest r) {
    var c = access.require(trip, bearer, Permission.OWNER);
    return idem.execute(
        c.userId().toString(),
        "POST /trips/" + trip + "/sharing-sessions",
        key,
        r,
        false,
        () -> sharing.start(trip, bearer, r.scopes(), r.consentPolicyVersion()));
  }

  @PostMapping("/trips/{trip}/invites")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> invite(
      @PathVariable UUID trip,
      @RequestHeader("Authorization") String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody InviteRequest r) {
    var c = access.require(trip, bearer, Permission.OWNER);
    return idem.execute(
        c.userId().toString(),
        "POST /trips/" + trip + "/invites",
        key,
        r,
        true,
        () ->
            sharing.invite(
                trip,
                bearer,
                r.role(),
                r.sessionId(),
                r.expiresInSeconds() == null ? 900 : r.expiresInSeconds()));
  }

  @PostMapping("/invites/join")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> join(
      @RequestHeader("Authorization") String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody JoinRequest r) {
    UUID user = identity.require(bearer);
    sharing.authorizeJoinReplay(user, r.code());
    return idem.execute(
        user.toString(),
        "POST /invites/join",
        key,
        r,
        false,
        () -> sharing.join(bearer, r.code(), r.consentScopes(), r.consentPolicyVersion()));
  }

  @PostMapping("/trips/{trip}/sharing/revoke")
  public Map<String, Object> revoke(
      @PathVariable UUID trip,
      @RequestHeader("Authorization") String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    var c = access.require(trip, bearer, Permission.OWNER);
    return idem.execute(
        c.userId().toString(),
        "POST /trips/" + trip + "/sharing/revoke",
        key,
        Map.of(),
        false,
        () -> sharing.revoke(trip, bearer));
  }

  @GetMapping("/trips/{trip}/consents/me")
  public Map<String, Object> ownConsents(
      @PathVariable UUID trip, @RequestHeader("Authorization") String bearer) {
    return sharing.ownConsents(trip, bearer);
  }

  @PutMapping("/trips/{trip}/consents/me")
  public Map<String, Object> consent(
      @PathVariable UUID trip,
      @RequestHeader("Authorization") String bearer,
      @RequestBody ConsentRequest r) {
    return sharing.consent(trip, bearer, r.scopes(), r.consentPolicyVersion(), r.expectedVersion());
  }

  @DeleteMapping("/trips/{trip}/memberships/me")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void leave(@PathVariable UUID trip, @RequestHeader("Authorization") String bearer) {
    sharing.leave(trip, bearer);
  }

  @PutMapping("/trips/{trip}/readiness/me")
  public Map<String, Object> readiness(
      @PathVariable UUID trip,
      @RequestHeader("Authorization") String bearer,
      @RequestBody ReadinessRequest r) {
    return sharing.readiness(trip, bearer, r.ready(), r.observedAt());
  }

  @PutMapping("/trips/{trip}/location")
  public Map<String, Object> location(
      @PathVariable UUID trip,
      @RequestHeader("Authorization") String bearer,
      @RequestBody LocationService.Observation r) {
    return location.publish(trip, bearer, r);
  }
}
