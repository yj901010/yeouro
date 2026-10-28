package ai.yeouro.v2.planning;

import static ai.yeouro.v2.planning.PlanningModels.*;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

@Repository
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class PlanningStore {
  private final JdbcTemplate jdbc;

  // Module-private SQL operations. Never expose Spring-proxied bean fields or the JDBC handle.
  int update(String sql, Object... args) {
    return jdbc.update(sql, args);
  }

  <T> List<T> query(String sql, org.springframework.jdbc.core.RowMapper<T> mapper, Object... args) {
    return jdbc.query(sql, mapper, args);
  }

  private final ObjectMapper mapper;

  public PlanningStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  String json(Object value) {
    return mapper.writeValueAsString(value);
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> object(String json) {
    return mapper.readValue(json, Map.class);
  }

  List<Stop> stops(UUID trip) {
    return jdbc.query(
        "SELECT * FROM app.trip_stops WHERE trip_id=? ORDER BY sequence_no",
        (r, n) ->
            new Stop(
                mapper.readValue(r.getString("place_json"), Place.class),
                r.getInt("stay_minutes"),
                (Integer) r.getObject("walk_to_place_seconds"),
                (Integer) r.getObject("return_walk_seconds")),
        trip);
  }

  Instant anchor(UUID trip, Anchor anchor) {
    List<Instant> rows =
        jdbc.query(
            "SELECT m.occurred_at FROM app.trip_milestones m WHERE m.trip_id=? AND m.type=? AND"
                + " m.source='USER_CONFIRMED' AND m.occurred_at >= COALESCE((SELECT"
                + " max(e.observed_at) FROM app.trip_phase_events e WHERE e.trip_id=m.trip_id AND"
                + " e.phase='DRIVING'), '-infinity'::timestamptz) AND (m.type<>'PERSON_ARRIVED' OR"
                + " m.occurred_at >= COALESCE((SELECT max(p.occurred_at) FROM app.trip_milestones p"
                + " WHERE p.trip_id=m.trip_id AND p.type='PARKING_CONFIRMED'),"
                + " '-infinity'::timestamptz)) ORDER BY m.occurred_at DESC LIMIT 1",
            (r, n) -> r.getTimestamp(1).toInstant(),
            trip,
            anchor == Anchor.PARKING_CONFIRMED ? "PARKING_CONFIRMED" : "PERSON_ARRIVED");
    return rows.isEmpty() ? null : rows.getFirst();
  }

  static Timestamp time(Instant instant) {
    return instant == null
        ? null
        : Timestamp.from(instant.truncatedTo(java.time.temporal.ChronoUnit.MILLIS));
  }

  static Map<String, Object> map(Object... kv) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < kv.length; i += 2) result.put((String) kv[i], kv[i + 1]);
    return result;
  }
}
