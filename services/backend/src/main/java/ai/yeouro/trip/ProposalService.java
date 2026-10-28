package ai.yeouro.trip;

import ai.yeouro.api.*;
import ai.yeouro.api.ApiModels.*;
import ai.yeouro.integration.NavigationGateway;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class ProposalService {
    private final TripAccess access;
    private final TripViews views;
    private final ProposalRepository proposals;
    private final NavigationGateway navigation;
    public ProposalService(TripAccess access, TripViews views, ProposalRepository proposals, NavigationGateway navigation) {
        this.access = access; this.views = views; this.proposals = proposals; this.navigation = navigation;
    }
    public Trip submit(UUID tripId, String bearer, ProposalRequest request) {
        var auth = access.lock(tripId, bearer, Role.COMPANION);
        access.requireSharing(auth.trip());
        PlaceValue place = new PlaceValue(request.place());
        var existing = proposals.findByParticipantIdAndRequestId(auth.token().participantId, request.requestId());
        if (existing.isPresent()) {
            if (!existing.get().place.dto().equals(place.dto()))
                throw ApiException.conflict("REQUEST_ID_REUSED", "That requestId was used with different content.");
            return views.snapshot(auth.trip());
        }
        if (proposals.findByTripIdOrderById(tripId).size() >= 100)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "LIMIT_EXCEEDED", "This trip has reached its proposal limit.");
        proposals.save(new ProposalEntity(tripId, auth.token().participantId, request.requestId(), place));
        auth.trip().changed();
        return views.snapshot(auth.trip());
    }
    public Trip decide(UUID tripId, UUID proposalId, String bearer, DecisionRequest request) {
        TripEntity trip = access.lock(tripId, bearer, Role.OWNER, Role.DRIVER).trip();
        access.requireSharing(trip);
        ProposalEntity proposal = find(tripId, proposalId);
        ProposalStatus target = request.decision() == Decision.ACCEPT ? ProposalStatus.APPLY_REQUESTED : ProposalStatus.DISMISSED;
        // Same decision replay is harmless only with the version from that original decision.
        if (proposal.status == target && proposal.revision == request.expectedVersion() + 1) return views.snapshot(trip);
        if (proposal.revision != request.expectedVersion()) throw ApiException.conflict("VERSION_CONFLICT", "Refresh the proposal before deciding.");
        if (proposal.status != ProposalStatus.PENDING) throw ApiException.conflict("PROPOSAL_FINALIZED", "This proposal is no longer pending.");
        if (target == ProposalStatus.APPLY_REQUESTED && proposals.findByTripIdOrderById(tripId).stream().anyMatch(p -> p.status == ProposalStatus.APPLY_REQUESTED))
            throw ApiException.conflict("APPLY_IN_PROGRESS", "Wait for the current navigation request to finish.");
        proposal.status = target; proposal.revision++; trip.changed();
        return views.snapshot(trip);
    }
    public Trip report(UUID tripId, UUID proposalId, String bearer, NavigationResultRequest request) {
        TripEntity trip = access.lock(tripId, bearer, Role.OWNER, Role.DRIVER).trip();
        access.requireSharing(trip);
        ProposalEntity proposal = find(tripId, proposalId);
        if (request.outcome() == Outcome.APPLIED && !navigation.canConfirmRealApplication())
            throw ApiException.conflict("NAVIGATION_UNAVAILABLE", "A verified navigation integration is not connected.");
        ProposalStatus target = ProposalStatus.valueOf(request.outcome().name());
        String reason = request.reason() == null ? null : request.reason().strip();
        if (proposal.status == target && Objects.equals(proposal.resultReason, reason)) return views.snapshot(trip);
        if (proposal.status != ProposalStatus.APPLY_REQUESTED) throw ApiException.conflict("PROPOSAL_FINALIZED", "No navigation application is awaiting a result.");
        proposal.status = target; proposal.resultReason = reason; proposal.revision++; trip.changed();
        return views.snapshot(trip);
    }
    private ProposalEntity find(UUID tripId, UUID proposalId) {
        return proposals.findById(proposalId).filter(p -> p.tripId.equals(tripId))
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "PROPOSAL_NOT_FOUND", "Proposal not found in this trip."));
    }
}
