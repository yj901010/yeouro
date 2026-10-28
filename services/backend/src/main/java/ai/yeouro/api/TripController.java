package ai.yeouro.api;

import ai.yeouro.api.ApiModels.*;
import ai.yeouro.sharing.SharingService;
import ai.yeouro.trip.*;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name="yeouro.v1.enabled",havingValue="true",matchIfMissing=true)
@RestController @RequestMapping("/api/v1")
public class TripController {
    private final TripService trips;
    private final SharingService sharing;
    private final ProposalService proposals;
    public TripController(TripService trips, SharingService sharing, ProposalService proposals) {
        this.trips = trips; this.sharing = sharing; this.proposals = proposals;
    }
    @GetMapping("/health") public Health health() { return new Health("UP"); }
    @PostMapping("/trips") @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@Valid @RequestBody CreateTripRequest request) { return trips.create(request); }
    @GetMapping("/trips/{tripId}")
    public Trip get(@PathVariable UUID tripId, @RequestHeader(value="Authorization",required=false) String bearer) { return trips.get(tripId, bearer); }
    @PostMapping("/trips/{tripId}/invites") @ResponseStatus(HttpStatus.CREATED)
    public InviteResponse invite(@PathVariable UUID tripId, @RequestHeader(value="Authorization",required=false) String bearer,
                                 @Valid @RequestBody ConsentRequest request) { return sharing.invite(tripId, bearer, request); }
    @PostMapping("/invites/join") @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse join(@Valid @RequestBody JoinRequest request) { return sharing.join(request); }
    @PutMapping("/trips/{tripId}/readiness")
    public Trip ready(@PathVariable UUID tripId, @RequestHeader(value="Authorization",required=false) String bearer,
                      @Valid @RequestBody ReadinessRequest request) { return sharing.readiness(tripId, bearer, request); }
    @PostMapping("/trips/{tripId}/proposals") @ResponseStatus(HttpStatus.CREATED)
    public Trip propose(@PathVariable UUID tripId, @RequestHeader(value="Authorization",required=false) String bearer,
                        @Valid @RequestBody ProposalRequest request) { return proposals.submit(tripId, bearer, request); }
    @PostMapping("/trips/{tripId}/proposals/{proposalId}/decision")
    public Trip decide(@PathVariable UUID tripId, @PathVariable UUID proposalId,
                       @RequestHeader(value="Authorization",required=false) String bearer,
                       @Valid @RequestBody DecisionRequest request) { return proposals.decide(tripId, proposalId, bearer, request); }
    @PostMapping("/trips/{tripId}/proposals/{proposalId}/navigation-result")
    public Trip result(@PathVariable UUID tripId, @PathVariable UUID proposalId,
                       @RequestHeader(value="Authorization",required=false) String bearer,
                       @Valid @RequestBody NavigationResultRequest request) { return proposals.report(tripId, proposalId, bearer, request); }
    @PutMapping("/trips/{tripId}/location")
    public Trip location(@PathVariable UUID tripId, @RequestHeader(value="Authorization",required=false) String bearer,
                         @Valid @RequestBody Location request) { return trips.location(tripId, bearer, request); }
    @PostMapping("/trips/{tripId}/sharing/revoke")
    public Trip revoke(@PathVariable UUID tripId, @RequestHeader(value="Authorization",required=false) String bearer) { return sharing.revoke(tripId, bearer); }
    @PostMapping("/trips/{tripId}/end")
    public Trip end(@PathVariable UUID tripId, @RequestHeader(value="Authorization",required=false) String bearer) { return trips.end(tripId, bearer); }
}
