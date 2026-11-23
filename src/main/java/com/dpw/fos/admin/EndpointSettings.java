package com.dpw.fos.admin;

import com.dpw.fos.config.FosProperties;
import com.dpw.fos.domain.*;
import com.dpw.fos.persistence.InboxRepository;
import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EndpointSettings {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final FosProperties properties;
  private final Environment env;
  private final InboxRepository inbox;

  public EndpointSettings(
      JdbcTemplate db,
      TransactionTemplate tx,
      FosProperties properties,
      Environment env,
      InboxRepository inbox) {
    this.db = db;
    this.tx = tx;
    this.properties = properties;
    this.env = env;
    this.inbox = inbox;
  }

  private MessageType type(String route) {
    return Arrays.stream(MessageType.values())
        .filter(t -> t.route.equals(route))
        .findFirst()
        .orElseThrow(() -> new FosException(400, "UNKNOWN_ROUTE"));
  }

  public String validate(String url) {
    try {
      URI u = URI.create(url);
      boolean local =
          Arrays.asList(env.getActiveProfiles()).contains("local") && !properties.production();
      if (url.length() > 1000
          || u.getHost() == null
          || u.getUserInfo() != null
          || u.getQuery() != null
          || u.getFragment() != null
          || u.getPort() == 0
          || u.getPort() > 65535
          || !("https".equals(u.getScheme()) || (local && "http".equals(u.getScheme())))
          || properties.allowedHosts() == null
          || properties.allowedHosts().stream().noneMatch(h -> h.equalsIgnoreCase(u.getHost())))
        throw new IllegalArgumentException();
      return u.toString();
    } catch (IllegalArgumentException ex) {
      throw new FosException(400, "ENDPOINT_URL_NOT_ALLOWED");
    }
  }

  public FosProperties.Target resolve(String route) {
    type(route);
    var baseline = properties.targets() == null ? null : properties.targets().get(route);
    var rows =
        db.queryForList(
            "SELECT url,idempotency_confirmed FROM endpoint_setting WHERE route=?", route);
    if (rows.isEmpty() || rows.getFirst().get("url") == null) return baseline;
    var row = rows.getFirst();
    String url = validate((String) row.get("url")); // Revalidate after deployment policy changes.
    boolean confirmed = Boolean.TRUE.equals(row.get("idempotency_confirmed"));
    if (properties.production() && !confirmed)
      throw new FosException(409, "DEDUPLICATION_REQUIRED");
    return new FosProperties.Target(
        url, baseline == null ? null : baseline.bearerToken(), confirmed);
  }

  public FosProperties.Target resolve(com.dpw.fos.persistence.DeliveryRepository.Work work) {
    // Keep retries on their first attempted destination, even when an operator edits a route.
    var attempts =
        db.queryForList(
            "SELECT destination,idempotency_confirmed FROM delivery_attempt WHERE delivery_id=? AND"
                + " destination IS NOT NULL ORDER BY attempt_number LIMIT 1",
            work.id());
    if (attempts.isEmpty()) return resolve(work.type().route);
    var first = attempts.getFirst();
    var baseline =
        properties.targets() == null ? null : properties.targets().get(work.type().route);
    return new FosProperties.Target(
        validate((String) first.get("destination")),
        baseline == null ? null : baseline.bearerToken(),
        Boolean.TRUE.equals(first.get("idempotency_confirmed")));
  }

  public List<Map<String, Object>> list() {
    var rows = db.queryForList("SELECT * FROM endpoint_setting ORDER BY route");
    for (var row : rows) {
      String route = (String) row.get("route");
      var baseline = properties.targets() == null ? null : properties.targets().get(route);
      boolean overridden = row.get("url") != null;
      row.put("system", type(route).target);
      row.put(
          "effective_url", overridden ? row.get("url") : baseline == null ? "" : baseline.url());
      row.put(
          "effective_confirmed",
          overridden
              ? row.get("idempotency_confirmed")
              : baseline != null && baseline.idempotencyConfirmed());
      row.put("origin", overridden ? "Saved in console" : "Deployment configuration");
      row.put(
          "credential_configured",
          baseline != null && baseline.bearerToken() != null && !baseline.bearerToken().isBlank());
    }
    return rows;
  }

  public List<String> allowedHosts() {
    return properties.allowedHosts() == null ? List.of() : properties.allowedHosts();
  }

  public void save(
      String route,
      String url,
      boolean confirmed,
      long revision,
      String reason,
      String actor,
      boolean reset) {
    type(route);
    if (reason == null || reason.isBlank() || reason.length() > 512)
      throw new FosException(400, "CHANGE_REASON_REQUIRED");
    String destination = reset ? null : validate(url == null ? "" : url.trim());
    if (!reset && properties.production() && !confirmed)
      throw new FosException(400, "DEDUPLICATION_REQUIRED");
    tx.executeWithoutResult(
        s -> {
          if (db.update(
                  "UPDATE endpoint_setting SET"
                      + " url=?,idempotency_confirmed=?,revision=revision+1,updated_by=?,updated_at=clock_timestamp()"
                      + " WHERE route=? AND revision=?",
                  destination,
                  confirmed,
                  actor,
                  route,
                  revision)
              != 1) throw new FosException(409, "SETTINGS_CHANGED_REFRESH");
          inbox.audit(
              actor,
              "ENDPOINT_CHANGE",
              null,
              route + " | " + (reset ? "deployment default" : destination) + " | " + reason,
              "OK");
        });
  }
}
