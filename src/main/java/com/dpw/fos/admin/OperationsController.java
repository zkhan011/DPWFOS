package com.dpw.fos.admin;

import com.dpw.fos.domain.FosException;
import com.dpw.fos.persistence.InboxRepository;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@ConditionalOnProperty(name = "fos.admin.enabled", havingValue = "true")
public class OperationsController {
  private final EndpointSettings settings;
  private final MessageHistory history;
  private final InboxRepository audit;

  public OperationsController(
      EndpointSettings settings, MessageHistory history, InboxRepository audit) {
    this.settings = settings;
    this.history = history;
    this.audit = audit;
  }

  @GetMapping("/admin/endpoints")
  public String endpoints(Model m) {
    m.addAttribute("routes", settings.list());
    m.addAttribute("allowedHosts", settings.allowedHosts());
    m.addAttribute("section", "endpoints");
    return "endpoints";
  }

  @PostMapping("/admin/endpoints/{route}")
  @PreAuthorize("hasRole('ADMIN')")
  public String save(
      @PathVariable String route,
      @RequestParam(defaultValue = "") String url,
      @RequestParam(defaultValue = "false") boolean confirmed,
      @RequestParam long revision,
      @RequestParam String reason,
      @RequestParam(defaultValue = "false") boolean reset,
      Authentication auth,
      RedirectAttributes flash) {
    try {
      settings.save(route, url, confirmed, revision, reason, auth.getName(), reset);
      flash.addFlashAttribute(
          "notice",
          "Endpoint saved. Messages without a previous attempt use this setting. Retries and"
              + " in-flight requests keep their original destination.");
    } catch (FosException e) {
      flash.addFlashAttribute("notice", friendly(e.code));
    }
    return "redirect:/admin/endpoints";
  }

  @GetMapping("/admin/messages")
  public String messages(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String state,
      @RequestParam(defaultValue = "") String target,
      Model m) {
    var rows = history.messages(page, search.trim(), state, target);
    paging(m, rows, page);
    m.addAttribute("search", search);
    m.addAttribute("state", state);
    m.addAttribute("target", target);
    m.addAttribute(
        "states",
        List.of(
            "PENDING",
            "IN_FLIGHT",
            "DELIVERED",
            "RETRY_WAIT",
            "BUSINESS_REJECTED",
            "UNKNOWN",
            "QUARANTINED",
            "REMOTE_ACCEPTED",
            "EXPIRED",
            "EXHAUSTED",
            "WAITING_SESSION"));
    m.addAttribute("section", "messages");
    return "messages";
  }

  @GetMapping("/admin/messages/{id}")
  public String detail(@PathVariable UUID id, Model m, Authentication auth) {
    m.addAttribute("message", history.detail(id));
    m.addAttribute("attempts", history.attempts(id));
    m.addAttribute("section", "messages");
    audit.audit(auth.getName(), "MESSAGE_DETAIL_VIEW", id, null, "OK");
    return "message-detail";
  }

  @GetMapping("/admin/kafka")
  public String kafka(
      @RequestParam(defaultValue = "0") int page,
      @RequestParam(defaultValue = "") String search,
      @RequestParam(defaultValue = "") String status,
      @RequestParam(defaultValue = "") String topic,
      Model m) {
    paging(m, history.kafka(page, search.trim(), status, topic.trim()), page);
    m.addAttribute("search", search);
    m.addAttribute("status", status);
    m.addAttribute("topic", topic);
    m.addAttribute("section", "kafka");
    return "kafka";
  }

  @GetMapping("/admin/kafka/{id}")
  public String kafkaDetail(@PathVariable UUID id, Model m, Authentication auth) {
    m.addAttribute("record", history.kafkaDetail(id));
    m.addAttribute("section", "kafka");
    audit.audit(auth.getName(), "KAFKA_DETAIL_VIEW", null, id.toString(), "OK");
    return "kafka-detail";
  }

  private void paging(Model m, List<Map<String, Object>> rows, int page) {
    m.addAttribute("rows", rows.stream().limit(25).toList());
    m.addAttribute("hasNext", rows.size() > 25);
    m.addAttribute("page", page);
  }

  static String friendly(String code) {
    return switch (code) {
      case "ENDPOINT_URL_NOT_ALLOWED" ->
          "Enter a complete URL on an approved host, without credentials, query parameters or"
              + " fragments. HTTPS is required outside the local profile.";
      case "DEDUPLICATION_REQUIRED" ->
          "Production endpoints must confirm deduplication by message ID.";
      case "SETTINGS_CHANGED_REFRESH" ->
          "Another administrator changed this route. Refresh and review the current endpoint before"
              + " saving again.";
      case "CHANGE_REASON_REQUIRED" -> "Provide a change reason of 1–512 characters.";
      case "NOT_FOUND" ->
          "This message was not found. It may have expired under the retention policy.";
      default -> "Unable to complete the request: " + code;
    };
  }

  @ExceptionHandler(FosException.class)
  public String error(FosException e, RedirectAttributes flash) {
    flash.addFlashAttribute("notice", friendly(e.code));
    return "redirect:/admin/messages";
  }
}
