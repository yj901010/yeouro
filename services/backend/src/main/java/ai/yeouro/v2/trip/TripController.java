package ai.yeouro.v2.trip;

import ai.yeouro.v2.trip.TripModels.*;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController("v2TripController")
@RequestMapping("/api/v2")
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class TripController {
  private final TripService service;

  public TripController(TripService service) {
    this.service = service;
  }

  @GetMapping("/me")
  public Map<String, Object> me(
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    return service.me(bearer);
  }

  @GetMapping("/trips")
  public Map<String, Object> list(
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @RequestParam(required = false) String status) {
    return service.list(bearer, status);
  }

  @PostMapping("/trips")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> create(
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Plan body) {
    return service.create(bearer, key, body);
  }

  @GetMapping("/trips/{id}")
  public ResponseEntity<Map<String, Object>> get(
      @PathVariable UUID id,
      @RequestHeader(value = "Authorization", required = false) String bearer) {
    var result = service.get(id, bearer);
    return ResponseEntity.ok().eTag("\"plan-" + result.get("planRevision") + "\"").body(result);
  }

  @PatchMapping("/trips/{id}")
  public Map<String, Object> patch(
      @PathVariable UUID id,
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @RequestHeader(value = "If-Match", required = false) String etag,
      @Valid @RequestBody Plan body) {
    return service.update(id, bearer, etag, body);
  }

  @PostMapping("/trips/{id}/{action:start|end|cancel}")
  public Map<String, Object> transition(
      @PathVariable UUID id,
      @PathVariable String action,
      @RequestHeader(value = "Authorization", required = false) String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Transition body) {
    return service.transition(id, bearer, key, action, body);
  }
}
