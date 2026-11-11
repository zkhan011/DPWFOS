package com.dpw.fos.uitv;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.dpw.fos.modules.ModuleControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;

class UitvBridgeTest {
  UitvBridge bridge;
  Channel channel;

  @BeforeEach
  void setup() {
    bridge =
        new UitvBridge(
            UitvTransformerTest.properties(),
            UitvTransformerTest.transformer(),
            mock(ModuleControl.class),
            new ObjectMapper());
    channel = mock(Channel.class);
  }

  @AfterEach
  void cleanup() {
    bridge.stop();
  }

  GetResponse message() {
    return new GetResponse(
        new Envelope(7, false, "in", "key"),
        new AMQP.BasicProperties.Builder()
            .messageId("original")
            .correlationId("corr")
            .headers(Map.of("source", "synthetic"))
            .build(),
        "{}".getBytes(),
        0);
  }

  @Test
  void acknowledgesOnlyAfterMandatoryPublishIsConfirmed() throws Exception {
    bridge.forward(channel, message(), new AtomicBoolean());
    var order = inOrder(channel);
    order
        .verify(channel)
        .basicPublish(
            eq("output"),
            eq("key"),
            eq(true),
            argThat(
                p ->
                    p.getDeliveryMode() == 2
                        && p.getMessageId().equals("original")
                        && p.getCorrelationId().equals("corr")
                        && p.getHeaders().get("source").equals("synthetic")),
            any(byte[].class));
    order.verify(channel).waitForConfirmsOrDie(1000);
    order.verify(channel).basicAck(7, false);
    assertEquals(1, bridge.getForwarded());
  }

  @Test
  void unconfirmedOrReturnedOutputNeverAcknowledges() throws Exception {
    doThrow(new IOException("nack")).when(channel).waitForConfirmsOrDie(anyLong());
    assertThrows(IOException.class, () -> bridge.forward(channel, message(), new AtomicBoolean()));
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
    reset(channel);
    AtomicBoolean returned = new AtomicBoolean();
    doAnswer(
            i -> {
              returned.set(true);
              return null;
            })
        .when(channel)
        .basicPublish(
            anyString(), anyString(), eq(true), any(AMQP.BasicProperties.class), any(byte[].class));
    assertThrows(IllegalStateException.class, () -> bridge.forward(channel, message(), returned));
    verify(channel, never()).basicAck(anyLong(), anyBoolean());
  }
}
