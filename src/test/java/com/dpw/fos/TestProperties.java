package com.dpw.fos;

import com.dpw.fos.config.FosProperties;
import java.time.Duration;
import java.util.*;

public final class TestProperties {
  public static FosProperties create() {
    return create(Map.of());
  }

  public static FosProperties create(Map<String, FosProperties.Target> targets) {
    return new FosProperties(
        false,
        false,
        false,
        262144,
        128,
        256,
        Duration.ofSeconds(60),
        Duration.ofSeconds(30),
        Duration.ofSeconds(45),
        Duration.ofSeconds(10),
        8,
        2,
        "fos-api",
        Map.of(),
        List.of("localhost"),
        targets);
  }
}
