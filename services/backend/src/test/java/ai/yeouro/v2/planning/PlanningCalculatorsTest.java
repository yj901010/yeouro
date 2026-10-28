package ai.yeouro.v2.planning;

import static ai.yeouro.v2.planning.PlanningModels.*;
import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlanningCalculatorsTest {
  final Instant start = Instant.parse("2026-10-28T08:00:00Z");

  @Test
  void personAndVehicleHaveDifferentDeadlineSlackIncludingLateNegative() {
    var person =
        PlanningCalculators.eta(start, 600L, 0L, 120L, 180L, start.plusSeconds(800), "PERSON");
    var vehicle =
        PlanningCalculators.eta(start, 600L, 0L, 120L, 180L, start.plusSeconds(800), "VEHICLE");
    assertEquals(-100L, person.slackSeconds());
    assertEquals(200L, vehicle.slackSeconds());
    assertEquals(start.plusSeconds(900), person.personArrivalAt());
  }

  @Test
  void outboundReturnAndStayAddedExactlyOnce() {
    var stop = new Stop(new Place("Cafe", null, null), 20, 120, 300);
    Long intermediate = PlanningCalculators.intermediateSeconds(List.of(stop));
    assertEquals(1620L, intermediate);
    var eta = PlanningCalculators.eta(start, 600L, intermediate, 60L, 120L, null, "PERSON");
    assertEquals(start.plusSeconds(2220), eta.vehicleArrivalAt());
    assertNull(eta.slackSeconds());
  }

  @Test
  void missingWalkDoesNotBecomeZeroOrCopyOutboundWalk() {
    assertNull(
        PlanningCalculators.intermediateSeconds(
            List.of(new Stop(new Place("Cafe", null, null), 5, 120, null))));
    var eta =
        PlanningCalculators.eta(start, 600L, 0L, 60L, null, start.plusSeconds(1000), "PERSON");
    assertNull(eta.personArrivalAt());
    assertNull(eta.slackSeconds());
    assertEquals("PARTIAL", eta.quality());
  }

  @Test
  void tariffUsesExplicitParkingDuration() {
    assertEquals(500, PlanningCalculators.demoFee(1));
    assertEquals(1000, PlanningCalculators.demoFee(11));
    assertEquals(6000, PlanningCalculators.demoFee(120));
  }

  @Test
  void unconfirmedAnchorWaitsAndReturnWalkIsIndependent() {
    assertNull(PlanningCalculators.reminder(null, 90, 10, ReminderBasis.STAY_END, null));
    assertEquals(
        start.plusSeconds(4500),
        PlanningCalculators.reminder(start, 90, 10, ReminderBasis.RETURN_TO_CAR, 300));
    assertThrows(
        IllegalArgumentException.class,
        () -> PlanningCalculators.reminder(start, 90, 10, ReminderBasis.RETURN_TO_CAR, null));
    assertThrows(
        IllegalArgumentException.class,
        () -> PlanningCalculators.reminder(start, 5, 10, ReminderBasis.STAY_END, null));
  }

  @Test
  void navigationArrivalCannotConfirmParkingOrPersonArrival() {
    assertThrows(
        ai.yeouro.api.ApiException.class,
        () -> PlanningPolicy.confirmation(Source.SDK_EVENT, true));
    assertThrows(
        ai.yeouro.api.ApiException.class,
        () -> PlanningPolicy.confirmation(Source.SIMULATED, true));
    assertThrows(
        ai.yeouro.api.ApiException.class,
        () -> PlanningPolicy.confirmation(Source.USER_CONFIRMED, false));
    assertDoesNotThrow(() -> PlanningPolicy.confirmation(Source.USER_CONFIRMED, true));
  }

  @Test
  void providerNeverSilentlyUsesFixtureForRealMode() {
    var provider = new PlanningRoutingProvider(true);
    var error = assertThrows(ai.yeouro.api.ApiException.class, () -> provider.route(Mode.REAL));
    assertEquals("ROUTING_UNAVAILABLE", error.code);
    assertEquals("SIMULATED", provider.route(Mode.DEMO).source());
    assertEquals(
        "DEMO_DISABLED",
        assertThrows(
                ai.yeouro.api.ApiException.class,
                () -> new PlanningRoutingProvider(false).route(Mode.DEMO))
            .code);
  }

  @Test
  void phaseCannotSkipActualParkingOrReverseProgress() {
    assertThrows(
        ai.yeouro.api.ApiException.class,
        () -> ProgressService.transition("DRIVING", Phase.AT_DESTINATION));
    assertDoesNotThrow(() -> ProgressService.transition("DRIVING", Phase.PARKED));
    assertDoesNotThrow(() -> ProgressService.transition("RETURNING", Phase.DRIVING));
  }
}
