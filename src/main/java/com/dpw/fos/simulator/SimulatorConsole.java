package com.dpw.fos.simulator;

import com.dpw.fos.config.FosProperties;
import com.dpw.fos.contract.ContractValidator;
import com.dpw.fos.domain.*;
import com.dpw.fos.modules.ModuleControl;
import com.dpw.fos.persistence.InboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class SimulatorConsole {
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final ContractValidator validator;
  private final InboxRepository inbox;
  private final ModuleControl modules;
  private final SimulatorProperties simulator;
  private final FosProperties fos;
  private final Environment env;
  private final ObjectMapper mapper;

  public SimulatorConsole(
      JdbcTemplate db,
      TransactionTemplate tx,
      ContractValidator validator,
      InboxRepository inbox,
      ModuleControl modules,
      SimulatorProperties simulator,
      FosProperties fos,
      Environment env,
      ObjectMapper mapper) {
    this.db = db;
    this.tx = tx;
    this.validator = validator;
    this.inbox = inbox;
    this.modules = modules;
    this.simulator = simulator;
    this.fos = fos;
    this.env = env;
    this.mapper = mapper;
  }

  private void available() {
    if (!simulator.available()
        || fos.production()
        || !fos.devAuth()
        || !Arrays.asList(env.getActiveProfiles()).contains("local"))
      throw new FosException(409, "SIMULATOR_UNAVAILABLE");
  }

  private void active() {
    available();
    if (!modules.enabled("simulator")) throw new FosException(409, "SIMULATOR_DISABLED");
  }

  public Map<String, Object> settings() {
    available();
    return db.queryForMap(
        "SELECT mode,fms_url,idempotency_confirmed,revision,updated_by FROM simulator_console WHERE"
            + " id=1");
  }

  public void configure(String mode, String url, boolean idempotency, long revision, String actor) {
    available();
    if (!Set.of("REAL", "SIMULATED").contains(mode)) throw new FosException(400, "INVALID_MODE");
    String destination = url == null ? "" : url.trim();
    if (mode.equals("REAL") || !destination.isEmpty()) validateUrl(destination);
    tx.executeWithoutResult(
        s -> {
          if (db.update(
                  "UPDATE simulator_console SET"
                      + " mode=?,fms_url=?,idempotency_confirmed=?,revision=revision+1,updated_by=?,updated_at=clock_timestamp()"
                      + " WHERE id=1 AND revision=?",
                  mode,
                  destination,
                  idempotency,
                  actor,
                  revision)
              != 1) throw new FosException(409, "SETTINGS_CHANGED_REFRESH");
          inbox.audit(actor, "SIMULATOR_CONFIG", null, mode + ": " + destination, "OK");
        });
  }

  public String validateUrl(String value) {
    try {
      URI u = URI.create(value);
      Set<String> hosts = new HashSet<>();
      for (String host :
          env.getProperty("fos.simulator.real-fms-allowed-hosts", "localhost,127.0.0.1").split(","))
        hosts.add(host.trim().toLowerCase(Locale.ROOT));
      if (value.length() > 1000
          || u.getHost() == null
          || !Set.of("http", "https").contains(u.getScheme())
          || u.getUserInfo() != null
          || u.getQuery() != null
          || u.getFragment() != null
          || u.getPort() > 65535
          || u.getPort() == 0
          || !hosts.contains(u.getHost().toLowerCase(Locale.ROOT)))
        throw new IllegalArgumentException();
      return u.toString();
    } catch (IllegalArgumentException e) {
      throw new FosException(400, "INVALID_FMS_URL_OR_HOST_NOT_ALLOWED");
    }
  }

  public Map<String, Object> snapshot() {
    var config = settings();
    return Map.of(
        "settings",
        config,
        "enabled",
        modules.enabled("simulator"),
        "vehicles",
        db.queryForList(
            "SELECT origin,vehicle_id,action,user_id,user_name,event_time,received_at FROM"
                + " vehicle_session ORDER BY received_at DESC LIMIT 500"),
        "events",
        db.queryForList(
            "SELECT origin,vehicle_id,action,user_id,user_name,event_time,received_at,receipt_id"
                + " FROM vehicle_session_event ORDER BY received_at DESC,receipt_id DESC LIMIT"
                + " 100"),
        "dispatches",
        db.queryForList(
            "SELECT"
                + " s.receipt_id,s.mode,s.destination,i.msg_id,i.vehicle_id,d.state,d.last_outcome,s.created_at"
                + " FROM simulator_dispatch s JOIN message_inbox i USING(receipt_id) JOIN delivery"
                + " d USING(receipt_id) ORDER BY s.created_at DESC LIMIT 50"));
  }

  public String template(String vehicle) {
    available();
    try {
      String id = "SIM-JOB-" + UUID.randomUUID();
      return SimulationPayloads.create(mapper, "vehicle-job", id, vehicle, id).toPrettyString();
    } catch (java.io.IOException e) {
      throw new IllegalStateException(e);
    }
  }

  public Object validate(String payload) {
    available();
    var v = validator.validate(MessageType.VEHICLE_JOB, payload.getBytes(StandardCharsets.UTF_8));
    return Map.of("valid", true, "vehicleId", v.vehicleId(), "msgId", v.msgId());
  }

  public InboxRepository.Receipt dispatch(
      String payload, String expectedMode, long revision, String actor) {
    active();
    var v = validator.validate(MessageType.VEHICLE_JOB, payload.getBytes(StandardCharsets.UTF_8));
    return tx.execute(
        s -> {
          var config = db.queryForMap("SELECT * FROM simulator_console WHERE id=1 FOR SHARE");
          String mode = (String) config.get("mode");
          if (!mode.equals(expectedMode)
              || ((Number) config.get("revision")).longValue() != revision)
            throw new FosException(409, "SETTINGS_CHANGED_REFRESH");
          if (mode.equals("SIMULATED")
              && (!v.vehicleId().startsWith("SIM-") || !v.msgId().startsWith("SIM-")))
            throw new FosException(400, "SIMULATED_IDS_REQUIRED");
          String url =
              mode.equals("REAL")
                  ? validateUrl((String) config.get("fms_url"))
                  : "http://127.0.0.1:" + simulator.port() + "/vehicle-job";
          db.queryForObject(
              "SELECT pg_advisory_xact_lock(hashtextextended(?,0)) IS NULL",
              Boolean.class,
              "session:" + mode + ":" + v.vehicleId());
          Boolean loggedIn =
              db.queryForObject(
                  "SELECT EXISTS(SELECT 1 FROM vehicle_session WHERE origin=? AND vehicle_id=? AND"
                      + " action='IN')",
                  Boolean.class,
                  mode,
                  v.vehicleId());
          if (!Boolean.TRUE.equals(loggedIn)) throw new FosException(409, "VEHICLE_NOT_LOGGED_IN");
          // Refuse reusing an identity for another destination, including ordinary API jobs.
          var prior =
              db.queryForList(
                  "SELECT i.receipt_id,s.mode,s.destination FROM message_inbox i LEFT JOIN"
                      + " simulator_dispatch s USING(receipt_id) WHERE i.source='TOS' AND"
                      + " i.message_type='VEHICLE_JOB' AND i.msg_id=?",
                  v.msgId());
          if (!prior.isEmpty()
              && (!mode.equals(prior.getFirst().get("mode"))
                  || !url.equals(prior.getFirst().get("destination"))))
            throw new FosException(409, "MESSAGE_ALREADY_ROUTED_USE_NEW_ID");
          var receipt =
              inbox.accept(MessageType.VEHICLE_JOB, v, "{\"transport\":\"SIMULATOR_UI\"}");
          db.update(
              "INSERT INTO"
                  + " simulator_dispatch(receipt_id,mode,destination,idempotency_confirmed,actor)"
                  + " VALUES(?,?,?,?,?) ON CONFLICT DO NOTHING",
              receipt.receiptId(),
              mode,
              url,
              mode.equals("SIMULATED") || Boolean.TRUE.equals(config.get("idempotency_confirmed")),
              actor);
          inbox.audit(
              actor, "SIMULATOR_DISPATCH", receipt.receiptId(), mode + ": " + url, "ACCEPTED");
          return receipt;
        });
  }

  public void session(String vehicle, String action, String actor) {
    active();
    if (!vehicle.startsWith("SIM-") || !Set.of("IN", "OUT").contains(action))
      throw new FosException(400, "INVALID_SIMULATED_SESSION");
    tx.executeWithoutResult(
        s -> {
          var config = db.queryForMap("SELECT mode FROM simulator_console WHERE id=1 FOR SHARE");
          if (!"SIMULATED".equals(config.get("mode")))
            throw new FosException(409, "REAL_MODE_REQUIRES_REAL_FMS_EVENTS");
          try {
            var body =
                SimulationPayloads.create(
                    mapper,
                    "vehicle-login-logout",
                    "SIM-SESSION-" + UUID.randomUUID(),
                    vehicle,
                    "");
            body.put("action", action);
            var receipt =
                inbox.acceptSimulatedSession(
                    validator.validate(MessageType.LOGIN_LOGOUT, mapper.writeValueAsBytes(body)));
            inbox.audit(
                actor,
                "SIMULATOR_SESSION",
                receipt.receiptId(),
                vehicle + ": " + action,
                "ACCEPTED");
          } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
          }
        });
  }
}
