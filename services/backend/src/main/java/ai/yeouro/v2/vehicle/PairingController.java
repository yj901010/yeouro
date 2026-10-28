package ai.yeouro.v2.vehicle;

import ai.yeouro.v2.common.IdempotencyService;
import ai.yeouro.v2.identity.IdentityService;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2")
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
@Transactional
public class PairingController {
  private final PairingService pairing;
  private final IdentityService identity;
  private final IdempotencyService idem;

  public PairingController(
      PairingService pairing, IdentityService identity, IdempotencyService idem) {
    this.pairing = pairing;
    this.identity = identity;
    this.idem = idem;
  }

  public record StartRequest(UUID installationId, String displayLabel) {}

  public record ApprovalRequest(UUID tripId, String displayCode, List<String> scopes) {}

  @PostMapping("/vehicle-pairings")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> start(
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody StartRequest r) {
    return idem.execute(
        "installation:" + r.installationId(),
        "POST /vehicle-pairings",
        key,
        r,
        true,
        () -> pairing.start(r.installationId()));
  }

  @PostMapping("/vehicle-pairings/{id}/approve")
  public Map<String, Object> approve(
      @PathVariable UUID id,
      @RequestHeader("Authorization") String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @RequestBody ApprovalRequest r) {
    UUID user = identity.require(bearer);
    pairing.authorizeApproval(id, bearer, r.tripId());
    return idem.execute(
        user.toString(),
        "POST /vehicle-pairings/" + id + "/approve",
        key,
        r,
        false,
        () -> pairing.approve(id, bearer, r.tripId(), r.displayCode(), r.scopes()));
  }

  @GetMapping("/vehicle-pairings/{id}")
  public Map<String, Object> poll(
      @PathVariable UUID id, @RequestHeader("X-Pairing-Poll-Secret") String secret) {
    return pairing.poll(id, secret);
  }

  @PostMapping("/vehicle-pairings/{id}/claim")
  public Map<String, Object> claim(
      @PathVariable UUID id,
      @RequestHeader("X-Pairing-Poll-Secret") String secret,
      @RequestHeader(value = "Idempotency-Key", required = false) String key) {
    pairing.verifyPoll(id, secret);
    return idem.execute(
        "pairing:" + id,
        "POST /vehicle-pairings/" + id + "/claim",
        key,
        Map.of(),
        true,
        () -> pairing.claim(id, secret));
  }

  @DeleteMapping("/trips/{trip}/vehicle-pairings/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void revoke(
      @PathVariable UUID trip,
      @PathVariable UUID id,
      @RequestHeader("Authorization") String bearer) {
    pairing.revoke(trip, id, bearer);
  }
}
