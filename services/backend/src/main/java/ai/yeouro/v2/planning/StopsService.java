package ai.yeouro.v2.planning;

import static ai.yeouro.v2.planning.PlanningModels.*;
import static ai.yeouro.v2.planning.PlanningStore.map;

import ai.yeouro.v2.trip.TripAccess;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class StopsService {
  private final TripAccess access;
  private final PlanningStore store;
  private final ReminderService reminders;

  public StopsService(TripAccess access, PlanningStore store, ReminderService reminders) {
    this.access = access;
    this.store = store;
    this.reminders = reminders;
  }

  public List<Stop> read(TripAccess.Context context) {
    return store.stops(context.tripId());
  }

  @Transactional
  public Map<String, Object> replace(
      UUID trip, String bearer, String ifMatch, StopsRequest request) {
    var c = access.require(trip, bearer, TripAccess.Permission.OWNER_OR_DRIVER);
    access.checkPlan(c, ifMatch);
    request.stops().forEach(s -> PlanningPolicy.place(s.place()));
    store.update("DELETE FROM app.trip_stops WHERE trip_id=?", trip);
    int i = 0;
    for (Stop stop : request.stops())
      store.update(
          "INSERT INTO"
              + " app.trip_stops(id,trip_id,sequence_no,place_json,stay_minutes,walk_to_place_seconds,return_walk_seconds)"
              + " VALUES (?,?,?,?,?,?,?)",
          UUID.randomUUID(),
          trip,
          i++,
          store.json(stop.place()),
          stop.stayMinutes(),
          stop.walkToPlaceSeconds(),
          stop.returnWalkSeconds());
    access.bump(trip, true);
    reminders.invalidateForPlanChange(trip);
    return map(
        "stops",
        request.stops(),
        "planRevision",
        c.planRevision() + 1,
        "revision",
        c.revision() + 1);
  }
}
