package com.dpw.fos.simulator;

import com.dpw.fos.domain.FosException;
import com.dpw.fos.modules.ModuleControl;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.*;
import jakarta.annotation.PreDestroy;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.core.env.Environment;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SimulatorPlugin {
  private final SimulatorProperties p;
  private final ModuleControl controls;
  private final ObjectMapper mapper;
  private final KafkaTemplate<String, byte[]> kafka;
  private final Environment env;
  private final HttpClient client =
      HttpClient.newBuilder()
          .connectTimeout(Duration.ofSeconds(3))
          .followRedirects(HttpClient.Redirect.NEVER)
          .build();
  private final ExecutorService jobs =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new ArrayBlockingQueue<>(20),
          r -> {
            Thread t = new Thread(r, "simulator-job");
            t.setDaemon(true);
            return t;
          });
  private final ExecutorService httpPool =
      Executors.newFixedThreadPool(
          2,
          r -> {
            Thread t = new Thread(r, "simulator-http");
            t.setDaemon(true);
            return t;
          });
  private final Deque<Map<String, String>> history = new ArrayDeque<>();
  private final Map<String, JsonNode> seen = new LinkedHashMap<>();
  private volatile HttpServer server;
  private volatile String state = "DISABLED";

  public SimulatorPlugin(
      SimulatorProperties p,
      ModuleControl controls,
      ObjectMapper mapper,
      KafkaTemplate<String, byte[]> kafka,
      Environment env) {
    this.p = p;
    this.controls = controls;
    this.mapper = mapper;
    this.kafka = kafka;
    this.env = env;
  }

  public String getState() {
    return state;
  }

  public boolean isAvailable() {
    return p.available();
  }

  public synchronized List<Map<String, String>> history() {
    return new ArrayList<>(history);
  }

  private synchronized void event(String step, String id, String result) {
    history.addFirst(
        Map.of(
            "time", OffsetDateTime.now().toString(), "step", step, "msgId", id, "result", result));
    while (history.size() > 200) history.removeLast();
  }

  @Scheduled(fixedDelay = 1000)
  public synchronized void reconcile() {
    boolean enabled;
    try {
      enabled = p.available() && controls.enabled("simulator");
    } catch (Exception e) {
      enabled = false;
    }
    if (!enabled) {
      if (server != null) {
        server.stop(0);
        server = null;
      }
      state = "DISABLED";
      return;
    }
    if (server == null)
      try {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", p.port()), 0);
        s.createContext("/", this::receive);
        s.setExecutor(httpPool);
        s.start();
        server = s;
        state = "RUNNING";
      } catch (Exception e) {
        state = "PORT_UNAVAILABLE";
      }
  }

  public void run(int count, String mode) {
    if (!"RUNNING".equals(state) || !controls.enabled("simulator"))
      throw new FosException(409, "SIMULATOR_DISABLED");
    if (count < 1 || count > 10 || !Set.of("SUCCESS", "REJECT").contains(mode))
      throw new FosException(400, "INVALID_SCENARIO");
    try {
      jobs.submit(
          () -> {
            for (int i = 0; i < count; i++) {
              if (!active()) break;
              String id = "SIM-" + mode + "-" + UUID.randomUUID(),
                  vehicle = "SIM-" + UUID.randomUUID().toString().substring(0, 8);
              try {
                post(
                    "/fos/api/v1/inbound/fms/vehicle-login-logout",
                    p.fmsUser(),
                    SimulationPayloads.create(
                        mapper, "vehicle-login-logout", id + "-LOGIN", vehicle, id));
                post(
                    "/fos/api/v1/inbound/tos/vehicle-jobs",
                    p.tosUser(),
                    SimulationPayloads.create(mapper, "vehicle-job", id, vehicle, id));
              } catch (Exception e) {
                event("Scenario", id, "FAILED: " + e.getClass().getSimpleName());
              }
            }
          });
    } catch (RejectedExecutionException e) {
      throw new FosException(429, "SIMULATOR_BUSY");
    }
  }

  private boolean active() {
    return server != null && controls.enabled("simulator");
  }

  private void post(String path, String user, ObjectNode payload) throws Exception {
    if (!active()) return;
    String auth =
        Base64.getEncoder()
            .encodeToString((user + ":" + p.password()).getBytes(StandardCharsets.UTF_8));
    var request =
        HttpRequest.newBuilder(URI.create(p.apiBaseUrl() + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("Authorization", "Basic " + auth)
            .POST(HttpRequest.BodyPublishers.ofByteArray(mapper.writeValueAsBytes(payload)))
            .build();
    var response = client.send(request, HttpResponse.BodyHandlers.ofString());
    event(
        path.contains("tos/") ? "TOS → FOS job" : "FMS → FOS login",
        payload.path("msgId").asText(),
        "HTTP " + response.statusCode());
    if (response.statusCode() != 200 && response.statusCode() != 202)
      throw new IllegalStateException("Ingress rejected");
  }

  private void receive(HttpExchange exchange) throws java.io.IOException {
    try {
      if (!active()) {
        reply(exchange, 503, "", "DISABLED");
        return;
      }
      String path = exchange.getRequestURI().getPath();
      if (!Set.of(
              "/vehicle-job",
              "/vehicle-login-logout",
              "/vehicle-yard-event",
              "/vehicle-operation-status")
          .contains(path)) {
        reply(exchange, 404, "", "NOT_FOUND");
        return;
      }
      if (!exchange.getRequestMethod().equals("POST")) {
        reply(exchange, 405, "", "METHOD_NOT_ALLOWED");
        return;
      }
      byte[] bytes = exchange.getRequestBody().readNBytes(262145);
      if (bytes.length > 262144) {
        reply(exchange, 413, "", "TOO_LARGE");
        return;
      }
      JsonNode body = mapper.readTree(bytes);
      String id = body.path("msgId").asText();
      if (!id.startsWith("SIM-") || !body.path("vehicleId").asText().startsWith("SIM-")) {
        reply(exchange, 400, id, "SIMULATED_IDS_REQUIRED");
        return;
      }
      boolean reject = id.startsWith("SIM-REJECT-") && path.equals("/vehicle-job");
      String key = path + ":" + id;
      synchronized (this) {
        JsonNode previous = seen.get(key);
        if (previous != null) {
          reply(
              exchange,
              previous.equals(body) ? 200 : 409,
              id,
              previous.equals(body) ? (reject ? "SIM_REJECTED" : "0") : "CONFLICT");
          return;
        }
        if (seen.size() >= 1000) {
          reply(exchange, 429, id, "SIMULATOR_CAPACITY");
          return;
        }
        if (path.equals("/vehicle-job") && !reject) {
          try {
            jobs.submit(() -> respond(body));
          } catch (RejectedExecutionException e) {
            reply(exchange, 503, id, "SIMULATOR_BUSY");
            return;
          }
        }
        seen.put(key, body.deepCopy());
      }
      event(
          path.equals("/vehicle-job") ? "FOS → FMS job" : "FOS → TOS " + path.substring(1),
          id,
          reject ? "BUSINESS_REJECTED" : "COMPLETED");
      reply(exchange, 200, id, reject ? "SIM_REJECTED" : "0");
    } catch (Exception e) {
      reply(exchange, 400, "", "INVALID_SIMULATION_MESSAGE");
    } finally {
      exchange.close();
    }
  }

  private void respond(JsonNode job) {
    String id = job.path("msgId").asText(), vehicle = job.path("vehicleId").asText();
    try {
      if (!active()) return;
      ObjectNode yard =
          SimulationPayloads.create(
              mapper, "vehicle-yard-event", id + "-YARD", vehicle, job.path("jobId").asText());
      publish(env.getRequiredProperty("fos.kafka.yard-topic"), vehicle, yard);
      for (int status : new int[] {1, 2, 3, 4, 5, 6}) {
        if (!active()) return;
        ObjectNode ops =
            SimulationPayloads.create(
                mapper, "vehicle-operation-status", id + "-OPS-" + status, vehicle, id);
        ops.put("TPStatus", status);
        publish(env.getRequiredProperty("fos.kafka.operation-topic"), vehicle, ops);
      }
    } catch (Exception e) {
      event("FMS → Kafka", id, "FAILED: " + e.getClass().getSimpleName());
    }
  }

  private void publish(String topic, String vehicle, ObjectNode body) throws Exception {
    if (!active()) return;
    kafka.send(topic, vehicle, mapper.writeValueAsBytes(body)).get(10, TimeUnit.SECONDS);
    event("FMS → Kafka → FOS", body.path("msgId").asText(), "BROKER_ACKNOWLEDGED");
  }

  private void reply(HttpExchange e, int status, String id, String code)
      throws java.io.IOException {
    byte[] bytes =
        mapper.writeValueAsBytes(
            Map.of(
                "msgId",
                id,
                "code",
                code,
                "message",
                code.equals("0") ? "success" : code,
                "data",
                Map.of()));
    e.getResponseHeaders().set("Content-Type", "application/json");
    e.sendResponseHeaders(status, bytes.length);
    e.getResponseBody().write(bytes);
  }

  @PreDestroy
  public synchronized void stop() {
    if (server != null) {
      server.stop(0);
      server = null;
    }
    jobs.shutdownNow();
    httpPool.shutdownNow();
  }
}
