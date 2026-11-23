package com.dpw.fos.admin;

import com.dpw.fos.domain.FosException;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class MessageHistory {
  private final JdbcTemplate db;
  private final ObjectMapper mapper;

  public MessageHistory(JdbcTemplate db, ObjectMapper mapper) {
    this.db = db;
    this.mapper = mapper;
  }

  private void page(int page, String search) {
    if (page < 0 || page > 10000 || search.length() > 128)
      throw new FosException(400, "INVALID_SEARCH");
  }

  public List<Map<String, Object>> messages(int page, String search, String state, String target) {
    page(page, search);
    return db.queryForList(
        """
SELECT i.receipt_id,i.msg_id,i.vehicle_id,i.message_type,i.source,i.received_at,d.target,d.state,d.attempt_count,d.last_outcome
FROM message_inbox i JOIN delivery d USING(receipt_id)
WHERE (?='' OR i.msg_id=? OR i.vehicle_id=? OR i.receipt_id::text=?)
 AND (?='' OR d.state=?) AND (?='' OR d.target=?)
ORDER BY i.received_at DESC,i.receipt_id LIMIT 26 OFFSET ?
""",
        search,
        search,
        search,
        search,
        state,
        state,
        target,
        target,
        page * 25);
  }

  public Map<String, Object> detail(UUID id) {
    var rows =
        db.queryForList(
            """
SELECT i.*,d.target,d.state,d.attempt_count,d.last_outcome,d.next_attempt_at,d.completed_at
FROM message_inbox i JOIN delivery d USING(receipt_id) WHERE i.receipt_id=?
""",
            id);
    if (rows.isEmpty()) throw new FosException(404, "NOT_FOUND");
    var row = rows.getFirst();
    row.put("display_payload", display(row.get("raw_payload")));
    row.put("display_metadata", display(row.get("transport_metadata")));
    return row;
  }

  public List<Map<String, Object>> attempts(UUID id) {
    var rows =
        db.queryForList(
            """
SELECT a.* FROM delivery_attempt a JOIN delivery d USING(delivery_id)
WHERE d.receipt_id=? ORDER BY a.attempt_number
""",
            id);
    rows.forEach(r -> r.put("display_response", display(r.get("response_body"))));
    return rows;
  }

  public List<Map<String, Object>> kafka(int page, String search, String status, String topic) {
    page(page, search);
    if (topic.length() > 249) throw new FosException(400, "INVALID_SEARCH");
    return db.queryForList(
        """
SELECT k.log_id,k.cluster_id,k.topic,k.partition_id,k.offset_id,k.record_key,k.received_at,k.status,k.failure_code,k.receipt_id,i.msg_id
FROM kafka_message_log k LEFT JOIN message_inbox i USING(receipt_id)
WHERE (?='' OR k.record_key=? OR i.msg_id=? OR k.receipt_id::text=?)
 AND (?='' OR k.status=?) AND (?='' OR k.topic=?)
ORDER BY k.received_at DESC,k.log_id LIMIT 26 OFFSET ?
""",
        search,
        search,
        search,
        search,
        status,
        status,
        topic,
        topic,
        page * 25);
  }

  public Map<String, Object> kafkaDetail(UUID id) {
    var rows = db.queryForList("SELECT * FROM kafka_message_log WHERE log_id=?", id);
    if (rows.isEmpty()) throw new FosException(404, "NOT_FOUND");
    var row = rows.getFirst();
    byte[] raw = (byte[]) row.remove("raw_bytes");
    row.put(
        "display_payload",
        raw == null
            ? "Payload not retained (size limit or retention policy)."
            : display(new String(raw, StandardCharsets.UTF_8)));
    return row;
  }

  public String display(Object value) {
    if (value == null) return "Not available — not captured or removed by retention.";
    String text = value.toString();
    try {
      JsonNode tree = mapper.readTree(text);
      if (tree == null) return text;
      redact(tree);
      return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(tree);
    } catch (Exception e) {
      return text;
    }
  }

  private void redact(JsonNode node) {
    if (node.isObject()) {
      var fields = new ArrayList<String>();
      node.fieldNames().forEachRemaining(fields::add);
      for (String key : fields) {
        String name = key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (Set.of(
                "password",
                "passwd",
                "secret",
                "token",
                "accesstoken",
                "refreshtoken",
                "bearertoken",
                "authorization",
                "apikey")
            .contains(name)) ((ObjectNode) node).put(key, "[REDACTED]");
        else redact(node.get(key));
      }
    } else if (node.isArray()) node.forEach(this::redact);
  }
}
