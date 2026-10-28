package ai.yeouro.v2.planning;

import static ai.yeouro.v2.planning.PlanningModels.Mode;

import ai.yeouro.api.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** Provider boundary. This implementation intentionally has no real network adapter. */
@Component
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class PlanningRoutingProvider {
  public record Estimate(
      long drivingSeconds,
      long parkingSearchSeconds,
      long walkSeconds,
      long returnWalkSeconds,
      String provider,
      String source) {}

  private final boolean demoEnabled;

  public PlanningRoutingProvider(@Value("${yeouro.v2.demo-enabled:false}") boolean demoEnabled) {
    this.demoEnabled = demoEnabled;
  }

  public Estimate route(Mode mode) {
    if (mode == Mode.REAL)
      throw new ApiException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "ROUTING_UNAVAILABLE",
          "No real routing provider is connected.");
    if (!demoEnabled)
      throw new ApiException(
          HttpStatus.FORBIDDEN, "DEMO_DISABLED", "Synthetic comparisons are disabled.");
    return new Estimate(5700, 240, 360, 360, "FIXTURE", "SIMULATED");
  }
}
