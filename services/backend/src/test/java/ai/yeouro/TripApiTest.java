package ai.yeouro;

import ai.yeouro.sharing.*;
import ai.yeouro.trip.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:yeouro-tests;DB_CLOSE_DELAY=-1", "yeouro.cleanup-delay-ms=3600000"})
@AutoConfigureMockMvc
@Import(TripApiTest.TimeConfig.class)
class TripApiTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired TestClock clock;
    @Autowired TripRepository trips;
    @Autowired AccessTokenRepository tokens;
    @Autowired InvitationRepository invitations;
    @Autowired ParticipantRepository participants;
    @Autowired ProposalRepository proposals;
    @Autowired ExpiredTripCleanup cleanup;
    static final String BASE = "/api/v1";
    static final Map<String,Object> PLACE = Map.of("name", "Synthetic test destination", "latitude", 1.25, "longitude", 2.5);
    @BeforeEach void reset() {
        trips.deleteAllInBatch();
        clock.now.set(Instant.parse("2026-10-28T12:00:00Z"));
    }
    record Session(String id, String token, JsonNode body) {}
    Session create() throws Exception {
        JsonNode body = send(post(BASE+"/trips"), null, Map.of("destination", PLACE), 201);
        return new Session(body.at("/trip/id").asText(), body.get("accessToken").asText(), body);
    }
    String invite(Session owner, String role) throws Exception {
        return send(post(path(owner,"/invites")), owner.token, Map.of("consent",true,"role",role),201).get("code").asText();
    }
    Session join(String code) throws Exception {
        JsonNode body = send(post(BASE+"/invites/join"), null, Map.of("code",code,"displayName","Test companion","consent",true),201);
        return new Session(body.at("/trip/id").asText(),body.get("accessToken").asText(),body);
    }
    JsonNode propose(Session companion, String requestId) throws Exception {
        return send(post(path(companion,"/proposals")),companion.token,Map.of("requestId",requestId,"place",PLACE),201);
    }
    String proposalId(Session companion) throws Exception { return propose(companion,UUID.randomUUID().toString()).at("/proposals/0/id").asText(); }
    String path(Session session, String suffix) { return BASE+"/trips/"+session.id+suffix; }
    JsonNode send(MockHttpServletRequestBuilder request, String token, Object body, int status) throws Exception {
        if (token != null) request.header("Authorization","Bearer "+token);
        if (body != null) request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        String response = mvc.perform(request).andExpect(status().is(status))
            .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }
    Map<String,Object> location(Instant observed) {
        return Map.of("latitude",1.5,"longitude",2.75,"accuracyMeters",15.0,"observedAt",observed.toString(),"source","SIMULATED");
    }
    @Test void createsRealStoredTripAndNeverPersistsPlainCredentials() throws Exception {
        send(get(BASE+"/health"),null,null,200);
        Session owner = create();
        assertThat(owner.body.at("/trip/navigation/availability").asText()).isEqualTo("UNAVAILABLE");
        assertThat(owner.body.get("role").asText()).isEqualTo("OWNER");
        assertThat(trips.count()).isEqualTo(1);
        assertThat(tokens.findAll()).allMatch(t -> !t.hash.equals(owner.token) && t.hash.length()==64);
        JsonNode snapshot = send(get(path(owner,"")),owner.token,null,200);
        assertThat(snapshot.has("accessToken")).isFalse();
        assertThat(snapshot.get("location").isNull()).isTrue();
        send(get(path(owner,"")),null,null,401);
        send(get(path(owner,"")),"invalid",null,401);
    }
    @Test void consentIsMandatoryAndCannotGrantOwnerOrSelfUpgrade() throws Exception {
        Session owner = create();
        assertThat(send(post(path(owner,"/invites")),owner.token,Map.of("consent",false,"role","COMPANION"),400).get("code").asText()).isEqualTo("CONSENT_REQUIRED");
        send(post(path(owner,"/invites")),owner.token,Map.of("consent",true,"role","OWNER"),400);
        String code = invite(owner,"COMPANION");
        send(post(BASE+"/invites/join"),null,Map.of("code",code,"displayName","Test","consent",false),400);
        send(post(BASE+"/invites/join"),null,Map.of("code",code,"displayName","Test","consent",true,"role","DRIVER"),400);
        Session companion = join(code);
        assertThat(companion.body.get("role").asText()).isEqualTo("COMPANION");
        assertThat(companion.token).isNotEqualTo(owner.token);
        assertThat(companion.body.toString()).doesNotContain(owner.token);
        assertThat(invitations.findAll()).allMatch(i -> !i.codeHash.equals(code));
    }
    @Test void invitesAreSingleUseAndExpire() throws Exception {
        Session owner = create(); String used = invite(owner,"COMPANION"); join(used);
        assertThat(send(post(BASE+"/invites/join"),null,Map.of("code",used,"displayName","Test","consent",true),409).get("code").asText()).isEqualTo("INVITE_CONSUMED");
        String expired = invite(owner,"COMPANION"); clock.advance(Duration.ofMinutes(15));
        assertThat(send(post(BASE+"/invites/join"),null,Map.of("code",expired,"displayName","Test","consent",true),409).get("code").asText()).isEqualTo("INVITE_EXPIRED");
    }
    @Test void rolesAndTripScopeAreEnforced() throws Exception {
        Session owner = create(); Session companion = join(invite(owner,"COMPANION")); Session other = create();
        send(get(path(other,"")),companion.token,null,403);
        send(post(path(owner,"/invites")),companion.token,Map.of("consent",true,"role","DRIVER"),403);
        send(post(path(owner,"/sharing/revoke")),companion.token,null,403);
        send(post(path(owner,"/end")),companion.token,null,403);
        send(put(path(owner,"/location")),companion.token,location(clock.instant()),403);
        send(put(path(owner,"/readiness")),owner.token,Map.of("ready",true),403);
    }
    @Test void readinessIsAnIdempotentParticipantMutation() throws Exception {
        Session owner = create(); Session companion = join(invite(owner,"COMPANION"));
        JsonNode once = send(put(path(owner,"/readiness")),companion.token,Map.of("ready",true),200);
        JsonNode twice = send(put(path(owner,"/readiness")),companion.token,Map.of("ready",true),200);
        assertThat(once.get("version").asLong()).isEqualTo(twice.get("version").asLong());
        assertThat(twice.at("/participants/0/ready").asBoolean()).isTrue();
        send(put(path(owner,"/readiness")),companion.token,Map.of(),400);
    }
    @Test void proposalRequestIdsDeduplicateAndRejectChangedPayload() throws Exception {
        Session owner = create(); Session companion = join(invite(owner,"COMPANION")); String requestId = UUID.randomUUID().toString();
        JsonNode first = propose(companion,requestId); JsonNode second = propose(companion,requestId);
        assertThat(second.get("proposals").size()).isEqualTo(1);
        assertThat(second.get("version").asLong()).isEqualTo(first.get("version").asLong());
        send(post(path(owner,"/proposals")),companion.token,Map.of("requestId",requestId,"place",Map.of("name","Different synthetic place")),409);
    }
    @Test void acceptIsNotAppliedAndFailedCallbackIsIdempotent() throws Exception {
        Session owner = create(); Session companion = join(invite(owner,"COMPANION")); String proposal = proposalId(companion);
        String decision = "/proposals/"+proposal+"/decision"; String result = "/proposals/"+proposal+"/navigation-result";
        send(post(path(owner,decision)),companion.token,Map.of("decision","ACCEPT","expectedVersion",0),403);
        send(post(path(owner,decision)),owner.token,Map.of("decision","ACCEPT","expectedVersion",8),409);
        JsonNode pending = send(post(path(owner,decision)),owner.token,Map.of("decision","ACCEPT","expectedVersion",0),200);
        assertThat(pending.at("/proposals/0/status").asText()).isEqualTo("APPLY_REQUESTED");
        JsonNode repeat = send(post(path(owner,decision)),owner.token,Map.of("decision","ACCEPT","expectedVersion",0),200);
        assertThat(repeat.get("version").asLong()).isEqualTo(pending.get("version").asLong());
        assertThat(send(post(path(owner,result)),owner.token,Map.of("outcome","APPLIED"),409).get("code").asText()).isEqualTo("NAVIGATION_UNAVAILABLE");
        JsonNode failed = send(post(path(owner,result)),owner.token,Map.of("outcome","FAILED","reason","SDK_NOT_CONNECTED"),200);
        assertThat(failed.at("/proposals/0/status").asText()).isEqualTo("FAILED");
        JsonNode twice = send(post(path(owner,result)),owner.token,Map.of("outcome","FAILED","reason","SDK_NOT_CONNECTED"),200);
        assertThat(twice.get("version").asLong()).isEqualTo(failed.get("version").asLong());
        send(post(path(owner,result)),owner.token,Map.of("outcome","DEMO_APPLIED"),409);
    }
    @Test void driverMayDecideButCannotInviteAndDemoIsNeverRealApplied() throws Exception {
        Session owner = create(); Session companion = join(invite(owner,"COMPANION")); Session driver = join(invite(owner,"DRIVER"));
        assertThat(driver.body.get("role").asText()).isEqualTo("DRIVER");
        send(post(path(owner,"/invites")),driver.token,Map.of("consent",true,"role","DRIVER"),403);
        send(put(path(owner,"/readiness")),driver.token,Map.of("ready",true),403);
        String proposal = proposalId(companion);
        send(post(path(owner,"/proposals/"+proposal+"/decision")),driver.token,Map.of("decision","ACCEPT","expectedVersion",0),200);
        JsonNode result = send(post(path(owner,"/proposals/"+proposal+"/navigation-result")),driver.token,Map.of("outcome","DEMO_APPLIED"),200);
        assertThat(result.at("/proposals/0/status").asText()).isEqualTo("DEMO_APPLIED");
        assertThat(result.at("/destination/name").asText()).isEqualTo(PLACE.get("name"));
    }
    @Test void onlyOneNavigationApplicationCanBePendingAndDismissIsSafe() throws Exception {
        Session owner = create(); Session companion = join(invite(owner,"COMPANION")); String first=proposalId(companion);
        send(post(path(owner,"/proposals/"+first+"/decision")),owner.token,Map.of("decision","ACCEPT","expectedVersion",0),200);
        JsonNode list = propose(companion,UUID.randomUUID().toString());
        String second = list.get("proposals").get(0).get("id").asText().equals(first) ? list.at("/proposals/1/id").asText() : list.at("/proposals/0/id").asText();
        send(post(path(owner,"/proposals/"+second+"/decision")),owner.token,Map.of("decision","ACCEPT","expectedVersion",0),409);
        send(post(path(owner,"/proposals/"+second+"/decision")),owner.token,Map.of("decision","DISMISS","expectedVersion",0),200);
        send(post(path(owner,"/proposals/"+second+"/decision")),owner.token,Map.of("decision","DISMISS","expectedVersion",0),200);
    }
    @Test void locationRequiresConsentAndFreshnessAndHidesStaleCoordinates() throws Exception {
        Session owner=create();
        send(put(path(owner,"/location")),owner.token,location(clock.instant()),409);
        Session companion=join(invite(owner,"COMPANION"));
        send(put(path(owner,"/location")),owner.token,location(clock.instant().minusSeconds(121)),409);
        send(put(path(owner,"/location")),owner.token,location(clock.instant().plusSeconds(31)),409);
        JsonNode uploaded=send(put(path(owner,"/location")),owner.token,location(clock.instant().plusNanos(123456)),200);
        assertThat(uploaded.at("/location/source").asText()).isEqualTo("SIMULATED");
        send(put(path(owner,"/location")),owner.token,location(clock.instant().plusNanos(123456)),409);
        assertThat(send(get(path(owner,"")),companion.token,null,200).get("location").isNull()).isFalse();
        clock.advance(Duration.ofSeconds(121));
        assertThat(send(get(path(owner,"")),companion.token,null,200).get("location").isNull()).isTrue();
    }
    @Test void revokeCancelsCommandsClearsLocationAndInvalidatesAllInvitesAndParticipants() throws Exception {
        Session owner=create(); Session companion=join(invite(owner,"COMPANION")); String pendingInvite=invite(owner,"DRIVER");
        String proposal=proposalId(companion);
        send(post(path(owner,"/proposals/"+proposal+"/decision")),owner.token,Map.of("decision","ACCEPT","expectedVersion",0),200);
        send(put(path(owner,"/location")),owner.token,location(clock.instant()),200);
        JsonNode revoked=send(post(path(owner,"/sharing/revoke")),owner.token,null,200);
        assertThat(revoked.get("location").isNull()).isTrue();
        assertThat(revoked.get("sharingEnabled").asBoolean()).isFalse();
        assertThat(revoked.at("/proposals/0/status").asText()).isEqualTo("CANCELLED");
        send(get(path(owner,"")),companion.token,null,401);
        send(post(BASE+"/invites/join"),null,Map.of("code",pendingInvite,"displayName","Test","consent",true),409);
        invite(owner,"COMPANION"); // New consent cannot revive old credentials.
        send(get(path(owner,"")),companion.token,null,401);
    }
    @Test void endRevokesSharingAndCannotBeReopenedOrAppliedLate() throws Exception {
        Session owner=create(); Session companion=join(invite(owner,"COMPANION")); String proposal=proposalId(companion);
        send(post(path(owner,"/proposals/"+proposal+"/decision")),owner.token,Map.of("decision","ACCEPT","expectedVersion",0),200);
        JsonNode ended=send(post(path(owner,"/end")),owner.token,null,200);
        assertThat(ended.get("status").asText()).isEqualTo("ENDED");
        assertThat(ended.at("/proposals/0/status").asText()).isEqualTo("CANCELLED");
        JsonNode repeat=send(post(path(owner,"/end")),owner.token,null,200);
        assertThat(repeat.get("version").asLong()).isEqualTo(ended.get("version").asLong());
        send(get(path(owner,"")),companion.token,null,401);
        send(post(path(owner,"/invites")),owner.token,Map.of("consent",true,"role","COMPANION"),409);
        send(post(path(owner,"/proposals/"+proposal+"/navigation-result")),owner.token,Map.of("outcome","DEMO_APPLIED"),409);
    }
    @Test void expiredCapabilitiesFailAndCleanupDeletesAggregateData() throws Exception {
        Session owner=create(); Session companion=join(invite(owner,"COMPANION")); proposalId(companion);
        clock.advance(Duration.ofHours(24));
        send(get(path(owner,"")),owner.token,null,401);
        send(get(path(owner,"")),companion.token,null,401);
        cleanup.purge();
        assertThat(trips.count()).isZero(); assertThat(tokens.count()).isZero();
        assertThat(invitations.count()).isZero(); assertThat(participants.count()).isZero(); assertThat(proposals.count()).isZero();
    }
    @Test void concurrentInviteRedemptionCreatesExactlyOneParticipant() throws Exception {
        Session owner=create(); String code=invite(owner,"COMPANION");
        try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch gate=new CountDownLatch(1);
            Callable<Integer> redeem=()->{gate.await();return mvc.perform(post(BASE+"/invites/join").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("code",code,"displayName","Concurrent test","consent",true)))).andReturn().getResponse().getStatus();};
            Future<Integer> first=pool.submit(redeem); Future<Integer> second=pool.submit(redeem); gate.countDown();
            assertThat(List.of(first.get(10,TimeUnit.SECONDS),second.get(10,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(participants.count()).isEqualTo(1);
    }
    @Test void concurrentDuplicateProposalUsesOnePersistedCommand() throws Exception {
        Session owner=create(); Session companion=join(invite(owner,"COMPANION")); String requestId=UUID.randomUUID().toString();
        try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch gate=new CountDownLatch(1);
            Callable<JsonNode> submit=()->{gate.await(); return propose(companion,requestId);};
            Future<JsonNode> first=pool.submit(submit); Future<JsonNode> second=pool.submit(submit); gate.countDown();
            assertThat(first.get(10,TimeUnit.SECONDS).get("proposals").size()).isEqualTo(1);
            assertThat(second.get(10,TimeUnit.SECONDS).get("proposals").size()).isEqualTo(1);
        }
        assertThat(proposals.count()).isEqualTo(1);
    }
    @Test void readinessRacingEndCannotReviveParticipantAccess() throws Exception {
        Session owner=create(); Session companion=join(invite(owner,"COMPANION"));
        try (ExecutorService pool=Executors.newFixedThreadPool(2)) {
            CountDownLatch gate=new CountDownLatch(1);
            Future<Integer> ready=pool.submit(()->{gate.await(); return mvc.perform(put(path(owner,"/readiness"))
                .header("Authorization","Bearer "+companion.token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ready\":true}")).andReturn().getResponse().getStatus();});
            Future<JsonNode> end=pool.submit(()->{gate.await(); return send(post(path(owner,"/end")),owner.token,null,200);});
            gate.countDown();
            assertThat(ready.get(10,TimeUnit.SECONDS)).isIn(200,401);
            assertThat(end.get(10,TimeUnit.SECONDS).get("status").asText()).isEqualTo("ENDED");
        }
        send(get(path(owner,"")),companion.token,null,401);
        assertThat(send(get(path(owner,"")),owner.token,null,200).get("participants").size()).isZero();
    }
    @Test void inputValidationDoesNotReflectSecretsAndRejectsPartialCoordinates() throws Exception {
        send(post(BASE+"/trips"),null,Map.of("destination",Map.of("name","Synthetic","latitude",1.0)),400);
        send(post(BASE+"/trips"),null,Map.of("destination",Map.of("name","Synthetic","latitude",91.0,"longitude",2.0)),400);
        JsonNode result=send(post(BASE+"/invites/join"),null,Map.of("code","SECRET_TEST","displayName","Test","consent",true),400);
        assertThat(result.toString()).doesNotContain("SECRET_TEST");
    }
    @TestConfiguration static class TimeConfig { @Bean @Primary TestClock testClock() { return new TestClock(); } }
    static class TestClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-10-28T12:00:00Z"));
        void advance(Duration duration) { now.updateAndGet(value->value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
