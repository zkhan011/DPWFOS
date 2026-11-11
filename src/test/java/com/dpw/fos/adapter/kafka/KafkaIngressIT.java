package com.dpw.fos.adapter.kafka;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

import com.dpw.fos.FosApplication;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.*;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.*;
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
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "management.server.port=0",
      "fos.workers.enabled=false",
      "fos.simulator.available=false",
      "fos.admin.enabled=false",
      "fos.local-password=integration-test-only-password"
    })
class KafkaIngressIT {
  @Container
  static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16.6-alpine");

  @Container
  static ConfluentKafkaContainer kafka = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.7.1");

  @DynamicPropertySource
  static void props(DynamicPropertyRegistry p) {
    p.add("spring.datasource.url", postgres::getJdbcUrl);
    p.add("spring.datasource.username", postgres::getUsername);
    p.add("spring.datasource.password", postgres::getPassword);
    p.add("spring.flyway.user", postgres::getUsername);
    p.add("spring.flyway.password", postgres::getPassword);
    p.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
    // Create only local test topics; application does not create production topics.
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

  @Autowired JdbcTemplate db;

  @Test
  void commitsOnlyDurableValidOrQuarantinedRecords() throws Exception {
    String topic = "fos.fms.vehicle-yard-event.v1";
    var props = new Properties();
    props.put("bootstrap.servers", kafka.getBootstrapServers());
    props.put("acks", "all");
    props.put("key.serializer", StringSerializer.class);
    props.put("value.serializer", ByteArraySerializer.class);
    try (var producer = new KafkaProducer<String, byte[]>(props)) {
      byte[] valid = Files.readAllBytes(Path.of("contracts/examples/vehicle-yard-event.json"));
      producer.send(new ProducerRecord<>(topic, "T501", valid)).get();
      producer.send(new ProducerRecord<>(topic, "T501", valid)).get();
      producer.send(new ProducerRecord<>(topic, "T501", "invalid".getBytes())).get();
    }
    await()
        .atMost(30, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertEquals(1, db.queryForObject("SELECT count(*) FROM delivery", Integer.class));
              assertEquals(1, db.queryForObject("SELECT count(*) FROM quarantine", Integer.class));
            });
    try (var admin = AdminClient.create(Map.of("bootstrap.servers", kafka.getBootstrapServers()))) {
      await()
          .atMost(10, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertEquals(
                      3,
                      admin
                          .listConsumerGroupOffsets("fos-local-v1")
                          .partitionsToOffsetAndMetadata()
                          .get()
                          .get(new TopicPartition(topic, 0))
                          .offset()));
    }
  }
}
