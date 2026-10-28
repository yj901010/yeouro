package ai.yeouro.v2.navigation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/trips/{tripId}")
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class NavigationController {
  private final NavigationService service;

  public NavigationController(NavigationService service) {
    this.service = service;
  }

  public record Place(@NotBlank @Size(max = 120) String name, Double latitude, Double longitude) {}

  public record Proposal(@NotBlank String kind, @NotNull @Valid Place place) {}

  public record Decision(
      @NotBlank String decision, @NotNull @Min(0) Long expectedVersion, String mode) {}

  public record Claim(@NotNull @Min(0) Long expectedVersion) {}

  public record Result(@NotNull @Min(0) Long expectedVersion, @NotBlank String outcome) {}

  @GetMapping("/proposals")
  public Map<String, Object> proposals(
      @PathVariable UUID tripId,
      @RequestHeader("Authorization") String bearer,
      @RequestParam(defaultValue = "50") int limit,
      @RequestParam(required = false) UUID cursor,
      @RequestParam(required = false) String status) {
    return service.proposals(tripId, bearer, limit, cursor, status);
  }

  @PostMapping("/proposals")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> propose(
      @PathVariable UUID tripId,
      @RequestHeader("Authorization") String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Proposal body) {
    return service.propose(tripId, bearer, key, body);
  }

  @PostMapping("/proposals/{proposalId}/decision")
  public Map<String, Object> decide(
      @PathVariable UUID tripId,
      @PathVariable UUID proposalId,
      @RequestHeader("Authorization") String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Decision body) {
    return service.decide(tripId, proposalId, bearer, key, body);
  }

  @GetMapping("/navigation-commands")
  public Map<String, Object> commands(
      @PathVariable UUID tripId,
      @RequestHeader("Authorization") String bearer,
      @RequestParam(defaultValue = "REQUESTED") String status) {
    return service.commands(tripId, bearer, status);
  }

  @PostMapping("/navigation-commands/{commandId}/claim")
  public Map<String, Object> claim(
      @PathVariable UUID tripId,
      @PathVariable UUID commandId,
      @RequestHeader("Authorization") String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Claim body) {
    return service.claim(tripId, commandId, bearer, key, body);
  }

  @PostMapping("/navigation-commands/{commandId}/result")
  public Map<String, Object> result(
      @PathVariable UUID tripId,
      @PathVariable UUID commandId,
      @RequestHeader("Authorization") String bearer,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody Result body) {
    return service.result(tripId, commandId, bearer, key, body);
  }
}
