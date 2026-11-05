package com.dpw.fos.api;

import com.dpw.fos.application.AcceptanceService;
import com.dpw.fos.config.FosProperties;
import com.dpw.fos.domain.*;
import com.dpw.fos.persistence.InboxRepository;
import com.fasterxml.jackson.databind.*;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/fos/api/v1")
public class MessageController {
  private final AcceptanceService accept;
  private final InboxRepository repo;
  private final FosProperties p;
  private final ObjectMapper mapper;

  public MessageController(
      AcceptanceService accept, InboxRepository repo, FosProperties p, ObjectMapper mapper) {
    this.accept = accept;
    this.repo = repo;
    this.p = p;
    this.mapper = mapper;
  }

  public record Envelope(String msgId, String code, String message, Object data) {}

  @PostMapping(value = "/inbound/tos/vehicle-jobs", consumes = "application/json")
  public ResponseEntity<Envelope> jobs(HttpServletRequest req, Authentication auth)
      throws IOException {
    return inbound(MessageType.VEHICLE_JOB, req, auth);
  }

  @PostMapping(value = "/inbound/fms/vehicle-login-logout", consumes = "application/json")
  public ResponseEntity<Envelope> login(HttpServletRequest req, Authentication auth)
      throws IOException {
    return inbound(MessageType.LOGIN_LOGOUT, req, auth);
  }

  private ResponseEntity<Envelope> inbound(
      MessageType type, HttpServletRequest req, Authentication auth) throws IOException {
    if (!type.source.equals(source(auth))) throw new FosException(403, "SOURCE_NOT_ALLOWED");
    var receipt = accept.accept(type, read(req), "{\"transport\":\"HTTP\"}");
    int status =
        receipt.state().equals("DELIVERED")
            ? 200
            : Set.of("PENDING", "IN_FLIGHT", "RETRY_WAIT", "REMOTE_ACCEPTED")
                    .contains(receipt.state())
                ? 202
                : 409;
    return ResponseEntity.status(status)
        .location(URI.create("/fos/api/v1/messages/" + receipt.receiptId()))
        .body(envelope(receipt));
  }

  @GetMapping("/messages/{receiptId}")
  public Envelope status(@PathVariable UUID receiptId, Authentication auth) {
    var r = repo.get(receiptId);
    boolean support =
        auth.getAuthorities().stream()
            .anyMatch(a -> a.getAuthority().equals("SCOPE_fos.support.read"));
    if (!support && !r.source().equals(source(auth))) throw new FosException(404, "NOT_FOUND");
    if (support) repo.audit(auth.getName(), "READ_RECEIPT", receiptId, null, "OK");
    return envelope(r);
  }

  @GetMapping("/operations/deliveries")
  public Object list(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "25") int size,
      @RequestParam(required = false) String state,
      Authentication auth) {
    var rows = repo.list(page, size, state);
    repo.audit(auth.getName(), "LIST_DELIVERIES", null, null, "OK");
    return Map.of("page", page, "size", size, "items", rows);
  }

  @PostMapping(value = "/operations/deliveries/{receiptId}/replay", consumes = "application/json")
  public Envelope replay(
      @PathVariable UUID receiptId, HttpServletRequest request, Authentication auth)
      throws IOException {
    JsonNode body;
    try {
      body = mapper.readTree(read(request));
    } catch (Exception e) {
      throw new FosException(400, "INVALID_REPLAY");
    }
    if (body == null
        || !body.isObject()
        || body.size() != 2
        || !body.path("reason").isTextual()
        || !body.path("requestToken").isTextual()) throw new FosException(400, "INVALID_REPLAY");
    String reason = body.get("reason").textValue(), token = body.get("requestToken").textValue();
    if (reason.isBlank() || reason.length() > 1024 || token.isBlank() || token.length() > 128)
      throw new FosException(400, "INVALID_REPLAY");
    repo.replay(receiptId, auth.getName(), token, reason);
    return envelope(repo.get(receiptId));
  }

  private byte[] read(HttpServletRequest req) throws IOException {
    byte[] bytes = req.getInputStream().readNBytes(p.maxBytes() + 1);
    if (bytes.length > p.maxBytes()) throw new FosException(413, "PAYLOAD_TOO_LARGE");
    return bytes;
  }

  private String source(Authentication a) {
    return p.principals() == null ? null : p.principals().get(a.getName());
  }

  private Envelope envelope(InboxRepository.Receipt r) {
    String code =
        r.state().equals("DELIVERED")
            ? "0"
            : Set.of("PENDING", "IN_FLIGHT", "RETRY_WAIT").contains(r.state())
                ? "ACCEPTED"
                : r.state();
    return new Envelope(
        r.msgId(),
        code,
        r.state().equals("DELIVERED") ? "success" : r.state().toLowerCase(Locale.ROOT),
        Map.of("receiptId", r.receiptId(), "state", r.state()));
  }
}
