package com.dpw.fos.uitv;

import com.dpw.fos.modules.ModuleControl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.*;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class UitvBridge {
  private final UitvProperties p;
  private final UitvTransformer transformer;
  private final ModuleControl controls;
  private final ObjectMapper mapper;
  private final ExecutorService executor =
      new ThreadPoolExecutor(
          1,
          1,
          0,
          TimeUnit.SECONDS,
          new SynchronousQueue<>(),
          r -> {
            Thread t = new Thread(r, "uitv-bridge");
            t.setDaemon(true);
            return t;
          });
  private Future<?> task;
  private volatile Connection connection;
  private volatile boolean wanted;
  private volatile String state = "DISABLED", error = "";
  private final AtomicLong forwarded = new AtomicLong(), failed = new AtomicLong();

  public UitvBridge(
      UitvProperties p, UitvTransformer transformer, ModuleControl controls, ObjectMapper mapper) {
    this.p = p;
    this.transformer = transformer;
    this.controls = controls;
    this.mapper = mapper;
  }

  public String getState() {
    return state;
  }

  public String getError() {
    return error;
  }

  public long getForwarded() {
    return forwarded.get();
  }

  public long getFailed() {
    return failed.get();
  }

  public boolean isAvailable() {
    return p.available();
  }

  @Scheduled(fixedDelay = 1000)
  public synchronized void reconcile() {
    try {
      wanted = p.available() && controls.enabled("uitv");
    } catch (Exception e) {
      wanted = false;
      error = "CONTROL_STORE_UNAVAILABLE";
    }
    if (!wanted) {
      closeConnection();
      state = "DISABLED";
      return;
    }
    if (task == null || task.isDone())
      try {
        task = executor.submit(this::consume);
      } catch (RejectedExecutionException ignored) {
      }
  }

  private void consume() {
    state = "CONNECTING";
    try {
      if (p.username().isBlank() || p.password().isBlank())
        throw new IllegalStateException("MISSING_CREDENTIALS");
      ConnectionFactory f = new ConnectionFactory();
      f.setHost(p.host());
      f.setPort(p.port());
      f.setUsername(p.username());
      f.setPassword(p.password());
      f.setVirtualHost(p.virtualHost());
      f.setRequestedHeartbeat(60);
      f.setConnectionTimeout(5000);
      f.setHandshakeTimeout(5000);
      f.setAutomaticRecoveryEnabled(false);
      if (p.tls()) {
        f.useSslProtocol(javax.net.ssl.SSLContext.getDefault());
        f.enableHostnameVerification();
      }
      try (Connection c = f.newConnection("fos-uitv");
          Channel ch = c.createChannel()) {
        connection = c;
        ch.exchangeDeclare(p.outputExchange(), "topic", true);
        ch.queueDeclare(p.inputQueue(), true, false, false, null);
        ch.confirmSelect();
        ch.basicQos(1);
        AtomicBoolean returned = new AtomicBoolean();
        ch.addReturnListener((code, text, exchange, key, properties, body) -> returned.set(true));
        state = "RUNNING";
        error = "";
        while (wanted && !Thread.currentThread().isInterrupted()) {
          GetResponse message = ch.basicGet(p.inputQueue(), false);
          if (message == null) {
            Thread.sleep(250);
            continue;
          }
          try {
            forward(ch, message, returned);
          } catch (Exception e) {
            failed.incrementAndGet();
            error =
                e instanceof IllegalArgumentException
                    ? "INVALID_TELEMETRY"
                    : "PUBLISH_NOT_CONFIRMED";
            if (ch.isOpen()) ch.basicNack(message.getEnvelope().getDeliveryTag(), false, true);
            // Match the source requeue semantics, but back off to avoid a poison-message hot loop.
            state = "RETRY_WAIT";
            Thread.sleep(1000);
            state = "RUNNING";
          }
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (Exception e) {
      error = "BROKER_CONNECTION_OR_CONFIGURATION_ERROR";
      state = "RETRY_WAIT";
      try {
        Thread.sleep(4000);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    } finally {
      connection = null;
      if (!wanted) state = "DISABLED";
    }
  }

  void forward(Channel ch, GetResponse message, AtomicBoolean returned) throws Exception {
    byte[] output = mapper.writeValueAsBytes(transformer.transform(message.getBody()));
    returned.set(false);
    AMQP.BasicProperties props =
        message.getProps().builder().contentType("application/json").deliveryMode(2).build();
    ch.basicPublish(p.outputExchange(), p.routingKey(), true, props, output);
    ch.waitForConfirmsOrDie(p.confirmTimeoutMs());
    if (returned.get()) throw new IllegalStateException("UNROUTABLE_OUTPUT");
    ch.basicAck(message.getEnvelope().getDeliveryTag(), false);
    forwarded.incrementAndGet();
  }

  private void closeConnection() {
    Connection c = connection;
    if (c != null && c.isOpen()) c.abort();
  }

  @PreDestroy
  public synchronized void stop() {
    wanted = false;
    closeConnection();
    executor.shutdownNow();
  }
}
