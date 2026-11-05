package com.dpw.fos.config;

import java.time.Duration;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("fos")
public record FosProperties(
    boolean production,
    boolean devAuth,
    boolean contractApproved,
    int maxBytes,
    int maxIdentifier,
    int maxOperatorName,
    Duration maxOperationAge,
    Duration maxFutureSkew,
    Duration leaseDuration,
    Duration requestTimeout,
    int maxAttempts,
    int concurrencyPerTarget,
    String audience,
    Map<String, String> principals,
    List<String> allowedHosts,
    Map<String, Target> targets) {
  public record Target(String url, String bearerToken, boolean idempotencyConfirmed) {}
}
