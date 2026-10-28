package ai.yeouro.v2.planning;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import ai.yeouro.PostgresTestDatabase;
import ai.yeouro.v2.identity.IdentityService;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.*;

/**
 * Real PostgreSQL/MVC/services; only identity decoding is substituted with a test-only bean here
 * (covered separately by core JWT tests).
 */
@SpringBootTest(properties = {"yeouro.v2.demo-enabled=true", "yeouro.cleanup-delay-ms=3600000"})
@ActiveProfiles("v2")
@AutoConfigureMockMvc
@Import(PlanningApiIntegrationTest.IdentityTestConfiguration.class)
class PlanningApiIntegrationTest {
  @DynamicPropertySource
  static void testDatabase(DynamicPropertyRegistry properties) {
    PostgresTestDatabase.configure(properties);
  }

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  @Autowired TestIdentity identity;

  @TestConfiguration
  static class IdentityTestConfiguration {
    @Bean
    @Primary
    TestIdentity planningTestIdentity(JdbcTemplate db) {
      return new TestIdentity(db);
    }
  }

  static class TestIdentity extends IdentityService {
    UUID user;

    TestIdentity(JdbcTemplate db) {
      super(db, "", "", "authenticated");
    }

    @Override
    public UUID require(String bearer) {
      if (!"Bearer planning-user".equals(bearer)) throw ai.yeouro.api.ApiException.unauthorized();
      return user;
    }
  }

  UUID user, trip, participant, session;
  Instant now;

  @BeforeEach
  void seed() {
    user = UUID.randomUUID();
    trip = UUID.randomUUID();
    participant = UUID.randomUUID();
    session = UUID.randomUUID();
    now = Instant.now();
    identity.user = user;
    db.update(
        "INSERT INTO app.app_users(id,display_name,status,default_time_zone) VALUES"
            + " (?,'Planning','ACTIVE','Asia/Seoul')",
        user);
    db.update(
        "INSERT INTO"
            + " app.trips(id,owner_user_id,destination_name,deadline_at,arrival_basis,time_zone,status,phase,expires_at)"
            + " VALUES (?,?,'Fixture',?,'PERSON','Asia/Seoul','ACTIVE','DRIVING',?)",
        trip,
        user,
        java.sql.Timestamp.from(now.plusSeconds(5400)),
        java.sql.Timestamp.from(now.plusSeconds(86400)));
    db.update(
        "INSERT INTO app.trip_participants(id,trip_id,user_id,role,status,display_name) VALUES"
            + " (?,?,?,'DRIVER','ACTIVE','Driver')",
        participant,
        trip,
        user);
    db.update(
        "INSERT INTO"
            + " app.sharing_sessions(id,trip_id,generation,status,started_by_participant_id,started_at,expires_at)"
            + " VALUES (?,?,1,'ACTIVE',?,?,?)",
        session,
        trip,
        participant,
        java.sql.Timestamp.from(now),
        java.sql.Timestamp.from(now.plusSeconds(86400)));
    db.update(
        "INSERT INTO"
            + " app.participant_consents(id,trip_id,sharing_session_id,participant_id,scope,policy_version,granted_at)"
            + " VALUES (?,?,?,?,'TRIP_COLLABORATION','test',?)",
        UUID.randomUUID(),
        trip,
        session,
        participant,
        java.sql.Timestamp.from(now));
  }

  String path(String suffix) {
    return "/api/v2/trips/" + trip + suffix;
  }

  JsonNode send(MockHttpServletRequestBuilder request, Object body, int expected) throws Exception {
    request.header("Authorization", "Bearer planning-user");
    if (body != null)
      request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
    var result = mvc.perform(request).andExpect(status().is(expected)).andReturn();
    return json.readTree(result.getResponse().getContentAsString());
  }

  Map<String, Object> compare(String mode, long revision) {
    return Map.of(
        "mode",
        mode,
        "origin",
        Map.of("latitude", 37.5, "longitude", 127.0, "observedAt", now, "source", "PHONE"),
        "parkingDurationMinutes",
        120,
        "tripPlanRevision",
        revision);
  }

  Map<String, Object> reminder(String anchor) {
    return Map.of(
        "enabled",
        true,
        "anchor",
        anchor,
        "stayMinutes",
        90,
        "leadMinutes",
        10,
        "reminderBasis",
        "RETURN_TO_CAR",
        "returnWalkSeconds",
        300);
  }

  @Test
  void demoSnapshotIsImmutableExplicitAndRealUnavailable() throws Exception {
    JsonNode snap =
        send(
            post(path("/comparisons")).header("Idempotency-Key", "compare"),
            compare("DEMO", 1),
            201);
    assertEquals("DEMO", snap.path("mode").asText());
    assertEquals("UNKNOWN", snap.at("/candidates/0/occupancy/availability").asText());
    assertTrue(snap.at("/candidates/0/occupancy/availableSpaces").isNull());
    assertEquals(-900, snap.at("/candidates/0/slackSeconds").asLong(), 2);
    assertEquals(6000, snap.at("/candidates/0/fee/estimatedAmount").asLong());
    JsonNode replay =
        send(
            post(path("/comparisons")).header("Idempotency-Key", "compare"),
            compare("DEMO", 1),
            201);
    assertEquals(snap, replay);
    send(post(path("/comparisons")).header("Idempotency-Key", "real"), compare("REAL", 1), 503);
    send(put(path("/stops")).header("If-Match", "\"plan-1\""), Map.of("stops", List.of()), 200);
    JsonNode read =
        send(get(path("/comparisons/" + snap.path("comparisonId").asText())), null, 200);
    assertTrue(read.path("stalePlan").asBoolean());
    assertEquals(snap, read.path("snapshot"));
    send(post(path("/comparisons")).header("Idempotency-Key", "stale"), compare("DEMO", 1), 409);
  }

  @Test
  void stopsRequireEtagAndPreserveSeparateUnknownReturnWalk() throws Exception {
    var stop =
        Map.of("place", Map.of("name", "Cafe"), "stayMinutes", 20, "walkToPlaceSeconds", 120);
    send(put(path("/stops")), Map.of("stops", List.of(stop)), 428);
    send(put(path("/stops")).header("If-Match", "\"plan-0\""), Map.of("stops", List.of(stop)), 412);
    send(put(path("/stops")).header("If-Match", "\"plan-1\""), Map.of("stops", List.of(stop)), 200);
    JsonNode snap =
        send(
            post(path("/comparisons")).header("Idempotency-Key", "unknown"),
            compare("DEMO", 2),
            201);
    assertTrue(snap.at("/candidates/0/personArrivalAt").isNull());
    assertTrue(snap.at("/candidates/0/intermediateSeconds").isNull());
  }

  @Test
  void confirmedParkingAnchorsLocalReminderButSdkArrivalCannot() throws Exception {
    JsonNode plan =
        send(
            put(path("/return-reminder-plan")).header("If-Match", "\"plan-1\""),
            reminder("PARKING_CONFIRMED"),
            200);
    assertEquals("WAITING_FOR_ANCHOR", plan.path("status").asText());
    assertTrue(plan.path("remindAt").isNull());
    var invalid =
        Map.of(
            "parkedAt",
            now,
            "place",
            Map.of("name", "Lot"),
            "source",
            "SDK_EVENT",
            "userConfirmed",
            true,
            "expectedVersion",
            2);
    send(post(path("/parking-confirmations")).header("Idempotency-Key", "sdk"), invalid, 400);
    var valid =
        Map.of(
            "parkedAt",
            now,
            "place",
            Map.of("name", "Lot"),
            "source",
            "USER_CONFIRMED",
            "userConfirmed",
            true,
            "expectedVersion",
            2);
    send(post(path("/parking-confirmations")).header("Idempotency-Key", "park"), valid, 201);
    JsonNode updated = send(get(path("/return-reminder-plan")), null, 200);
    assertEquals("PLANNED", updated.path("status").asText());
    assertEquals("NONE", updated.path("deliveryGuarantee").asText());
    assertEquals(
        now.plusSeconds(4500).toEpochMilli(),
        Instant.parse(updated.path("remindAt").asText()).toEpochMilli());
    var staleStatus =
        Map.of(
            "planVersion",
            1,
            "installationId",
            UUID.randomUUID(),
            "status",
            "SCHEDULED",
            "observedAt",
            now);
    send(put(path("/return-reminder-plan/client-status")), staleStatus, 409);
    var validStatus =
        Map.of(
            "planVersion",
            updated.path("version").asLong(),
            "installationId",
            UUID.randomUUID(),
            "status",
            "SCHEDULED",
            "observedAt",
            now);
    JsonNode scheduled = send(put(path("/return-reminder-plan/client-status")), validStatus, 200);
    assertEquals("SCHEDULED", scheduled.at("/clientStatuses/0/status").asText());
    assertEquals("NONE", scheduled.path("deliveryGuarantee").asText());
  }

  @Test
  void scopesTerminalTripsAndPayloadReuseFailClosed() throws Exception {
    send(post(path("/comparisons")).header("Idempotency-Key", "same"), compare("DEMO", 1), 201);
    send(post(path("/comparisons")).header("Idempotency-Key", "same"), compare("REAL", 1), 409);
    db.update("UPDATE app.trip_participants SET role='COMPANION' WHERE id=?", participant);
    send(
        post(path("/parking-confirmations")).header("Idempotency-Key", "forbidden"),
        Map.of(
            "parkedAt",
            now,
            "place",
            Map.of("name", "Lot"),
            "source",
            "USER_CONFIRMED",
            "userConfirmed",
            true,
            "expectedVersion",
            1),
        403);
    db.update("UPDATE app.trips SET status='ENDED' WHERE id=?", trip);
    send(put(path("/stops")).header("If-Match", "\"plan-1\""), Map.of("stops", List.of()), 409);
  }

  @Test
  void personArrivalIsSeparateAndPreviousLegAnchorIsNotReused() throws Exception {
    send(
        put(path("/return-reminder-plan")).header("If-Match", "\"plan-1\""),
        reminder("PERSON_ARRIVAL_CONFIRMED"),
        200);
    send(
        post(path("/parking-confirmations")).header("Idempotency-Key", "park"),
        Map.of(
            "parkedAt",
            now,
            "place",
            Map.of("name", "Lot"),
            "source",
            "USER_CONFIRMED",
            "userConfirmed",
            true,
            "expectedVersion",
            2),
        201);
    assertEquals(
        "WAITING_FOR_ANCHOR",
        send(get(path("/return-reminder-plan")), null, 200).path("status").asText());
    send(
        post(path("/phase-events")).header("Idempotency-Key", "arrive"),
        Map.of(
            "phase",
            "AT_DESTINATION",
            "observedAt",
            now.plusSeconds(1),
            "source",
            "USER_CONFIRMED",
            "userConfirmed",
            true,
            "expectedVersion",
            3),
        201);
    assertEquals(
        "PLANNED", send(get(path("/return-reminder-plan")), null, 200).path("status").asText());
    send(
        post(path("/phase-events")).header("Idempotency-Key", "return"),
        Map.of(
            "phase",
            "RETURNING",
            "observedAt",
            now.plusSeconds(2),
            "source",
            "USER_CONFIRMED",
            "userConfirmed",
            true,
            "expectedVersion",
            4),
        201);
    send(
        post(path("/phase-events")).header("Idempotency-Key", "drive"),
        Map.of(
            "phase",
            "DRIVING",
            "observedAt",
            now.plusSeconds(3),
            "source",
            "USER_CONFIRMED",
            "userConfirmed",
            true,
            "expectedVersion",
            5),
        201);
    assertEquals(
        "WAITING_FOR_ANCHOR",
        send(get(path("/return-reminder-plan")), null, 200).path("status").asText());
  }
}
