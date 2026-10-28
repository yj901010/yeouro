package ai.yeouro.v2.planning;

import static ai.yeouro.v2.planning.PlanningModels.*;

import jakarta.validation.Valid;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v2/trips/{trip}")
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class PlanningController {
  private final StopsService stops;
  private final ComparisonService comparisons;
  private final ProgressService progress;
  private final ReminderService reminders;

  public PlanningController(
      StopsService stops,
      ComparisonService comparisons,
      ProgressService progress,
      ReminderService reminders) {
    this.stops = stops;
    this.comparisons = comparisons;
    this.progress = progress;
    this.reminders = reminders;
  }

  @PutMapping("/stops")
  public Map<String, Object> stops(
      @PathVariable UUID trip,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "If-Match", required = false) String etag,
      @Valid @RequestBody StopsRequest body) {
    return stops.replace(trip, auth, etag, body);
  }

  @PostMapping("/comparisons")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> compare(
      @PathVariable UUID trip,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody ComparisonRequest body) {
    return comparisons.create(trip, auth, key, body);
  }

  @GetMapping("/comparisons/{id}")
  public Map<String, Object> comparison(
      @PathVariable UUID trip,
      @PathVariable UUID id,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    return comparisons.get(trip, id, auth);
  }

  @PostMapping("/parking-confirmations")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> park(
      @PathVariable UUID trip,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody ParkingRequest body) {
    return progress.parking(trip, auth, key, body);
  }

  @PostMapping("/phase-events")
  @ResponseStatus(HttpStatus.CREATED)
  public Map<String, Object> phase(
      @PathVariable UUID trip,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "Idempotency-Key", required = false) String key,
      @Valid @RequestBody PhaseRequest body) {
    return progress.phase(trip, auth, key, body);
  }

  @PutMapping("/return-reminder-plan")
  public Map<String, Object> reminder(
      @PathVariable UUID trip,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @RequestHeader(value = "If-Match", required = false) String etag,
      @Valid @RequestBody ReminderRequest body) {
    return reminders.put(trip, auth, etag, body);
  }

  @GetMapping("/return-reminder-plan")
  public Map<String, Object> reminder(
      @PathVariable UUID trip,
      @RequestHeader(value = "Authorization", required = false) String auth) {
    return reminders.get(trip, auth);
  }

  @PutMapping("/return-reminder-plan/client-status")
  public Map<String, Object> clientStatus(
      @PathVariable UUID trip,
      @RequestHeader(value = "Authorization", required = false) String auth,
      @Valid @RequestBody ClientStatusRequest body) {
    return reminders.clientStatus(trip, auth, body);
  }
}
