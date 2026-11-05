package com.dpw.fos.contract;

import com.dpw.fos.config.FosProperties;
import com.dpw.fos.domain.*;
import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class ContractValidator {
  private final ObjectMapper mapper =
      new ObjectMapper(
              JsonFactory.builder()
                  .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                  .streamReadConstraints(
                      StreamReadConstraints.builder().maxNestingDepth(30).build())
                  .build())
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
  private final FosProperties p;

  public ContractValidator(FosProperties p) {
    this.p = p;
  }

  public record Validated(
      JsonNode json,
      String raw,
      String hash,
      String msgId,
      String vehicleId,
      OffsetDateTime time,
      String timestamp) {}

  public Validated validate(MessageType type, byte[] bytes) {
    if (bytes.length > p.maxBytes()) throw new FosException(413, "PAYLOAD_TOO_LARGE");
    try {
      JsonNode n = mapper.readTree(bytes);
      String extra =
          switch (type) {
            case VEHICLE_JOB -> "seqNo jobId jobType jobAction jobState jobDetails vesselVisit";
            case LOGIN_LOGOUT -> "action userId userName";
            case YARD_EVENT -> "jobId eventType toPosition";
            case OPERATION_STATUS -> "area craneId TPStatus";
          };
      object(n, "msgId eventTimestamp vehicleId " + extra);
      String id = str(n, "msgId"), vehicle = str(n, "vehicleId"), ts = str(n, "eventTimestamp");
      OffsetDateTime time = OffsetDateTime.parse(ts);
      if (time.toInstant().isAfter(Instant.now().plus(p.maxFutureSkew()))) bad();
      switch (type) {
        case LOGIN_LOGOUT -> {
          en(n, "action", "IN OUT");
          str(n, "userId");
          text(n.get("userName"), p.maxOperatorName());
        }
        case VEHICLE_JOB -> {
          if (!str(n, "seqNo").matches("[0-9]+")) bad();
          str(n, "jobId");
          en(n, "jobType", "LOAD DSCH YARD");
          en(n, "jobAction", "LOAD UNLOAD");
          en(n, "jobState", "DISPATCH CANCEL FINISH UPDATE");
          nullable(n, "vesselVisit");
          JsonNode details = n.get("jobDetails");
          if (details == null || !details.isArray() || details.isEmpty()) bad();
          for (JsonNode d : details) {
            object(d, "container fromPosition toPosition conePlan pow twinFlag");
            JsonNode c = d.get("container");
            object(c, "cntrNo cntrIso vehiclePos weight");
            str(c, "cntrNo");
            str(c, "cntrIso");
            en(c, "vehiclePos", "F A M 0");
            JsonNode w = c.get("weight");
            if (w != null
                && !w.isNull()
                && (!w.isIntegralNumber() || !w.canConvertToInt() || w.intValue() < 0)) bad();
            position(d.get("fromPosition"));
            position(d.get("toPosition"));
            str(d, "conePlan");
            str(d, "pow");
            en(d, "twinFlag", "T Y N");
          }
        }
        case YARD_EVENT -> {
          str(n, "jobId");
          en(n, "eventType", "TZ TP EXIT");
          yard(n.get("toPosition"));
        }
        case OPERATION_STATUS -> {
          JsonNode area = n.get("area");
          object(area, "type toPosition");
          en(area, "type", "YARD QC");
          if (area.get("type").asText().equals("YARD")) yard(area.get("toPosition"));
          else crane(area.get("toPosition"), false);
          nullable(n, "craneId");
          JsonNode v = n.get("TPStatus");
          if (v == null
              || !v.isIntegralNumber()
              || !v.canConvertToInt()
              || v.intValue() < 1
              || v.intValue() > 6) bad();
        }
      }
      String canonical = mapper.writeValueAsString(sorted(n));
      return new Validated(
          n,
          new String(bytes, StandardCharsets.UTF_8),
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonical.getBytes(StandardCharsets.UTF_8))),
          id,
          vehicle,
          time,
          ts);
    } catch (FosException e) {
      throw e;
    } catch (Exception e) {
      throw new FosException(400, "INVALID_CONTRACT");
    }
  }

  private JsonNode sorted(JsonNode n) {
    if (n.isObject()) {
      ObjectNode o = mapper.createObjectNode();
      List<String> keys = new ArrayList<>();
      n.fieldNames().forEachRemaining(keys::add);
      Collections.sort(keys);
      for (String k : keys) o.set(k, sorted(n.get(k)));
      return o;
    }
    if (n.isArray()) {
      ArrayNode a = mapper.createArrayNode();
      n.forEach(v -> a.add(sorted(v)));
      return a;
    }
    return n;
  }

  private void position(JsonNode n) {
    object(n, "type yardPosition cranePosition");
    en(n, "type", "YARD QC");
    boolean y = n.get("type").asText().equals("YARD");
    JsonNode inactive = n.get(y ? "cranePosition" : "yardPosition");
    if (inactive != null && !inactive.isNull()) bad();
    if (y) yard(n.get("yardPosition"));
    else crane(n.get("cranePosition"), true);
  }

  private void yard(JsonNode n) {
    object(n, "blockId bay laneNo");
    str(n, "blockId");
    str(n, "bay");
    str(n, "laneNo");
  }

  private void crane(JsonNode n, boolean bay) {
    object(n, bay ? "QCId laneNo bay" : "QCId laneNo");
    str(n, "QCId");
    str(n, "laneNo");
    if (bay) nullable(n, "bay");
  }

  private void object(JsonNode n, String names) {
    if (n == null || !n.isObject()) bad();
    Set<String> s = new HashSet<>(Arrays.asList(names.split(" ")));
    n.fieldNames()
        .forEachRemaining(
            k -> {
              if (!s.contains(k)) bad();
            });
  }

  private String str(JsonNode n, String key) {
    return text(n.get(key), p.maxIdentifier());
  }

  private String text(JsonNode n, int max) {
    if (n == null || !n.isTextual() || n.textValue().isBlank() || n.textValue().length() > max)
      bad();
    return n.textValue();
  }

  private void nullable(JsonNode n, String key) {
    if (n.hasNonNull(key)) str(n, key);
  }

  private void en(JsonNode n, String key, String vals) {
    if (!Arrays.asList(vals.split(" ")).contains(str(n, key))) bad();
  }

  private void bad() {
    throw new FosException(400, "INVALID_CONTRACT");
  }
}
