package com.dpw.fos.modules;

import com.dpw.fos.config.FosProperties;
import com.dpw.fos.simulator.SimulatorProperties;
import com.dpw.fos.uitv.UitvProperties;
import java.net.URI;
import java.util.Set;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class ModuleGuard {
  public ModuleGuard(FosProperties p, UitvProperties u, SimulatorProperties s, Environment env) {
    if (p.production() && u.available() && !u.tls())
      throw new IllegalStateException("Production UITV requires TLS");
    if (s.available()) {
      if (p.production()
          || !p.devAuth()
          || !env.getProperty("fos.kafka.enabled", Boolean.class, false)
          || !env.getProperty("fos.workers.enabled", Boolean.class, false))
        throw new IllegalStateException(
            "Simulator requires local mock authentication, Kafka and workers");
      URI api = URI.create(s.apiBaseUrl());
      if (!loopback(api)
          || api.getUserInfo() != null
          || api.getQuery() != null
          || api.getFragment() != null
          || s.password().isBlank()
          || s.port() < 1024
          || s.port() > 65535)
        throw new IllegalStateException("Invalid isolated simulator configuration");
      if (p.targets() == null || p.targets().size() != 4)
        throw new IllegalStateException("Simulator requires four loopback targets");
      p.targets()
          .forEach(
              (key, t) -> {
                URI uri = URI.create(t.url());
                if (!loopback(uri) || uri.getPort() != s.port() || !uri.getPath().equals("/" + key))
                  throw new IllegalStateException(
                      "Simulator target must use its loopback receiver");
              });
    }
  }

  private boolean loopback(URI uri) {
    return "http".equals(uri.getScheme())
        && Set.of("localhost", "127.0.0.1", "[::1]")
            .contains(uri.getHost() == null ? "" : uri.getHost());
  }
}
