package com.dpw.fos.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dpw.fos.modules.ModuleControl;
import com.dpw.fos.persistence.InboxRepository;
import com.dpw.fos.simulator.SimulatorPlugin;
import com.dpw.fos.uitv.UitvBridge;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "management.endpoint.health.group.readiness.include=readinessState",
      "fos.workers.enabled=false",
      "fos.kafka.enabled=false",
      "fos.simulator.available=false",
      "fos.uitv.available=false",
      "fos.admin.enabled=true",
      "fos.admin.username=admin",
      "fos.admin.password-hash=",
      "fos.production=false",
      "fos.dev-auth=true",
      "fos.local-password=admin-test-password-only",
      "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration"
    })
@ActiveProfiles("local")
class AdminPagesTest {
  @LocalServerPort int port;
  @MockBean ModuleControl modules;
  @MockBean InboxRepository inbox;
  @MockBean SimulatorPlugin simulator;
  @MockBean UitvBridge bridge;
  @MockBean com.dpw.fos.simulator.SimulatorConsole console;
  @MockBean JdbcTemplate jdbc;
  @MockBean TransactionTemplate transaction;
  @MockBean EndpointSettings endpoints;
  @MockBean MessageHistory history;
  HttpClient client;

  @BeforeEach
  void setup() {
    client =
        HttpClient.newBuilder()
            .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    when(modules.list())
        .thenReturn(
            List.of(
                Map.of(
                    "name",
                    "uitv",
                    "enabled",
                    false,
                    "updated_by",
                    "test",
                    "updated_at",
                    "today")));
    when(inbox.list(0, 25, null)).thenReturn(List.of());
    when(simulator.getState()).thenReturn("DISABLED");
    when(bridge.getState()).thenReturn("DISABLED");
    when(bridge.isAvailable()).thenReturn(true);
    when(simulator.history()).thenReturn(List.of());
  }

  HttpResponse<String> get(String path) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET().build(),
        HttpResponse.BodyHandlers.ofString());
  }

  HttpResponse<String> post(String path, String body) throws Exception {
    return client.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  String csrf(String html) {
    Matcher m = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"").matcher(html);
    assertTrue(m.find(), html);
    return m.group(1);
  }

  String signIn() throws Exception {
    var login = get("/admin/login");
    assertEquals(200, login.statusCode());
    String token = csrf(login.body());
    var result =
        post(
            "/admin/login",
            "username=admin&password=admin-test-password-only&_csrf="
                + URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(302, result.statusCode());
    return csrf(get("/admin").body());
  }

  @Test
  void liveConsoleRequiresLoginAndCsrfAndRendersEditor() throws Exception {
    assertEquals(302, get("/admin/simulator/console/status").statusCode());
    String token = signIn();
    String html = get("/admin").body();
    assertTrue(html.contains("id=\"job-payload\""));
    assertTrue(html.contains("id=\"vehicle-status\""));
    assertTrue(html.contains("simulator.js"));
    assertEquals(
        403,
        post("/admin/simulator/console/dispatch", "payload={}&mode=REAL&revision=0").statusCode());
    verify(console, never()).dispatch(anyString(), anyString(), anyLong(), anyString());
    when(console.snapshot()).thenReturn(Map.of("vehicles", List.of(), "events", List.of()));
    assertEquals(200, get("/admin/simulator/console/status").statusCode());
    assertEquals(
        200,
        post(
                "/admin/simulator/console/session",
                "vehicle=SIM-1&action=IN&_csrf="
                    + URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8))
            .statusCode());
    verify(console).session("SIM-1", "IN", "admin");
  }

  @Test
  void actualJspRendersAndControlsRequireCsrf() throws Exception {
    assertEquals(302, get("/admin").statusCode());
    String token = signIn();
    var dashboard = get("/admin");
    assertEquals(200, dashboard.statusCode());
    assertTrue(dashboard.body().contains("UITV transformation preview"));
    assertTrue(dashboard.body().contains("TOS / FMS simulator"));
    assertFalse(dashboard.body().contains("<%@"));
    java.nio.file.Files.writeString(
        java.nio.file.Path.of("target/admin-preview.html"), dashboard.body());
    assertEquals(403, post("/admin/modules/uitv", "enabled=true&reason=test").statusCode());
    verify(modules, never()).set(anyString(), anyBoolean(), anyString(), anyString());
    assertEquals(
        302,
        post(
                "/admin/modules/uitv",
                "enabled=true&reason=test&_csrf="
                    + URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8))
            .statusCode());
    verify(modules).set("uitv", true, "admin", "test");
  }

  @Test
  void previewEscapesUserTextAndDoesNotPublish() throws Exception {
    String token = signIn();
    String payload =
        "{\"AssetNumber\":\"<script>alert(1)</script>\",\"EnergySource\":\"ELEC\",\"BatterySoC\":0}";
    var result =
        post(
            "/admin/uitv/preview",
            "_csrf="
                + URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8)
                + "&payload="
                + URLEncoder.encode(payload, java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(302, result.statusCode());
    String html = get("/admin").body();
    assertTrue(html.contains("&lt;script&gt;"));
    assertFalse(html.contains("<script>alert(1)</script>"));
    verify(modules, never()).set(anyString(), anyBoolean(), anyString(), anyString());
  }

  @Test
  void operationsPagesRenderEscapePayloadsAndProtectSettings() throws Exception {
    assertEquals(302, get("/admin/endpoints").statusCode());
    assertEquals(302, get("/admin/kafka").statusCode());
    String token = signIn();
    when(endpoints.list())
        .thenReturn(
            List.of(
                Map.of(
                    "route",
                    "vehicle-job",
                    "system",
                    "FMS",
                    "effective_url",
                    "https://localhost/jobs",
                    "revision",
                    0,
                    "effective_confirmed",
                    true)));
    assertEquals(200, get("/admin/endpoints").statusCode());
    assertTrue(get("/admin/endpoints").body().contains("Save endpoint"));
    assertEquals(
        403,
        post("/admin/endpoints/vehicle-job", "url=https://localhost/jobs&reason=test&revision=0")
            .statusCode());
    verify(endpoints, never())
        .save(
            anyString(),
            anyString(),
            anyBoolean(),
            anyLong(),
            anyString(),
            anyString(),
            anyBoolean());
    assertEquals(
        302,
        post(
                "/admin/endpoints/vehicle-job",
                "url=https://localhost/jobs&reason=test&revision=0&_csrf="
                    + URLEncoder.encode(token, java.nio.charset.StandardCharsets.UTF_8))
            .statusCode());
    verify(endpoints)
        .save("vehicle-job", "https://localhost/jobs", false, 0, "test", "admin", false);
    assertEquals(200, get("/admin/messages").statusCode());
    assertEquals(200, get("/admin/kafka").statusCode());
    var id = UUID.randomUUID();
    when(history.detail(id))
        .thenReturn(
            Map.of(
                "msg_id",
                "<script>alert(1)</script>",
                "display_payload",
                "<img src=x onerror=alert(1)>",
                "state",
                "DELIVERED"));
    when(history.attempts(id))
        .thenReturn(List.of(Map.of("display_response", "<script>bad()</script>")));
    var detail = get("/admin/messages/" + id);
    assertEquals(200, detail.statusCode());
    assertTrue(detail.body().contains("&lt;script&gt;"));
    assertFalse(detail.body().contains("<script>"));
    when(history.kafkaDetail(id))
        .thenReturn(Map.of("topic", "yard", "display_payload", "<script>bad()</script>"));
    var kafka = get("/admin/kafka/" + id);
    assertEquals(200, kafka.statusCode());
    assertTrue(kafka.body().contains("&lt;script&gt;"));
  }
}
