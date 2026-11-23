package com.dpw.fos.adapter.http;

import com.dpw.fos.config.FosProperties;
import com.dpw.fos.persistence.DeliveryRepository.Work;
import com.fasterxml.jackson.databind.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import org.springframework.stereotype.Component;

@Component
public class ReceiverClient {
  private final FosProperties p;
  private final ObjectMapper mapper;
  private final HttpClient client;
  private final com.dpw.fos.admin.EndpointSettings endpoints;

  @org.springframework.beans.factory.annotation.Value("${fos.simulator.real-fms-bearer-token:}")
  private String realFmsToken = "";

  @org.springframework.beans.factory.annotation.Value("${fos.simulator.available:false}")
  private boolean simulatorAvailable;

  public record Outcome(
      String state,
      String category,
      Integer httpCode,
      String businessCode,
      Duration delay,
      String destination,
      String responseBody,
      Boolean idempotencyConfirmed) {
    public Outcome(
        String state, String category, Integer httpCode, String businessCode, Duration delay) {
      this(state, category, httpCode, businessCode, delay, null, null, null);
    }

    public Outcome exchange(String url, String body, Integer status) {
      return new Outcome(
          state,
          category,
          status == null ? httpCode : status,
          businessCode,
          delay,
          url,
          body,
          idempotencyConfirmed);
    }

    public Outcome policy(boolean confirmed) {
      return new Outcome(
          state, category, httpCode, businessCode, delay, destination, responseBody, confirmed);
    }

    public static Outcome of(String state, String category) {
      return new Outcome(state, category, null, null, Duration.ZERO);
    }
  }

  public ReceiverClient(FosProperties p, ObjectMapper mapper) {
    this(p, mapper, null);
  }

  @org.springframework.beans.factory.annotation.Autowired
  public ReceiverClient(
      FosProperties p, ObjectMapper mapper, com.dpw.fos.admin.EndpointSettings endpoints) {
    this.endpoints = endpoints;
    this.p = p;
    this.mapper = mapper;
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  public Outcome send(Work w) {
    var target = p.targets() == null ? null : p.targets().get(w.type().route);
    if (w.simulatorMode() == null
        && endpoints != null
        && !(simulatorAvailable
            && p.devAuth()
            && !p.production()
            && w.msgId().startsWith("SIM-"))) {
      try {
        target = endpoints.resolve(w);
      } catch (com.dpw.fos.domain.FosException e) {
        return Outcome.of("QUARANTINED", e.code);
      }
    }
    if (w.simulatorMode() != null) {
      if (p.production()) return Outcome.of("QUARANTINED", "SIMULATOR_FORBIDDEN_IN_PRODUCTION");
      target =
          new FosProperties.Target(
              w.simulatorUrl(),
              "REAL".equals(w.simulatorMode()) ? realFmsToken : null,
              w.simulatorIdempotency());
    }
    if (target == null) return Outcome.of("QUARANTINED", "ROUTE_UNCONFIGURED");
    var req =
        HttpRequest.newBuilder(URI.create(target.url()))
            .timeout(p.requestTimeout())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(w.payload()));
    if (target.bearerToken() != null && !target.bearerToken().isBlank())
      req.header("Authorization", "Bearer " + target.bearerToken());
    try {
      // ofByteArray completes only after the bounded body subscriber completes; timeout covers the
      // entire response.
      var pending = client.sendAsync(req.build(), info -> new LimitedBodySubscriber(65536));
      HttpResponse<byte[]> res;
      try {
        res = pending.get(p.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
      } finally {
        if (!pending.isDone()) pending.cancel(true);
      }
      return classify(w, target, res)
          .exchange(
              target.url(),
              new String(res.body(), java.nio.charset.StandardCharsets.UTF_8)
                  .replace("\u0000", "\uFFFD"),
              res.statusCode())
          .policy(target.idempotencyConfirmed());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Outcome.of("UNKNOWN", "INTERRUPTED")
          .exchange(target.url(), null, null)
          .policy(target.idempotencyConfirmed());
    } catch (Exception e) {
      Throwable cause = e;
      while (cause.getCause() != null) cause = cause.getCause();
      if (cause instanceof ConnectException || cause instanceof HttpConnectTimeoutException)
        return retry(w, "CONNECT_FAILED", null)
            .exchange(target.url(), null, null)
            .policy(target.idempotencyConfirmed());
      return (target.idempotencyConfirmed()
              ? retry(w, "AMBIGUOUS_RETRY", null)
              : Outcome.of("UNKNOWN", "AMBIGUOUS_HTTP"))
          .exchange(target.url(), null, null)
          .policy(target.idempotencyConfirmed());
    }
  }

  private Outcome classify(Work w, FosProperties.Target target, HttpResponse<byte[]> res) {
    int status = res.statusCode();
    if (status == 429) {
      return new Outcome(
          "RETRY_WAIT",
          "RATE_LIMITED",
          status,
          null,
          retryAfter(res.headers().firstValue("Retry-After").orElse(""), w.attempt()));
    }
    if (status >= 500)
      return target.idempotencyConfirmed()
          ? retry(w, "HTTP_SERVER_ERROR", status)
          : Outcome.of("UNKNOWN", "SERVER_ERROR_UNCONFIRMED");
    if (status != 200 && status != 202)
      return new Outcome("BUSINESS_REJECTED", "HTTP_REJECTED", status, null, Duration.ZERO);
    JsonNode body;
    try {
      body = mapper.readTree(res.body());
    } catch (java.io.IOException e) {
      return Outcome.of("UNKNOWN", "RESPONSE_CONTRACT_MISMATCH");
    }
    if (body == null
        || !body.path("msgId").isTextual()
        || !w.msgId().equals(body.path("msgId").asText())
        || !body.path("code").isTextual())
      return Outcome.of("UNKNOWN", "RESPONSE_CONTRACT_MISMATCH");
    String code = body.get("code").textValue();
    if (code.length() > 128) return Outcome.of("UNKNOWN", "RESPONSE_CODE_TOO_LONG");
    if (status == 202)
      return new Outcome("REMOTE_ACCEPTED", "AWAITING_CONFIRMATION", status, code, Duration.ZERO);
    return new Outcome(
        code.equals("0") ? "DELIVERED" : "BUSINESS_REJECTED",
        code.equals("0") ? "COMPLETED" : "BUSINESS_CODE",
        status,
        code,
        Duration.ZERO);
  }

  private Outcome retry(Work w, String category, Integer status) {
    return new Outcome("RETRY_WAIT", category, status, null, backoff(w.attempt()));
  }

  static Duration backoff(int attempt) {
    long base = Math.min(60, 1L << Math.min(6, Math.max(0, attempt - 1)));
    return Duration.ofMillis(base * 1000 + ThreadLocalRandom.current().nextLong(251));
  }

  static Duration retryAfter(String value, int attempt) {
    try {
      long seconds = Long.parseLong(value);
      if (seconds >= 0 && seconds <= 31536000) return Duration.ofSeconds(seconds);
    } catch (NumberFormatException ignored) {
      try {
        return Duration.between(
                    Instant.now(),
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant())
                .isNegative()
            ? Duration.ZERO
            : Duration.between(
                Instant.now(),
                ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant());
      } catch (Exception ignored2) {
      }
    }
    return backoff(attempt);
  }
}
