package com.dpw.fos.simulator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.OffsetDateTime;
import org.springframework.core.io.ClassPathResource;

public final class SimulationPayloads {
  private SimulationPayloads() {}

  public static ObjectNode create(
      ObjectMapper m, String fixture, String msgId, String vehicle, String jobId)
      throws java.io.IOException {
    try (var in = new ClassPathResource("simulator/" + fixture + ".json").getInputStream()) {
      ObjectNode n = (ObjectNode) m.readTree(in);
      n.put("msgId", msgId);
      n.put("vehicleId", vehicle);
      n.put("eventTimestamp", OffsetDateTime.now().toString());
      if (n.has("jobId")) n.put("jobId", jobId);
      return n;
    }
  }
}
