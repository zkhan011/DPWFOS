package com.dpw.fos.application;

import com.dpw.fos.adapter.http.ReceiverClient;
import com.dpw.fos.adapter.http.ReceiverClient.Outcome;
import com.dpw.fos.config.FosProperties;
import com.dpw.fos.domain.MessageType;
import com.dpw.fos.persistence.DeliveryRepository;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PreDestroy;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.slf4j.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "fos.workers.enabled", havingValue = "true")
public class DeliveryWorkers {
  private static final Logger log = LoggerFactory.getLogger(DeliveryWorkers.class);
  private final Map<String, ThreadPoolExecutor> pools = new HashMap<>();
  private final Map<String, Instant> openUntil = new ConcurrentHashMap<>();
  private final Map<String, Integer> failures = new ConcurrentHashMap<>();
  private final DeliveryRepository repo;
  private final ReceiverClient client;
  private final FosProperties p;
  private final MeterRegistry metrics;
  private final UUID owner = UUID.randomUUID();

  public DeliveryWorkers(
      DeliveryRepository repo, ReceiverClient client, FosProperties p, MeterRegistry metrics) {
    this.repo = repo;
    this.client = client;
    this.p = p;
    this.metrics = metrics;
    for (String target : List.of("FMS", "TOS"))
      pools.put(
          target,
          new ThreadPoolExecutor(
              p.concurrencyPerTarget(),
              p.concurrencyPerTarget(),
              0,
              TimeUnit.SECONDS,
              new SynchronousQueue<>(),
              new ThreadPoolExecutor.AbortPolicy()));
  }

  @Scheduled(fixedDelayString = "${fos.workers.poll-ms:100}")
  public void tick() {
    pools.forEach(
        (target, pool) -> {
          if (Instant.now().isBefore(openUntil.getOrDefault(target, Instant.EPOCH))) return;
          for (int i = 0; i < p.concurrencyPerTarget(); i++)
            try {
              pool.execute(() -> process(target));
            } catch (RejectedExecutionException ignored) {
              break;
            }
        });
  }

  private void process(String target) {
    try {
      repo.claim(target, owner)
          .ifPresent(
              w -> {
                long start = System.nanoTime();
                Outcome o;
                if (w.type() == MessageType.OPERATION_STATUS
                    && w.sourceTime().isBefore(Instant.now().minus(p.maxOperationAge())))
                  o = Outcome.of("EXPIRED", "STALE_OPERATION_STATUS");
                else o = client.send(w);
                boolean stored =
                    repo.finish(w, o, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
                metrics
                    .counter("fos.delivery.outcomes", "target", target, "state", o.state())
                    .increment();
                if (o.state().equals("RETRY_WAIT") || o.state().equals("UNKNOWN")) {
                  if (failures.merge(target, 1, Integer::sum) >= 5)
                    openUntil.put(target, Instant.now().plusSeconds(30));
                } else failures.put(target, 0);
                log.info(
                    "delivery receiptId={} target={} attempt={} outcome={} fenced={}",
                    w.receipt(),
                    target,
                    w.attempt(),
                    o.category(),
                    !stored);
              });
    } catch (Exception e) {
      metrics.counter("fos.worker.errors", "target", target).increment();
      log.warn("Worker failure target={} category={}", target, e.getClass().getSimpleName());
    }
  }

  @PreDestroy
  public void close() {
    pools.values().forEach(ExecutorService::shutdown);
    for (var pool : pools.values())
      try {
        if (!pool.awaitTermination(
            p.requestTimeout().plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS))
          pool.shutdownNow();
      } catch (InterruptedException e) {
        pool.shutdownNow();
        Thread.currentThread().interrupt();
      }
  }
}
