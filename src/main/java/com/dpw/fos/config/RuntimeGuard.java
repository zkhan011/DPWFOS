package com.dpw.fos.config;

import java.net.URI;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class RuntimeGuard {
  public RuntimeGuard(FosProperties p, Environment e) {
    boolean local = Arrays.asList(e.getActiveProfiles()).contains("local");
    if (p.devAuth() && (!local || p.production()))
      throw new IllegalStateException("Mock authentication requires local non-production profile");
    if (p.maxBytes() < 1
        || p.maxIdentifier() < 1
        || p.maxIdentifier() > 128
        || p.maxOperatorName() < 1
        || p.maxOperatorName() > 256
        || p.maxAttempts() < 1
        || p.concurrencyPerTarget() < 1
        || p.requestTimeout().isNegative()
        || p.requestTimeout().isZero()
        || p.maxOperationAge().isNegative()
        || p.leaseDuration().compareTo(p.requestTimeout().plusSeconds(10)) < 0)
      throw new IllegalStateException("Invalid bounded processing configuration");
    if (p.production() && (!p.contractApproved() || p.devAuth()))
      throw new IllegalStateException("Production contract/security gates are unconfirmed");
    if (p.production()
        && (!e.getProperty("server.ssl.enabled", Boolean.class, false)
            || !e.getProperty("management.server.ssl.enabled", Boolean.class, false)
            || !e.getRequiredProperty("spring.datasource.url").contains("sslmode=verify-full")
            || !Set.of("SSL", "SASL_SSL")
                .contains(e.getProperty("spring.kafka.properties.security.protocol", ""))))
      throw new IllegalStateException(
          "Production requires verified TLS for API, management, database and Kafka");
    if (p.production()
        && (p.targets() == null
            || !p.targets()
                .keySet()
                .containsAll(
                    Set.of(
                        "vehicle-job",
                        "vehicle-login-logout",
                        "vehicle-yard-event",
                        "vehicle-operation-status"))))
      throw new IllegalStateException("All approved production routes required");
    if (p.targets() != null)
      p.targets()
          .forEach(
              (name, t) -> {
                URI u = URI.create(t.url());
                if (u.getHost() == null
                    || u.getUserInfo() != null
                    || u.getFragment() != null
                    || u.getQuery() != null
                    || p.allowedHosts() == null
                    || !p.allowedHosts().contains(u.getHost())
                    || (!"https".equals(u.getScheme()) && !(local && "http".equals(u.getScheme()))))
                  throw new IllegalStateException("Unapproved receiver URI for " + name);
                if (p.production() && !t.idempotencyConfirmed())
                  throw new IllegalStateException("Receiver deduplication must be confirmed");
              });
  }
}
