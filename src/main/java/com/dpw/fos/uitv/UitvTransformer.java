package com.dpw.fos.uitv;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class UitvTransformer {
  private static final List<String> BATTERY =
      List.of(
          "BatterySoC",
          "BatterSoc",
          "BatteryPercentage",
          "BatteryLevelPercent",
          "StateOfCharge",
          "SOC");
  private static final List<String> FUEL =
      List.of("FuelLevel", "FuelLevelPercent", "FuelPercentage", "Fuel Level");
  private final UitvProperties p;
  private final Clock clock;
  private final ObjectMapper mapper =
      new ObjectMapper(
              JsonFactory.builder()
                  .streamReadConstraints(
                      StreamReadConstraints.builder().maxNestingDepth(40).build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

  public UitvTransformer(UitvProperties p, Clock clock) {
    this.p = p;
    this.clock = clock;
    if (p.maxBytes() < 1
        || !Double.isFinite(p.lowFuelThreshold())
        || !Double.isFinite(p.lowBatteryThreshold())
        || p.lowFuelThreshold() < 0
        || p.lowFuelThreshold() > 100
        || p.lowBatteryThreshold() < 0
        || p.lowBatteryThreshold() > 100) throw new IllegalArgumentException("Invalid UITV limits");
  }

  public ArrayNode transform(byte[] bytes) {
    if (bytes.length > p.maxBytes()) throw new IllegalArgumentException("UITV payload too large");
    try {
      JsonNode input = mapper.readTree(bytes);
      if (input == null || (!input.isObject() && !input.isArray()))
        throw new IllegalArgumentException("Expected object or array");
      ArrayNode out = mapper.createArrayNode();
      String timestamp =
          OffsetDateTime.now(clock)
              .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ssxxx"));
      if (input.isObject()) out.add(adapt(input, timestamp));
      else for (JsonNode item : input) out.add(adapt(item, timestamp));
      return out;
    } catch (java.io.IOException ex) {
      throw new IllegalArgumentException("Invalid telemetry JSON");
    }
  }

  public ObjectNode adapt(JsonNode s, String timestamp) {
    if (!s.isObject()) throw new IllegalArgumentException("Every record must be an object");
    String type = vehicleType(s);
    boolean fuel = type.equals("FUEL");
    JsonNode can =
        truthy(s.get("CANParameters")) ? s.get("CANParameters") : mapper.createArrayNode();
    Double level = findPercentage(s, fuel ? FUEL : BATTERY);
    if (level == null) level = findPercentage(can, fuel ? FUEL : BATTERY);
    ObjectNode out = mapper.createObjectNode();
    for (String key :
        List.of(
            "AssetID",
            "AssetNumber",
            "Make",
            "Model",
            "LastUpdatedTime",
            "Latitude",
            "Longitude",
            "Speed",
            "BatteryVoltage",
            "EngineHours")) out.set(key, value(s, key));
    out.put("VehicleType", type);
    out.set("EquipmentType", orDefault(s.get("EquipmentType"), p.defaultEquipmentType()));
    out.set(
        "FuelType", fuel ? orDefault(s.get("FuelType"), p.defaultFuelType()) : NullNode.instance);
    out.put("Timestamp", timestamp);
    out.set(
        "BatteryLevelPercent",
        !fuel && level != null ? DoubleNode.valueOf(level) : NullNode.instance);
    out.set(
        "LowBattery",
        !fuel && level != null
            ? BooleanNode.valueOf(level <= p.lowBatteryThreshold())
            : NullNode.instance);
    out.set(
        "FuelLevelPercent", fuel && level != null ? DoubleNode.valueOf(level) : NullNode.instance);
    out.set(
        "LowFuel",
        fuel && level != null
            ? BooleanNode.valueOf(level <= p.lowFuelThreshold())
            : NullNode.instance);
    out.set("FuelConsumptionRate", fuel ? value(s, "FuelConsumptionRate") : NullNode.instance);
    out.set("TotalFuelUsed", fuel ? value(s, "TotalFuelUsed") : NullNode.instance);
    out.set("Location", location(s.get("Location")));
    ObjectNode availability = mapper.createObjectNode();
    for (String key :
        List.of("GPSAvailable", "FuelDataAvailable", "EngineDataAvailable", "CANDataAvailable"))
      availability.put(key, false);
    if (s.path("DataAvailabilityFlag").isObject())
      availability.setAll((ObjectNode) s.get("DataAvailabilityFlag"));
    availability.put("FuelDataAvailable", fuel && level != null);
    out.set("DataAvailabilityFlag", availability);
    out.set("CANParameters", can);
    return out;
  }

  public static Double percentage(JsonNode n) {
    if (n == null || n.isNull() || n.isBoolean() || (!n.isNumber() && !n.isTextual())) return null;
    try {
      String t = n.asText().strip();
      if (t.endsWith("%")) t = t.substring(0, t.length() - 1).strip();
      double d = Double.parseDouble(t);
      return Double.isFinite(d) && d >= 0 && d <= 100 ? d : null;
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private static String norm(String s) {
    StringBuilder b = new StringBuilder();
    s.toLowerCase(Locale.ROOT)
        .codePoints()
        .filter(Character::isLetterOrDigit)
        .forEach(b::appendCodePoint);
    return b.toString();
  }

  public static Double findPercentage(JsonNode data, List<String> names) {
    if (data == null) return null;
    List<String> aliases = names.stream().map(UitvTransformer::norm).toList();
    if (data.isArray()) {
      for (JsonNode n : data) {
        if (!n.isObject()) continue;
        Double found = findPercentage(n, names);
        if (found != null) return found;
      }
      return null;
    }
    if (!data.isObject()) return null;
    Map<String, JsonNode> normalized = new HashMap<>();
    data.fields().forEachRemaining(e -> normalized.put(norm(e.getKey()), e.getValue()));
    for (String alias : aliases) {
      Double d = percentage(normalized.get(alias));
      if (d != null) return d;
    }
    boolean match =
        List.of("name", "parametername", "parameter", "canparametername", "key").stream()
            .anyMatch(
                k ->
                    aliases.contains(norm(normalized.getOrDefault(k, NullNode.instance).asText())));
    if (match)
      for (String key : List.of("value", "parametervalue", "canparametervalue")) {
        Double d = percentage(normalized.get(key));
        if (d != null) return d;
      }
    return null;
  }

  private String vehicleType(JsonNode s) {
    for (String candidate :
        List.of(
            s.path("EnergySource").asText(""),
            s.path("VehicleType").asText(""),
            p.defaultVehicleType())) {
      String v = candidate.strip().toUpperCase(Locale.ROOT);
      if (Set.of("ELEC", "ELECTRIC", "EV").contains(v)) return "ELECTRIC";
      if (Set.of("FUEL", "DIESEL", "PETROL").contains(v)) return "FUEL";
    }
    throw new IllegalArgumentException("Cannot determine energy type");
  }

  private ObjectNode location(JsonNode source) {
    JsonNode s = source != null && source.isObject() ? source : mapper.createObjectNode();
    String name = s.path("LocationName").asText("").strip(),
        code = s.path("LocationCode").asText("").strip(),
        status = s.path("LocationMappingStatus").asText("").strip().toLowerCase(Locale.ROOT);
    if (Set.of("insidedefinedlocation", "outsidedefinedlocation").contains(status))
      return loc(
          status.equals("insidedefinedlocation")
              ? "InsideDefinedLocation"
              : "OutsideDefinedLocation",
          value(s, "LocationType"),
          name,
          code);
    String terminal =
        switch (name.toLowerCase(Locale.ROOT)) {
          case "operation t1", "engineering t1" -> "T1";
          case "operation t2" -> "T2";
          case "operation t3" -> "T3";
          default -> null;
        };
    if (terminal != null)
      return loc(
          "InsideDefinedLocation",
          TextNode.valueOf(terminal),
          "Terminal " + terminal.substring(1),
          terminal);
    if (status.equals("mapped")) return loc("Mapped", value(s, "LocationType"), name, code);
    return loc("OutsideDefinedLocation", TextNode.valueOf("OUTSIDE"), "Outside", "OUTSIDE");
  }

  private ObjectNode loc(String status, JsonNode type, String name, String code) {
    ObjectNode n = mapper.createObjectNode();
    n.put("LocationMappingStatus", status);
    n.set("LocationType", type);
    n.set("LocationName", name.isEmpty() ? NullNode.instance : TextNode.valueOf(name));
    n.set("LocationCode", code.isEmpty() ? NullNode.instance : TextNode.valueOf(code));
    return n;
  }

  private static JsonNode value(JsonNode n, String k) {
    return n.has(k) ? n.get(k).deepCopy() : NullNode.instance;
  }

  private static JsonNode orDefault(JsonNode n, String fallback) {
    return truthy(n) ? n.deepCopy() : TextNode.valueOf(fallback);
  }

  private static boolean truthy(JsonNode n) {
    return n != null
        && !n.isNull()
        && !(n.isBoolean() && !n.booleanValue())
        && !(n.isNumber() && n.doubleValue() == 0)
        && !(n.isTextual() && n.textValue().isEmpty())
        && !(n.isContainerNode() && n.isEmpty());
  }
}
