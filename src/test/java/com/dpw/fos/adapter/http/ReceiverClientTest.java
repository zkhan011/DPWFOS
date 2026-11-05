package com.dpw.fos.adapter.http;

import static org.junit.jupiter.api.Assertions.*;

import com.dpw.fos.TestProperties;
import com.dpw.fos.config.FosProperties;
import com.dpw.fos.domain.MessageType;
import com.dpw.fos.persistence.DeliveryRepository.Work;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;

class ReceiverClientTest {
  private HttpServer server;
  private int status;
  private String response;
  private String received;

  @BeforeEach
  void start() throws Exception {
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/",
        e -> {
          received = new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
          e.getResponseHeaders().set("Retry-After", "120");
          byte[] b = response.getBytes(StandardCharsets.UTF_8);
          e.sendResponseHeaders(status, b.length);
          e.getResponseBody().write(b);
          e.close();
        });
    server.start();
  }

  @AfterEach
  void stop() {
    server.stop(0);
  }

  ReceiverClient client(boolean dedup) {
    return new ReceiverClient(
        TestProperties.create(
            Map.of(
                "vehicle-job",
                new FosProperties.Target(
                    "http://localhost:" + server.getAddress().getPort(), null, dedup))),
        new ObjectMapper());
  }

  Work work() {
    return new Work(
        UUID.randomUUID(),
        UUID.randomUUID(),
        MessageType.VEHICLE_JOB,
        "A",
        "{\"msgId\":\"A\"}",
        1,
        1,
        UUID.randomUUID(),
        Instant.now());
  }

  @Test
  void onlyMatchingBusinessCompletionIsDelivered() {
    status = 200;
    response = "{\"msgId\":\"A\",\"code\":\"0\"}";
    assertEquals("DELIVERED", client(true).send(work()).state());
    assertEquals(work().payload(), received);
    response = "{\"msgId\":\"B\",\"code\":\"0\"}";
    assertEquals("UNKNOWN", client(true).send(work()).state());
  }

  @Test
  void acceptedAndRejectedAreDistinct() {
    status = 202;
    response = "{\"msgId\":\"A\",\"code\":\"ACCEPTED\"}";
    assertEquals("REMOTE_ACCEPTED", client(true).send(work()).state());
    status = 200;
    response = "{\"msgId\":\"A\",\"code\":\"17\"}";
    assertEquals("BUSINESS_REJECTED", client(true).send(work()).state());
  }

  @Test
  void serverErrorRequiresDedupForAutomaticRetry() {
    status = 503;
    response = "unavailable";
    assertEquals("UNKNOWN", client(false).send(work()).state());
    assertEquals("RETRY_WAIT", client(true).send(work()).state());
  }

  @Test
  void honorsRetryAfter() {
    status = 429;
    response = "slow";
    assertEquals(Duration.ofSeconds(120), client(true).send(work()).delay());
  }

  @Test
  void oversizedResponseIsAmbiguous() {
    status = 200;
    response = "x".repeat(65537);
    assertEquals("UNKNOWN", client(false).send(work()).state());
  }
}
