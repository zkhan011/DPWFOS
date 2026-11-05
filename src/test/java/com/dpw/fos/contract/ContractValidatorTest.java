package com.dpw.fos.contract;

import static org.junit.jupiter.api.Assertions.*;

import com.dpw.fos.TestProperties;
import com.dpw.fos.domain.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class ContractValidatorTest {
  private final ContractValidator v = new ContractValidator(TestProperties.create());
  private final ObjectMapper mapper = new ObjectMapper();

  byte[] fixture(String name) throws Exception {
    return Files.readAllBytes(Path.of("contracts/examples/" + name + ".json"));
  }

  @ParameterizedTest
  @CsvSource({
    "LOGIN_LOGOUT,vehicle-login-logout",
    "VEHICLE_JOB,vehicle-job",
    "YARD_EVENT,vehicle-yard-event",
    "OPERATION_STATUS,vehicle-operation-status",
    "OPERATION_STATUS,vehicle-operation-status-qc"
  })
  void allFixtures(MessageType type, String file) throws Exception {
    assertNotNull(v.validate(type, fixture(file)).hash());
  }

  @Test
  void reorderedKeysAreDuplicateButNullAndMissingAreDistinct() throws Exception {
    byte[] bytes = fixture("vehicle-job");
    JsonNode n = mapper.readTree(bytes);
    ObjectNode reordered = mapper.createObjectNode();
    var names = new java.util.ArrayList<String>();
    n.fieldNames().forEachRemaining(names::add);
    java.util.Collections.reverse(names);
    names.forEach(k -> reordered.set(k, n.get(k)));
    assertEquals(
        v.validate(MessageType.VEHICLE_JOB, bytes).hash(),
        v.validate(MessageType.VEHICLE_JOB, mapper.writeValueAsBytes(reordered)).hash());
    reordered.putNull("vesselVisit");
    String hash = v.validate(MessageType.VEHICLE_JOB, mapper.writeValueAsBytes(reordered)).hash();
    reordered.remove("vesselVisit");
    assertNotEquals(
        hash, v.validate(MessageType.VEHICLE_JOB, mapper.writeValueAsBytes(reordered)).hash());
  }

  @Test
  void preservesLeadingZeroesTimestampAndUnicode() throws Exception {
    var n = (ObjectNode) mapper.readTree(fixture("vehicle-login-logout"));
    n.put("vehicleId", "00012");
    n.put("userName", "مشغل");
    var result = v.validate(MessageType.LOGIN_LOGOUT, mapper.writeValueAsBytes(n));
    assertEquals("00012", result.vehicleId());
    assertEquals("2026-10-22T14:00:00.000+04:00", result.timestamp());
    assertTrue(result.raw().contains("مشغل"));
  }

  @Test
  void rejectsUnknownMissingAndOffsetless() throws Exception {
    for (String change : new String[] {"unknown", "missing", "time", "action"}) {
      var n = (ObjectNode) mapper.readTree(fixture("vehicle-login-logout"));
      switch (change) {
        case "unknown" -> n.put("source", "TOS");
        case "missing" -> n.remove("msgId");
        case "time" -> n.put("eventTimestamp", "2026-10-22T14:00:00");
        default -> n.put("action", "LOGIN");
      }
      assertThrows(
          FosException.class,
          () -> v.validate(MessageType.LOGIN_LOGOUT, mapper.writeValueAsBytes(n)));
    }
  }

  @Test
  void rejectsNumericStringAndDoesNotRequireMonotonicStatus() throws Exception {
    var n = (ObjectNode) mapper.readTree(fixture("vehicle-operation-status"));
    n.put("TPStatus", "2");
    assertThrows(
        FosException.class,
        () -> v.validate(MessageType.OPERATION_STATUS, mapper.writeValueAsBytes(n)));
    for (int i : new int[] {6, 1}) {
      n.put("TPStatus", i);
      assertNotNull(v.validate(MessageType.OPERATION_STATUS, mapper.writeValueAsBytes(n)));
    }
  }

  @Test
  void rejectsInactivePositionAndEmptyJobDetails() throws Exception {
    var n = (ObjectNode) mapper.readTree(fixture("vehicle-job"));
    var d = (ObjectNode) n.get("jobDetails").get(0);
    ((ObjectNode) d.get("fromPosition"))
        .set("cranePosition", d.get("toPosition").get("cranePosition"));
    assertThrows(
        FosException.class, () -> v.validate(MessageType.VEHICLE_JOB, mapper.writeValueAsBytes(n)));
    n.putArray("jobDetails");
    assertThrows(
        FosException.class, () -> v.validate(MessageType.VEHICLE_JOB, mapper.writeValueAsBytes(n)));
  }

  @Test
  void rejectsDuplicateKeysTrailingTokensAndOversize() throws Exception {
    String base = new String(fixture("vehicle-login-logout"), StandardCharsets.UTF_8);
    assertThrows(
        FosException.class,
        () ->
            v.validate(MessageType.LOGIN_LOGOUT, (base + " {}").getBytes(StandardCharsets.UTF_8)));
    assertThrows(
        FosException.class,
        () ->
            v.validate(
                MessageType.LOGIN_LOGOUT,
                base.replace("\"action\": \"IN\"", "\"action\": \"IN\", \"action\": \"OUT\"")
                    .getBytes(StandardCharsets.UTF_8)));
    assertEquals(
        413,
        assertThrows(
                FosException.class, () -> v.validate(MessageType.LOGIN_LOGOUT, new byte[262145]))
            .status);
  }
}
