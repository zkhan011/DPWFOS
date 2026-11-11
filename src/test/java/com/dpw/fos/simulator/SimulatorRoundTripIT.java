package com.dpw.fos.simulator;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.dpw.fos.FosApplication;
import com.dpw.fos.domain.FosException;
import com.dpw.fos.modules.ModuleControl;
import java.net.ServerSocket;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.testcontainers.kafka.ConfluentKafkaContainer;

@Testcontainers
@ActiveProfiles("local")
@SpringBootTest(
    classes = FosApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
    properties = {
      "management.server.port=0",
      "fos.admin.enabled=false",
      "fos.local-password=simulator-integration-test-only"
    })
class SimulatorRoundTripIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

  @Container
  static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

  static final int API_PORT = freePort(), MOCK_PORT = freePort();

  static int freePort() {
    try (var s = new ServerSocket(0)) {
      return s.getLocalPort();
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("server.port", () -> API_PORT);
    p.add("fos.simulator.port", () -> MOCK_PORT);
    p.add("fos.simulator.api-base-url", () -> "http://127.0.0.1:" + API_PORT);
    p.add("fos.simulator.password", () -> "simulator-integration-test-only");
    for (String route :
        List.of(
            "vehicle-job",
            "vehicle-login-logout",
            "vehicle-yard-event",
            "vehicle-operation-status"))
      p.add("fos.targets." + route + ".url", () -> "http://127.0.0.1:" + MOCK_PORT + "/" + route);
    p.add("spring.datasource.url", postgres::getJdbcUrl);
    p.add("spring.datasource.username", postgres::getUsername);
    p.add("spring.datasource.password", postgres::getPassword);
    p.add("spring.flyway.user", postgres::getUsername);
    p.add("spring.flyway.password", postgres::getPassword);
    p.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      admin
          .createTopics(
              List.of(
                  new NewTopic("fos.fms.vehicle-yard-event.v1", 1, (short) 1),
                  new NewTopic("fos.fms.vehicle-operation-status.v1", 1, (short) 1)))
          .all()
          .get(20, TimeUnit.SECONDS);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  @Autowired ModuleControl controls;
  @Autowired SimulatorPlugin simulator;
  @Autowired JdbcTemplate db;

  @Test
  void completeRestKafkaReturnTripAndDisable() {
    controls.set("simulator", true, "test", "round trip");
    await().atMost(10, TimeUnit.SECONDS).until(() -> simulator.getState().equals("RUNNING"));
    simulator.run(1, "SUCCESS");
    await()
        .atMost(45, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertEquals(
                    9,
                    db.queryForObject(
                        "SELECT count(*) FROM delivery WHERE state='DELIVERED'", Integer.class)));
    assertTrue(
        simulator.history().stream()
            .anyMatch(e -> e.get("step").startsWith("FOS → TOS vehicle-operation-status")));
    controls.set("simulator", false, "test", "end test");
    await().atMost(5, TimeUnit.SECONDS).until(() -> simulator.getState().equals("DISABLED"));
    assertThrows(FosException.class, () -> simulator.run(1, "SUCCESS"));
  }
}
