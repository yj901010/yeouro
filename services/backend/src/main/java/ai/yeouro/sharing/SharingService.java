package ai.yeouro.sharing;

import ai.yeouro.api.*;
import ai.yeouro.api.ApiModels.*;
import ai.yeouro.trip.*;
import java.time.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class SharingService {
    private final TripRepository trips;
    private final TripAccess access;
    private final TripViews views;
    private final TokenService tokens;
    private final InvitationRepository invitations;
    private final ParticipantRepository participants;
    private final ProposalRepository proposals;
    private final Clock clock;
    public SharingService(TripRepository trips, TripAccess access, TripViews views, TokenService tokens,
                          InvitationRepository invitations, ParticipantRepository participants,
                          ProposalRepository proposals, Clock clock) {
        this.trips = trips; this.access = access; this.views = views; this.tokens = tokens;
        this.invitations = invitations; this.participants = participants; this.proposals = proposals; this.clock = clock;
    }
    public InviteResponse invite(UUID tripId, String bearer, ConsentRequest request) {
        TripEntity trip = access.lock(tripId, bearer, Role.OWNER).trip();
        access.requireActive(trip);
        requireConsent(request.consent());
        if (request.role() == Role.OWNER) throw ApiException.invalid("INVALID_REQUEST", "An invite cannot grant ownership.");
        if (invitations.findByTripId(tripId).size() >= 32) throw limit("This trip has reached its invite limit.");
        String raw = tokens.randomSecret(18);
        Instant expiry = clock.instant().plus(Duration.ofMinutes(15));
        if (expiry.isAfter(trip.expiresAt)) expiry = trip.expiresAt;
        invitations.save(new Invitation(TokenService.hash(raw), tripId, request.role(), expiry));
        trip.sharingEnabled = true; trip.changed();
        return new InviteResponse(raw, expiry);
    }
    public SessionResponse join(JoinRequest request) {
        requireConsent(request.consent());
        String hash = TokenService.hash(request.code());
        UUID tripId = invitations.findTripId(hash).orElseThrow(() -> ApiException.conflict("INVITE_INVALID", "This invite is invalid or revoked."));
        TripEntity trip = trips.lockById(tripId).orElseThrow(() -> ApiException.conflict("INVITE_INVALID", "This invite is invalid or revoked."));
        Invitation invite = invitations.findById(hash).orElseThrow(() -> ApiException.conflict("INVITE_INVALID", "This invite is invalid or revoked."));
        if (invite.revoked) throw ApiException.conflict("INVITE_INVALID", "This invite is invalid or revoked.");
        if (invite.consumed) throw ApiException.conflict("INVITE_CONSUMED", "This one-time invite has already been used.");
        if (!invite.expiresAt.isAfter(clock.instant())) throw ApiException.conflict("INVITE_EXPIRED", "This invite has expired.");
        access.requireSharing(trip);
        if (participants.findByTripIdOrderById(tripId).size() >= 8) throw limit("This trip has reached its participant limit.");
        ParticipantEntity participant = participants.save(new ParticipantEntity(tripId, request.displayName(), invite.role));
        invite.consumed = true;
        String raw = tokens.issue(trip, participant.id, invite.role);
        trip.changed();
        return new SessionResponse(views.snapshot(trip), raw, invite.role, participant.id, trip.expiresAt);
    }
    public Trip readiness(UUID tripId, String bearer, ReadinessRequest request) {
        var auth = access.lock(tripId, bearer, Role.COMPANION);
        access.requireSharing(auth.trip());
        ParticipantEntity person = participants.findById(auth.token().participantId).orElseThrow(ApiException::unauthorized);
        if (person.ready != request.ready()) { person.ready = request.ready(); auth.trip().changed(); }
        return views.snapshot(auth.trip());
    }
    public Trip revoke(UUID tripId, String bearer) {
        TripEntity trip = access.lock(tripId, bearer, Role.OWNER).trip();
        revokeLocked(trip);
        return views.snapshot(trip);
    }
    /** Caller must hold the trip lock; participant access disappears in the same transaction. */
    public void revokeLocked(TripEntity trip) {
        boolean changed = trip.sharingEnabled || trip.location != null;
        trip.sharingEnabled = false; trip.location = null;
        tokens.revokeParticipants(trip.id);
        invitations.findByTripId(trip.id).forEach(i -> i.revoked = true);
        participants.findByTripIdOrderById(trip.id).forEach(p -> { p.active = false; p.ready = false; });
        proposals.findByTripIdOrderById(trip.id).stream()
            .filter(p -> p.status == ProposalStatus.PENDING || p.status == ProposalStatus.APPLY_REQUESTED)
            .forEach(p -> { p.status = ProposalStatus.CANCELLED; p.resultReason = "SHARING_ENDED"; p.revision++; });
        if (changed) trip.changed();
    }
    private void requireConsent(Boolean consent) {
        if (!Boolean.TRUE.equals(consent)) throw ApiException.invalid("CONSENT_REQUIRED", "Explicit consent is required for this trip.");
    }
    private ApiException limit(String message) { return new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LIMIT_EXCEEDED", message); }
}
