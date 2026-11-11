package com.dpw.fos.simulator;

import static org.junit.jupiter.api.Assertions.*;

import com.dpw.fos.TestProperties;
import com.dpw.fos.contract.ContractValidator;
import com.dpw.fos.domain.MessageType;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class SimulationPayloadsTest {
  @Test
  void generatedTrafficUsesActualContracts() throws Exception {
    var m = new ObjectMapper();
    var validator = new ContractValidator(TestProperties.create());
    for (var type : MessageType.values()) {
      var n = SimulationPayloads.create(m, type.route, "SIM-id", "SIM-vehicle", "SIM-job");
      var v = validator.validate(type, m.writeValueAsBytes(n));
      assertEquals("SIM-id", v.msgId());
      assertEquals("SIM-vehicle", v.vehicleId());
    }
  }
}
