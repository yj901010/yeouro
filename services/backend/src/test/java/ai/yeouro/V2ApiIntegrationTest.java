package ai.yeouro;

import static org.assertj.core.api.Assertions.*;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP, RSA/JWKS validation and PostgreSQL constraints; never an H2 fallback. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "yeouro.v2.demo-enabled=true")
@ActiveProfiles("v2")
class V2ApiIntegrationTest {
  private static final String ISSUER = "https://synthetic-issuer.invalid";
  private static final RSAKey KEY = key("test-signing-key");
  private static final HttpServer JWKS = jwks();
  private static final Map<String, Object> PLACE =
      Map.of("name", "Synthetic test destination", "latitude", 1.25, "longitude", 2.5);
  private static final List<String> DRIVER_SCOPES =
      List.of("TRIP_COLLABORATION", "LOCATION_PUBLISH", "LOCATION_VIEW");
  private static final List<String> MEMBER_SCOPES = List.of("TRIP_COLLABORATION");
  @LocalServerPort int port;
  @Autowired JdbcTemplate db;
  @Autowired ObjectMapper json;
  private final HttpClient http = HttpClient.newHttpClient();

  private static RSAKey key(String kid) {
    try {
      return new RSAKeyGenerator(2048).keyID(kid).generate();
    } catch (JOSEException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static HttpServer jwks() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] payload = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/jwks",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            try (var output = exchange.getResponseBody()) {
              output.write(payload);
            }
          });
      server.start();
      return server;
    } catch (Exception e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void configuration(DynamicPropertyRegistry properties) {
    PostgresTestDatabase.configure(properties);
    properties.add("yeouro.v2.auth.issuer", () -> ISSUER);
    properties.add(
        "yeouro.v2.auth.jwk-set-uri",
        () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/jwks");
    properties.add("yeouro.v2.auth.audience", () -> "authenticated");
  }

  @AfterAll
  static void closeJwks() {
    JWKS.stop(0);
  }

  @BeforeEach
  void clearSyntheticFixtures() {
    assertThat(db.queryForObject("SELECT version()", String.class)).startsWith("PostgreSQL");
    db.execute("TRUNCATE app.app_users CASCADE");
    db.execute("TRUNCATE app.idempotency_records");
    db.execute("TRUNCATE app.vehicle_pairings CASCADE");
  }

  String token(UUID user) throws Exception {
    return token(user, ISSUER, "authenticated", Instant.now().plusSeconds(600), KEY);
  }

  String token(UUID user, String issuer, String audience, Instant expiry, RSAKey key)
      throws Exception {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
            new JWTClaimsSet.Builder()
                .subject(user.toString())
                .issuer(issuer)
                .audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(300)))
                .expirationTime(Date.from(expiry))
                .build());
    jwt.sign(new RSASSASigner(key));
    return jwt.serialize();
  }

  record Reply(int status, JsonNode body, String raw) {}

  Reply request(String method, String path, String token, Object body, String idempotency)
      throws Exception {
    return request(method, path, token, body, idempotency, null);
  }

  Reply request(
      String method, String path, String token, Object body, String idempotency, String etag)
      throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/v2" + path));
    if (token != null && !token.startsWith("POLL:"))
      builder.header("Authorization", "Bearer " + token);
    if (idempotency != null) builder.header("Idempotency-Key", idempotency);
    if (etag != null) builder.header("If-Match", etag);
    if (token != null && token.startsWith("POLL:")) {
      builder.setHeader("X-Pairing-Poll-Secret", token.substring(5));
    }
    builder.header("Content-Type", "application/json");
    builder.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
    HttpResponse<String> response =
        http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
    return new Reply(
        response.statusCode(),
        response.body().isBlank() ? json.nullNode() : json.readTree(response.body()),
        response.body());
  }

  JsonNode call(String method, String path, String token, Object body, int expected)
      throws Exception {
    Reply reply = request(method, path, token, body, UUID.randomUUID().toString());
    assertThat(reply.status()).as("%s %s: %s", method, path, reply.raw()).isEqualTo(expected);
    return reply.body();
  }

  @Test
  void signedJwtRequiredAndIssuerAudienceExpiryAndSignatureAreChecked() throws Exception {
    UUID user = UUID.randomUUID();
    call("GET", "/me", token(user), null, 200);
    call("GET", "/me", null, null, 401);
    call("GET", "/me", "not-a-jwt", null, 401);
    call(
        "GET",
        "/me",
        token(user, "https://other.invalid", "authenticated", Instant.now().plusSeconds(600), KEY),
        null,
        401);
    call(
        "GET",
        "/me",
        token(user, ISSUER, "other-app", Instant.now().plusSeconds(600), KEY),
        null,
        401);
    call(
        "GET",
        "/me",
        token(user, ISSUER, "authenticated", Instant.now().minusSeconds(120), KEY),
        null,
        401);
    call(
        "GET",
        "/me",
        token(user, ISSUER, "authenticated", Instant.now().plusSeconds(600), key("other-key")),
        null,
        401);
  }

  record Trip(String id, String owner, JsonNode body) {}

  Trip trip(String role) throws Exception {
    String owner = token(UUID.randomUUID());
    JsonNode body = call("POST", "/trips", owner, plan(role), 201);
    return new Trip(body.path("id").asText(), owner, body);
  }

  Map<String, Object> plan(String role) {
    return Map.of(
        "destination",
        PLACE,
        "timeZone",
        "Asia/Seoul",
        "ownerRole",
        role,
        "arrivalBasis",
        "PERSON",
        "plannedStayMinutes",
        60);
  }

  JsonNode share(Trip trip, List<String> scopes) throws Exception {
    return call(
        "POST",
        "/trips/" + trip.id() + "/sharing-sessions",
        trip.owner(),
        Map.of("scopes", scopes, "consentPolicyVersion", "test-policy-v1"),
        201);
  }

  JsonNode invite(Trip trip, JsonNode sharing, String role) throws Exception {
    return call(
        "POST",
        "/trips/" + trip.id() + "/invites",
        trip.owner(),
        Map.of(
            "role", role, "sessionId", sharing.path("sessionId").asText(), "expiresInSeconds", 600),
        201);
  }

  Map<String, Object> joinBody(String code, List<String> scopes) {
    return Map.of("code", code, "consentScopes", scopes, "consentPolicyVersion", "test-policy-v1");
  }

  String member(Trip trip, JsonNode sharing, String role, List<String> scopes) throws Exception {
    String member = token(UUID.randomUUID());
    call(
        "POST",
        "/invites/join",
        member,
        joinBody(invite(trip, sharing, role).path("code").asText(), scopes),
        201);
    return member;
  }

  Map<String, Object> observation(long generation, String source) {
    return Map.of(
        "observationId",
        UUID.randomUUID().toString(),
        "sharingGeneration",
        generation,
        "latitude",
        1.5,
        "longitude",
        2.75,
        "accuracyMeters",
        10.0,
        "observedAt",
        Instant.now().toString(),
        "source",
        source,
        "publisherSequence",
        1);
  }

  long version(Trip trip) {
    return db.queryForObject(
        "SELECT version FROM app.trips WHERE id=?", Long.class, UUID.fromString(trip.id()));
  }

  @Test
  void createRetriesDoNotDuplicateAndChangedPayloadConflicts() throws Exception {
    String owner = token(UUID.randomUUID());
    String key = UUID.randomUUID().toString();
    Reply first = request("POST", "/trips", owner, plan("DRIVER"), key);
    assertThat(first.status()).isEqualTo(201);
    Reply replay = request("POST", "/trips", owner, plan("DRIVER"), key);
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(first.body());
    Reply changed = request("POST", "/trips", owner, plan("COMPANION"), key);
    assertThat(changed.status()).isEqualTo(409);
    assertThat(db.queryForObject("SELECT count(*) FROM app.trips", Integer.class)).isEqualTo(1);
    assertThat(request("POST", "/trips", owner, plan("DRIVER"), null).status()).isEqualTo(400);
  }

  @Test
  void ownerCompanionCannotPublishButInvitedDriverCanAndLocationIsConsentProjected()
      throws Exception {
    Trip trip = trip("COMPANION");
    JsonNode sharing = share(trip, MEMBER_SCOPES);
    String driver = member(trip, sharing, "DRIVER", DRIVER_SCOPES);
    String companion = member(trip, sharing, "COMPANION", MEMBER_SCOPES);
    String path = "/trips/" + trip.id();
    call("PUT", path + "/location", trip.owner(), observation(1, "PHONE"), 403);
    call("PUT", path + "/location", driver, observation(1, "PHONE"), 200);
    assertThat(call("GET", path, companion, null, 200).path("location").isNull()).isTrue();
    assertThat(call("GET", path, trip.owner(), null, 200).path("location").isNull()).isTrue();
    assertThat(call("GET", path, driver, null, 200).path("location").path("latitude").asDouble())
        .isEqualTo(1.5);
    call("POST", path + "/sharing/revoke", driver, null, 403);
    call("POST", path + "/cancel", driver, Map.of("expectedVersion", version(trip)), 403);
    call("POST", path + "/start", driver, Map.of("expectedVersion", version(trip)), 200);
    call("POST", path + "/end", companion, Map.of("expectedVersion", version(trip)), 403);
    call("POST", path + "/end", driver, Map.of("expectedVersion", version(trip)), 200);
    assertThat(db.queryForObject("SELECT count(*) FROM app.trip_latest_locations", Integer.class))
        .isZero();
    Reply late = request("PUT", path + "/location", driver, observation(1, "PHONE"), null);
    assertThat(late.status()).isIn(403, 404, 409);
  }

  @Test
  void simultaneousInviteRedemptionHasOnlyOneWinnerAndNoCrossTripAccess() throws Exception {
    Trip trip = trip("DRIVER");
    JsonNode sharing = share(trip, DRIVER_SCOPES);
    String code = invite(trip, sharing, "COMPANION").path("code").asText();
    String first = token(UUID.randomUUID());
    String second = token(UUID.randomUUID());
    try (var executor = Executors.newFixedThreadPool(2)) {
      var tasks =
          List.<Callable<Reply>>of(
              () ->
                  request(
                      "POST",
                      "/invites/join",
                      first,
                      joinBody(code, MEMBER_SCOPES),
                      UUID.randomUUID().toString()),
              () ->
                  request(
                      "POST",
                      "/invites/join",
                      second,
                      joinBody(code, MEMBER_SCOPES),
                      UUID.randomUUID().toString()));
      var replies = executor.invokeAll(tasks);
      List<Integer> statuses =
          List.of(replies.get(0).get().status(), replies.get(1).get().status());
      assertThat(statuses.stream().filter(s -> s == 201).count()).isEqualTo(1);
      assertThat(statuses).allMatch(s -> s == 201 || s == 403 || s == 409);
    }
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM app.trip_participants WHERE trip_id=?",
                Integer.class,
                UUID.fromString(trip.id())))
        .isEqualTo(2);
    Trip other = trip("DRIVER");
    call("GET", "/trips/" + trip.id(), other.owner(), null, 404);
    JsonNode otherSharing = share(other, DRIVER_SCOPES);
    UUID participant =
        db.queryForObject(
            "SELECT id FROM app.trip_participants WHERE trip_id=? AND role='DRIVER'",
            UUID.class,
            UUID.fromString(trip.id()));
    assertThatThrownBy(
            () ->
                db.update(
                    "INSERT INTO"
                        + " app.participant_consents(id,trip_id,sharing_session_id,participant_id,scope,policy_version,granted_at)"
                        + " VALUES(?,?,?,?,?,'test',now())",
                    UUID.randomUUID(),
                    UUID.fromString(trip.id()),
                    UUID.fromString(otherSharing.path("sessionId").asText()),
                    participant,
                    "LOCATION_VIEW"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void inviteSecretCannotReplayAndConsumedJoinCanReplayOnlyWhileAuthorized() throws Exception {
    Trip trip = trip("DRIVER");
    JsonNode sharing = share(trip, DRIVER_SCOPES);
    String path = "/trips/" + trip.id();
    String inviteKey = UUID.randomUUID().toString();
    Map<String, Object> inviteBody =
        Map.of(
            "role",
            "COMPANION",
            "sessionId",
            sharing.path("sessionId").asText(),
            "expiresInSeconds",
            600);
    Reply issued = request("POST", path + "/invites", trip.owner(), inviteBody, inviteKey);
    assertThat(issued.status()).isEqualTo(201);
    Reply secretReplay = request("POST", path + "/invites", trip.owner(), inviteBody, inviteKey);
    assertThat(secretReplay.status()).isEqualTo(409);
    assertThat(secretReplay.raw()).doesNotContain(issued.body().path("code").asText());
    String companion = token(UUID.randomUUID());
    var body = joinBody(issued.body().path("code").asText(), MEMBER_SCOPES);
    String joinKey = UUID.randomUUID().toString();
    Reply joined = request("POST", "/invites/join", companion, body, joinKey);
    assertThat(joined.status()).isEqualTo(201);
    assertThat(request("POST", "/invites/join", companion, body, joinKey).body())
        .isEqualTo(joined.body());
    call("DELETE", path + "/memberships/me", companion, null, 204);
    assertThat(request("POST", "/invites/join", companion, body, joinKey).status()).isEqualTo(403);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM app.idempotency_records WHERE secret_response AND"
                    + " response_json IS NOT NULL",
                Integer.class))
        .isZero();
  }

  JsonNode pair(Trip trip) throws Exception {
    JsonNode pairing =
        call(
            "POST",
            "/vehicle-pairings",
            null,
            Map.of(
                "installationId",
                UUID.randomUUID().toString(),
                "displayLabel",
                "Synthetic vehicle"),
            201);
    call(
        "POST",
        "/vehicle-pairings/" + pairing.path("pairingId").asText() + "/approve",
        trip.owner(),
        Map.of(
            "tripId",
            trip.id(),
            "displayCode",
            pairing.path("displayCode").asText(),
            "scopes",
            List.of(
                "TRIP_READ",
                "LOCATION_PUBLISH",
                "NAV_COMMAND_CLAIM",
                "NAV_RESULT_WRITE",
                "PROPOSAL_DECIDE")),
        200);
    return pairing;
  }

  JsonNode propose(Trip trip, String companion) throws Exception {
    return call(
        "POST",
        "/trips/" + trip.id() + "/proposals",
        companion,
        Map.of("kind", "DESTINATION", "place", PLACE),
        201);
  }

  @Test
  void pairingClaimIsOneTimeAndRevocationBlocksLateLocationAndCommandResult() throws Exception {
    Trip trip = trip("DRIVER");
    call("POST", "/trips/" + trip.id() + "/start", trip.owner(), Map.of("expectedVersion", 0), 200);
    JsonNode sharing = share(trip, DRIVER_SCOPES);
    String companion = member(trip, sharing, "COMPANION", MEMBER_SCOPES);
    JsonNode pairing = pair(trip);
    String pairingPath = "/vehicle-pairings/" + pairing.path("pairingId").asText();
    String poll = "POLL:" + pairing.path("pollSecret").asText();
    call("GET", pairingPath, "POLL:incorrect-secret", null, 401);
    JsonNode status = call("GET", pairingPath, poll, null, 200);
    assertThat(status.has("displayCode")).isFalse();
    assertThat(status.has("credential")).isFalse();
    String claimKey = UUID.randomUUID().toString();
    Reply issued = request("POST", pairingPath + "/claim", poll, null, claimKey);
    assertThat(issued.status()).isEqualTo(200);
    String credential = issued.body().path("credential").asText();
    assertThat(credential).isNotBlank();
    Reply replay = request("POST", pairingPath + "/claim", poll, null, claimKey);
    assertThat(replay.status()).isEqualTo(409);
    assertThat(replay.raw()).doesNotContain(credential);
    call("POST", pairingPath + "/claim", poll, null, 409);
    String path = "/trips/" + trip.id();
    call("PUT", path + "/location", credential, observation(1, "VEHICLE"), 200);
    JsonNode proposed = propose(trip, companion);
    JsonNode decision =
        call(
            "POST",
            path + "/proposals/" + proposed.path("id").asText() + "/decision",
            trip.owner(),
            Map.of("decision", "ACCEPT", "expectedVersion", 0, "mode", "DEMO"),
            200);
    JsonNode command = decision.path("navigationCommand");
    assertThat(command.path("status").asText()).isEqualTo("REQUESTED");
    String commandPath = path + "/navigation-commands/" + command.path("id").asText();
    call("POST", commandPath + "/claim", trip.owner(), Map.of("expectedVersion", 0), 403);
    call("POST", commandPath + "/claim", credential, Map.of("expectedVersion", 0), 200);
    call(
        "POST",
        commandPath + "/result",
        credential,
        Map.of("expectedVersion", 1, "outcome", "APPLIED"),
        400);
    call("POST", path + "/sharing/revoke", trip.owner(), null, 200);
    assertThat(db.queryForObject("SELECT count(*) FROM app.trip_latest_locations", Integer.class))
        .isZero();
    Reply lateLocation =
        request("PUT", path + "/location", credential, observation(1, "VEHICLE"), null);
    assertThat(lateLocation.status()).isIn(401, 403, 409);
    Reply lateResult =
        request(
            "POST",
            commandPath + "/result",
            credential,
            Map.of("expectedVersion", 1, "outcome", "DEMO_APPLIED"),
            UUID.randomUUID().toString());
    assertThat(lateResult.status()).isIn(401, 403, 409);
    assertThat(
            db.queryForObject(
                "SELECT status FROM app.navigation_commands WHERE id=?",
                String.class,
                UUID.fromString(command.path("id").asText())))
        .isEqualTo("OUTCOME_UNKNOWN");
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM app.idempotency_records WHERE response_json LIKE ?",
                Integer.class,
                "%" + credential + "%"))
        .isZero();
  }

  @Test
  void approvalDoesNotPretendNavigationAppliedAndExplicitDemoIsDistinct() throws Exception {
    Trip trip = trip("DRIVER");
    call("POST", "/trips/" + trip.id() + "/start", trip.owner(), Map.of("expectedVersion", 0), 200);
    JsonNode sharing = share(trip, DRIVER_SCOPES);
    String companion = member(trip, sharing, "COMPANION", MEMBER_SCOPES);
    String path = "/trips/" + trip.id();
    JsonNode proposal = propose(trip, companion);
    JsonNode accepted =
        call(
            "POST",
            path + "/proposals/" + proposal.path("id").asText() + "/decision",
            trip.owner(),
            Map.of("decision", "ACCEPT", "expectedVersion", 0, "mode", "PLEOS"),
            200);
    assertThat(accepted.path("proposal").path("decisionStatus").asText()).isEqualTo("ACCEPTED");
    assertThat(accepted.path("navigationCommand").path("status").asText()).isEqualTo("FAILED");
    assertThat(accepted.toString()).contains("SDK_NOT_CONNECTED");
    JsonNode pairing = pair(trip);
    String credential =
        call(
                "POST",
                "/vehicle-pairings/" + pairing.path("pairingId").asText() + "/claim",
                "POLL:" + pairing.path("pollSecret").asText(),
                null,
                200)
            .path("credential")
            .asText();
    JsonNode demoProposal = propose(trip, companion);
    JsonNode demo =
        call(
            "POST",
            path + "/proposals/" + demoProposal.path("id").asText() + "/decision",
            trip.owner(),
            Map.of("decision", "ACCEPT", "expectedVersion", 0, "mode", "DEMO"),
            200);
    String commandPath =
        path + "/navigation-commands/" + demo.path("navigationCommand").path("id").asText();
    call("POST", commandPath + "/claim", credential, Map.of("expectedVersion", 0), 200);
    JsonNode result =
        call(
            "POST",
            commandPath + "/result",
            credential,
            Map.of("expectedVersion", 1, "outcome", "DEMO_APPLIED"),
            200);
    assertThat(result.toString()).contains("DEMO_APPLIED").doesNotContain("\"status\":\"APPLIED\"");
  }

  @Test
  void newSharingGenerationDoesNotResurrectOldInvitesOrObservations() throws Exception {
    Trip trip = trip("DRIVER");
    JsonNode first = share(trip, DRIVER_SCOPES);
    String path = "/trips/" + trip.id();
    JsonNode oldInvite = invite(trip, first, "COMPANION");
    var point = observation(first.path("generation").asLong(), "PHONE");
    JsonNode published = call("PUT", path + "/location", trip.owner(), point, 200);
    assertThat(call("PUT", path + "/location", trip.owner(), point, 200).path("observationId"))
        .isEqualTo(published.path("observationId"));
    call("POST", path + "/sharing/revoke", trip.owner(), null, 200);
    JsonNode second = share(trip, DRIVER_SCOPES);
    assertThat(second.path("generation").asLong()).isEqualTo(first.path("generation").asLong() + 1);
    call("PUT", path + "/location", trip.owner(), point, 409);
    Reply oldJoin =
        request(
            "POST",
            "/invites/join",
            token(UUID.randomUUID()),
            joinBody(oldInvite.path("code").asText(), MEMBER_SCOPES),
            UUID.randomUUID().toString());
    assertThat(oldJoin.status()).isIn(403, 409);
    call(
        "PUT",
        path + "/location",
        trip.owner(),
        observation(second.path("generation").asLong(), "PHONE"),
        200);
    long consentVersion =
        call("GET", path + "/consents/me", trip.owner(), null, 200).path("version").asLong();
    call(
        "PUT",
        path + "/consents/me",
        trip.owner(),
        Map.of(
            "scopes",
            Map.of("LOCATION_PUBLISH", false),
            "consentPolicyVersion",
            "test-policy-v1",
            "expectedVersion",
            consentVersion),
        200);
    assertThat(db.queryForObject("SELECT count(*) FROM app.trip_latest_locations", Integer.class))
        .isZero();
    call(
        "PUT",
        path + "/location",
        trip.owner(),
        observation(second.path("generation").asLong(), "PHONE"),
        403);
    call(
        "PUT",
        path + "/consents/me",
        trip.owner(),
        Map.of(
            "scopes",
            Map.of("LOCATION_PUBLISH", true),
            "consentPolicyVersion",
            "test-policy-v1",
            "expectedVersion",
            consentVersion),
        409);
    long nextVersion =
        call("GET", path + "/consents/me", trip.owner(), null, 200).path("version").asLong();
    call(
        "PUT",
        path + "/consents/me",
        trip.owner(),
        Map.of(
            "scopes",
            Map.of("LOCATION_PUBLISH", true),
            "consentPolicyVersion",
            "test-policy-v1",
            "expectedVersion",
            nextVersion),
        200);
    call(
        "PUT",
        path + "/location",
        trip.owner(),
        observation(second.path("generation").asLong(), "PHONE"),
        200);
    call(
        "PUT",
        path + "/consents/me",
        trip.owner(),
        Map.of(
            "scopes",
            Map.of("TRIP_COLLABORATION", false),
            "consentPolicyVersion",
            "test-policy-v1",
            "expectedVersion",
            nextVersion + 1),
        200);
    JsonNode ownConsent = call("GET", path + "/consents/me", trip.owner(), null, 200);
    assertThat(ownConsent.path("scopes").path("LOCATION_PUBLISH").asBoolean()).isTrue();
    assertThat(ownConsent.path("scopes").path("TRIP_COLLABORATION").asBoolean()).isFalse();
    call(
        "PUT",
        path + "/location",
        trip.owner(),
        observation(second.path("generation").asLong(), "PHONE"),
        403);
    assertThat(call("GET", path, trip.owner(), null, 200).path("location").isNull()).isTrue();
  }

  @Test
  void expiredInvitesAndTripsAreRejectedAndDriverUniquenessIsDatabaseEnforced() throws Exception {
    Trip trip = trip("DRIVER");
    JsonNode sharing = share(trip, DRIVER_SCOPES);
    JsonNode invitation = invite(trip, sharing, "COMPANION");
    db.update(
        "UPDATE app.trip_invitations SET expires_at=now()-interval '1 minute' WHERE id=?",
        UUID.fromString(invitation.path("id").asText()));
    call(
        "POST",
        "/invites/join",
        token(UUID.randomUUID()),
        joinBody(invitation.path("code").asText(), MEMBER_SCOPES),
        409);
    String member = member(trip, sharing, "COMPANION", MEMBER_SCOPES);
    assertThatThrownBy(
            () ->
                db.update(
                    "UPDATE app.trip_participants SET role='DRIVER' WHERE trip_id=? AND"
                        + " role='COMPANION'",
                    UUID.fromString(trip.id())))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    db.update(
        "UPDATE app.trips SET expires_at=now()-interval '1 minute' WHERE id=?",
        UUID.fromString(trip.id()));
    Reply expired = request("GET", "/trips/" + trip.id(), member, null, null);
    assertThat(expired.status()).isIn(403, 409, 410);
  }

  @Test
  void terminalOwnerRetryReturnsOriginalResponseWithoutReopeningTrip() throws Exception {
    Trip trip = trip("DRIVER");
    String path = "/trips/" + trip.id();
    call("POST", path + "/start", trip.owner(), Map.of("expectedVersion", 0), 200);
    String key = UUID.randomUUID().toString();
    var body = Map.of("expectedVersion", 1);
    Reply ended = request("POST", path + "/end", trip.owner(), body, key);
    assertThat(ended.status()).isEqualTo(200);
    Reply retry = request("POST", path + "/end", trip.owner(), body, key);
    assertThat(retry.status()).isEqualTo(200);
    assertThat(retry.body()).isEqualTo(ended.body());
    assertThat(version(trip)).isEqualTo(2);
    call("POST", path + "/start", trip.owner(), Map.of("expectedVersion", 2), 409);
    assertThat(call("GET", path, trip.owner(), null, 200).path("status").asText())
        .isEqualTo("ENDED");
  }

  @Test
  void newGenerationDoesNotExposeUnconsentedMembersOrOldReadiness() throws Exception {
    Trip trip = trip("DRIVER");
    JsonNode sharing = share(trip, DRIVER_SCOPES);
    String companion = member(trip, sharing, "COMPANION", MEMBER_SCOPES);
    String path = "/trips/" + trip.id();
    call(
        "PUT",
        path + "/readiness/me",
        companion,
        Map.of("ready", true, "observedAt", Instant.now().toString()),
        200);
    JsonNode before = call("GET", path, trip.owner(), null, 200);
    assertThat(before.path("participants").size()).isEqualTo(2);
    assertThat(before.path("participants").toString()).contains("\"ready\":true");
    call("POST", path + "/sharing/revoke", trip.owner(), null, 200);
    JsonNode freshSharing = share(trip, DRIVER_SCOPES);
    JsonNode renewed = call("GET", path, trip.owner(), null, 200);
    assertThat(renewed.path("participants").size()).isEqualTo(1);
    assertThat(request("GET", path, companion, null, null).status()).isIn(403, 404);
    Reply oldReconsent =
        request(
            "PUT",
            path + "/consents/me",
            companion,
            Map.of(
                "scopes",
                Map.of("TRIP_COLLABORATION", true),
                "consentPolicyVersion",
                "test-policy-v1",
                "expectedVersion",
                0),
            null);
    assertThat(oldReconsent.status()).isIn(403, 404);
    call(
        "POST",
        "/invites/join",
        companion,
        joinBody(invite(trip, freshSharing, "COMPANION").path("code").asText(), MEMBER_SCOPES),
        201);
    long ownVersion =
        call("GET", path + "/consents/me", companion, null, 200).path("version").asLong();
    JsonNode reconsented = call("GET", path, trip.owner(), null, 200);
    assertThat(reconsented.path("participants").size()).isEqualTo(2);
    assertThat(reconsented.path("participants").toString()).doesNotContain("\"ready\":true");
    call(
        "PUT",
        path + "/readiness/me",
        companion,
        Map.of("ready", true, "observedAt", Instant.now().toString()),
        200);
    call(
        "PUT",
        path + "/consents/me",
        companion,
        Map.of(
            "scopes",
            Map.of("TRIP_COLLABORATION", false),
            "consentPolicyVersion",
            "test-policy-v1",
            "expectedVersion",
            ownVersion),
        200);
    assertThat(call("GET", path, trip.owner(), null, 200).path("participants").size()).isEqualTo(1);
    JsonNode withdrawn = call("GET", path + "/consents/me", companion, null, 200);
    assertThat(withdrawn.path("version").asLong()).isEqualTo(ownVersion + 1);
    assertThat(withdrawn.path("scopes").path("TRIP_COLLABORATION").asBoolean()).isFalse();
    call(
        "PUT",
        path + "/consents/me",
        companion,
        Map.of(
            "scopes",
            Map.of("TRIP_COLLABORATION", true),
            "consentPolicyVersion",
            "test-policy-v1",
            "expectedVersion",
            withdrawn.path("version").asLong()),
        200);
    assertThat(call("GET", path, trip.owner(), null, 200).path("participants").toString())
        .doesNotContain("\"ready\":true");
  }

  @Test
  void driverDiscoversProposalsWithPaginationAndGenerationIsolation() throws Exception {
    Trip trip = trip("DRIVER");
    call("POST", "/trips/" + trip.id() + "/start", trip.owner(), Map.of("expectedVersion", 0), 200);
    JsonNode sharing = share(trip, DRIVER_SCOPES);
    String companion = member(trip, sharing, "COMPANION", MEMBER_SCOPES);
    String path = "/trips/" + trip.id();
    String proposalKey = UUID.randomUUID().toString();
    var proposalBody = Map.of("kind", "DESTINATION", "place", PLACE);
    Reply initialProposal =
        request("POST", path + "/proposals", companion, proposalBody, proposalKey);
    assertThat(initialProposal.status()).isEqualTo(201);
    propose(trip, companion);
    JsonNode first =
        call("GET", path + "/proposals?limit=1&status=PENDING", trip.owner(), null, 200);
    assertThat(first.path("proposals").size()).isEqualTo(1);
    String proposal = first.path("proposals").get(0).path("id").asText();
    String cursor = first.path("nextCursor").asText();
    assertThat(cursor).isNotBlank().isNotEqualTo("null");
    JsonNode second =
        call(
            "GET",
            path + "/proposals?limit=1&status=PENDING&cursor=" + cursor,
            trip.owner(),
            null,
            200);
    assertThat(second.path("proposals").size()).isEqualTo(1);
    assertThat(second.path("proposals").get(0).path("id").asText()).isNotEqualTo(proposal);
    String decisionKey = UUID.randomUUID().toString();
    var decisionBody = Map.of("decision", "ACCEPT", "expectedVersion", 0, "mode", "PLEOS");
    Reply decision =
        request(
            "POST",
            path + "/proposals/" + proposal + "/decision",
            trip.owner(),
            decisionBody,
            decisionKey);
    assertThat(decision.status()).as(decision.raw()).isEqualTo(200);
    JsonNode decided = call("GET", path + "/proposals?status=ACCEPTED", trip.owner(), null, 200);
    assertThat(decided.path("proposals").size()).isEqualTo(1);
    assertThat(decided.path("proposals").get(0).path("navigationCommand").path("status").asText())
        .isEqualTo("FAILED");
    call("GET", path + "/proposals?limit=0", trip.owner(), null, 400);
    call("POST", path + "/sharing/revoke", trip.owner(), null, 200);
    assertThat(request("GET", path + "/proposals", companion, null, null).status()).isIn(403, 404);
    JsonNode freshSharing = share(trip, DRIVER_SCOPES);
    assertThat(call("GET", path + "/proposals", trip.owner(), null, 200).path("proposals").size())
        .isZero();
    assertThat(
            request(
                    "POST",
                    path + "/proposals/" + proposal + "/decision",
                    trip.owner(),
                    decisionBody,
                    decisionKey)
                .status())
        .isEqualTo(404);
    call(
        "POST",
        "/invites/join",
        companion,
        joinBody(invite(trip, freshSharing, "COMPANION").path("code").asText(), MEMBER_SCOPES),
        201);
    Reply freshProposal =
        request("POST", path + "/proposals", companion, proposalBody, proposalKey);
    assertThat(freshProposal.status()).isEqualTo(201);
    assertThat(freshProposal.body().path("id")).isNotEqualTo(initialProposal.body().path("id"));
  }

  @Test
  void planPatchCannotKeepAnExpiryBeforeChangedDeadlineAndStay() throws Exception {
    Trip trip = trip("DRIVER");
    String path = "/trips/" + trip.id();
    var body = new java.util.LinkedHashMap<String, Object>(plan("DRIVER"));
    body.remove("ownerRole");
    body.put("deadlineAt", Instant.now().plusSeconds(26 * 3600).toString());
    String etag = "\"plan-1\"";
    assertThat(request("PATCH", path, trip.owner(), body, null).status()).isEqualTo(428);
    assertThat(request("PATCH", path, trip.owner(), body, null, "\"plan-0\"").status())
        .isEqualTo(412);
    assertThat(request("PATCH", path, trip.owner(), body, null, etag).status()).isEqualTo(400);
    body.put("expiresAt", Instant.now().plusSeconds(26 * 3600 + 1800).toString());
    assertThat(request("PATCH", path, trip.owner(), body, null, etag).status()).isEqualTo(400);
    body.put("expiresAt", Instant.now().plusSeconds(28 * 3600).toString());
    Reply updated = request("PATCH", path, trip.owner(), body, null, etag);
    assertThat(updated.status()).as(updated.raw()).isEqualTo(200);
    assertThat(updated.body().path("planRevision").asLong()).isEqualTo(2);
    assertThat(request("PATCH", path, trip.owner(), body, null, etag).status()).isEqualTo(412);
  }

  @Test
  void revokingSharingRemovesFormerDriverAndAllowsFreshDriverInvitation() throws Exception {
    Trip trip = trip("COMPANION");
    JsonNode first = share(trip, MEMBER_SCOPES);
    String oldDriver = member(trip, first, "DRIVER", DRIVER_SCOPES);
    String path = "/trips/" + trip.id();
    call("POST", path + "/sharing/revoke", trip.owner(), null, 200);
    assertThat(
            db.queryForObject(
                "SELECT count(*) FROM app.trip_participants WHERE trip_id=? AND role='DRIVER' AND"
                    + " status='ACTIVE'",
                Integer.class,
                UUID.fromString(trip.id())))
        .isZero();
    JsonNode next = share(trip, MEMBER_SCOPES);
    String newDriver = member(trip, next, "DRIVER", DRIVER_SCOPES);
    call(
        "PUT",
        path + "/location",
        newDriver,
        observation(next.path("generation").asLong(), "PHONE"),
        200);
    assertThat(request("GET", path, oldDriver, null, null).status()).isIn(403, 404);
    assertThat(
            request(
                    "PUT",
                    path + "/location",
                    oldDriver,
                    observation(next.path("generation").asLong(), "PHONE"),
                    null)
                .status())
        .isIn(403, 404);
  }

  @Test
  void lifecycleTransitionsRequireExplicitExpectedVersion() throws Exception {
    Trip trip = trip("DRIVER");
    String path = "/trips/" + trip.id();
    call("POST", path + "/start", trip.owner(), Map.of(), 400);
    assertThat(version(trip)).isZero();
    assertThat(call("GET", path, trip.owner(), null, 200).path("status").asText())
        .isEqualTo("PLANNED");
    call("POST", path + "/start", trip.owner(), Map.of("expectedVersion", 0), 200);
    call("POST", path + "/end", trip.owner(), Map.of(), 400);
    call("POST", path + "/cancel", trip.owner(), Map.of(), 400);
    assertThat(version(trip)).isEqualTo(1);
    assertThat(call("GET", path, trip.owner(), null, 200).path("status").asText())
        .isEqualTo("ACTIVE");
  }
}
