package ai.yeouro.v2.common;

import ai.yeouro.api.ApiException;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
@ConditionalOnProperty(name = "yeouro.v2.enabled", havingValue = "true")
public class IdempotencyService {
  private final JdbcTemplate db;
  private final ObjectMapper json;
  private final Clock clock;

  public IdempotencyService(JdbcTemplate db, ObjectMapper json, Clock clock) {
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  @SuppressWarnings("unchecked")
  public Map<String, Object> execute(
      String principal,
      String route,
      String key,
      Object payload,
      boolean secret,
      Supplier<Map<String, Object>> work) {
    if (key == null || key.isBlank() || key.length() > 128)
      throw ApiException.invalid(
          "IDEMPOTENCY_KEY_REQUIRED", "A key of at most 128 characters is required.");
    byte[] hash =
        Secrets.hash(json.writeValueAsString(canonical(json.convertValue(payload, Object.class))));
    // PostgreSQL transaction advisory lock serializes a principal/route/key before first insert.
    db.queryForList(
        "SELECT pg_advisory_xact_lock(hashtextextended(?,0))", principal + "|" + route + "|" + key);
    var rows =
        db.queryForList(
            "SELECT request_hash,response_json,secret_response FROM app.idempotency_records WHERE"
                + " principal=? AND route=? AND idempotency_key=? AND expires_at>?",
            principal,
            route,
            key,
            java.sql.Timestamp.from(clock.instant()));
    if (!rows.isEmpty()) {
      var row = rows.getFirst();
      if (!Arrays.equals(hash, (byte[]) row.get("request_hash")))
        throw ApiException.conflict(
            "IDEMPOTENCY_KEY_REUSED", "Key already used with another request.");
      if ((Boolean) row.get("secret_response"))
        throw ApiException.conflict(
            "SECRET_ALREADY_ISSUED", "Issue a new secret; previous secret cannot be recovered.");
      return json.readValue((String) row.get("response_json"), Map.class);
    }
    db.update(
        "DELETE FROM app.idempotency_records WHERE principal=? AND route=? AND idempotency_key=?",
        principal,
        route,
        key);
    Map<String, Object> result = work.get();
    db.update(
        "INSERT INTO"
            + " app.idempotency_records(principal,route,idempotency_key,request_hash,response_json,secret_response,expires_at)"
            + " VALUES (?,?,?,?,?,?,?)",
        principal,
        route,
        key,
        hash,
        secret ? null : json.writeValueAsString(result),
        secret,
        java.sql.Timestamp.from(clock.instant().plus(Duration.ofHours(24))));
    return result;
  }

  private Object canonical(Object value) {
    if (value instanceof Map<?, ?> m) {
      Map<String, Object> sorted = new TreeMap<>();
      m.forEach((k, v) -> sorted.put(k.toString(), canonical(v)));
      return sorted;
    }
    if (value instanceof List<?> l) return l.stream().map(this::canonical).toList();
    return value;
  }
}
