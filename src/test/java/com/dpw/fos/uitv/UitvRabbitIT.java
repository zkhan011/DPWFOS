package com.dpw.fos.uitv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dpw.fos.modules.ModuleControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.*;

@Testcontainers
class UitvRabbitIT {
  @Container
  static GenericContainer<?> rabbit =
      new GenericContainer<>("rabbitmq:4.1.4-alpine")
          .withEnv("RABBITMQ_DEFAULT_USER", "bridge-test")
          .withEnv("RABBITMQ_DEFAULT_PASS", "synthetic-test-only")
          .withExposedPorts(5672)
          .waitingFor(Wait.forLogMessage(".*Server startup complete.*", 1));

  @Test
  void mandatoryConfirmationAndInputAckUseRealRabbit() throws Exception {
    ConnectionFactory f = new ConnectionFactory();
    f.setHost(rabbit.getHost());
    f.setPort(rabbit.getMappedPort(5672));
    f.setUsername("bridge-test");
    f.setPassword("synthetic-test-only");
    var m = new ObjectMapper();
    var bridge =
        new UitvBridge(
            UitvTransformerTest.properties(),
            UitvTransformerTest.transformer(),
            mock(ModuleControl.class),
            m);
    try (Connection c = f.newConnection();
        Channel ch = c.createChannel()) {
      ch.queueDeclare("input", true, false, false, null);
      ch.queueDeclare("sink", true, false, false, null);
      ch.exchangeDeclare("output", "topic", true);
      ch.queueBind("sink", "output", "key");
      ch.confirmSelect();
      AtomicBoolean returned = new AtomicBoolean();
      ch.addReturnListener((code, text, exchange, key, properties, body) -> returned.set(true));
      var props =
          new AMQP.BasicProperties.Builder()
              .messageId("source-id")
              .correlationId("correlation")
              .headers(Map.of("source", "test"))
              .deliveryMode(2)
              .build();
      ch.basicPublish(
          "", "input", props, "{\"EnergySource\":\"ELEC\",\"BatterySoC\":18}".getBytes());
      ch.waitForConfirmsOrDie(5000);
      bridge.forward(ch, ch.basicGet("input", false), returned);
      GetResponse output = ch.basicGet("sink", true);
      assertNotNull(output);
      assertTrue(m.readTree(output.getBody()).get(0).path("LowBattery").booleanValue());
      assertEquals("source-id", output.getProps().getMessageId());
      assertEquals(2, output.getProps().getDeliveryMode());
      assertEquals(0, ch.queueDeclarePassive("input").getMessageCount());
      ch.queueUnbind("sink", "output", "key");
      ch.basicPublish("", "input", props, "{}".getBytes());
      ch.waitForConfirmsOrDie(5000);
      GetResponse input = ch.basicGet("input", false);
      assertThrows(IllegalStateException.class, () -> bridge.forward(ch, input, returned));
      ch.basicNack(input.getEnvelope().getDeliveryTag(), false, true);
      // Requeue is asynchronous in RabbitMQ; observe completion rather than race broker processing.
      org.awaitility.Awaitility.await()
          .atMost(5, java.util.concurrent.TimeUnit.SECONDS)
          .untilAsserted(() -> assertEquals(1, ch.queueDeclarePassive("input").getMessageCount()));
      GetResponse retried = ch.basicGet("input", true);
      assertEquals("source-id", retried.getProps().getMessageId());
      assertTrue(retried.getEnvelope().isRedeliver());
    } finally {
      bridge.stop();
    }
  }
}
