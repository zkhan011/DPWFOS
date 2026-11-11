package com.dpw.fos.uitv;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.*;
import java.time.*;
import java.util.stream.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class UitvTransformerTest {
  static final ObjectMapper M = new ObjectMapper();

  static UitvProperties properties() {
    return new UitvProperties(
        false,
        "localhost",
        5671,
        "",
        "",
        "/",
        true,
        "input",
        "output",
        "key",
        "FUEL",
        "TRACTOR",
        "DIESEL",
        20,
        20,
        262144,
        1000);
  }

  static UitvTransformer transformer() {
    return new UitvTransformer(
        properties(), Clock.fixed(Instant.parse("2026-11-11T05:00:00Z"), ZoneId.of("Asia/Dubai")));
  }

  static Stream<JsonNode> cases() throws Exception {
    try (var in = UitvTransformerTest.class.getResourceAsStream("/uitv-cases.json")) {
      JsonNode cases = M.readTree(in);
      return StreamSupport.stream(cases.spliterator(), false).toList().stream();
    }
  }

  @ParameterizedTest(name = "{index}: Python parity")
  @MethodSource("cases")
  void parity(JsonNode test) throws Exception {
    assertEquals(
        test.get("expected"),
        transformer().transform(M.writeValueAsBytes(test.get("input"))),
        test.get("name").asText());
  }

  @Test
  void rejectsInvalidShapesAndOversize() {
    for (String s : new String[] {"null", "123", "[1]", "{} {}", "{\"Speed\":NaN}"})
      assertThrows(IllegalArgumentException.class, () -> transformer().transform(s.getBytes()));
    assertThrows(IllegalArgumentException.class, () -> transformer().transform(new byte[262145]));
  }

  @Test
  void utcStillContainsExplicitOffset() throws Exception {
    var t =
        new UitvTransformer(
            properties(), Clock.fixed(Instant.parse("2026-11-11T05:00:00Z"), ZoneOffset.UTC));
    assertEquals(
        "2026-11-11T05:00:00+00:00",
        t.transform("{}".getBytes()).get(0).path("Timestamp").asText());
  }

  @Test
  void nestedCanListsAreIgnored() throws Exception {
    assertTrue(
        transformer()
            .transform("{\"CANParameters\":[[{\"FuelLevel\":12}]]}".getBytes())
            .get(0)
            .get("FuelLevelPercent")
            .isNull());
  }
}
