package com.dpw.fos.admin;

import com.dpw.fos.domain.FosException;
import com.dpw.fos.modules.ModuleControl;
import com.dpw.fos.persistence.InboxRepository;
import com.dpw.fos.simulator.SimulatorPlugin;
import com.dpw.fos.uitv.*;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@ConditionalOnProperty(name = "fos.admin.enabled", havingValue = "true")
public class AdminController {
  private final ModuleControl controls;
  private final SimulatorPlugin simulator;
  private final UitvBridge bridge;
  private final UitvTransformer transformer;
  private final InboxRepository repo;

  public AdminController(
      ModuleControl controls,
      SimulatorPlugin simulator,
      UitvBridge bridge,
      UitvTransformer transformer,
      InboxRepository repo) {
    this.controls = controls;
    this.simulator = simulator;
    this.bridge = bridge;
    this.transformer = transformer;
    this.repo = repo;
  }

  @GetMapping("/admin/login")
  public String login() {
    return "login";
  }

  @GetMapping({"/admin", "/admin/"})
  public String dashboard(
      Model m, @RequestParam(defaultValue = "0") int page, Authentication auth) {
    m.addAttribute("modules", controls.list());
    m.addAttribute("simulator", simulator);
    m.addAttribute("bridge", bridge);
    m.addAttribute("history", simulator.history());
    m.addAttribute("deliveries", repo.list(page, 25, null));
    m.addAttribute("page", page);
    repo.audit(auth.getName(), "ADMIN_VIEW", null, null, "OK");
    return "dashboard";
  }

  @PostMapping("/admin/modules/{name}")
  @PreAuthorize("hasRole('ADMIN')")
  public String toggle(
      @PathVariable String name,
      @RequestParam boolean enabled,
      @RequestParam String reason,
      Authentication auth,
      RedirectAttributes flash) {
    if (enabled
        && ((name.equals("uitv") && !bridge.isAvailable())
            || (name.equals("simulator") && !simulator.isAvailable())))
      throw new FosException(409, "MODULE_UNAVAILABLE_IN_THIS_DEPLOYMENT");
    controls.set(name, enabled, auth.getName(), reason);
    flash.addFlashAttribute(
        "notice",
        name
            + " "
            + (enabled ? "enable" : "disable")
            + " requested. Runtime reconciles within one second.");
    return "redirect:/admin";
  }

  @PostMapping("/admin/simulator/run")
  @PreAuthorize("hasRole('ADMIN')")
  public String run(
      @RequestParam(defaultValue = "1") int count,
      @RequestParam(defaultValue = "SUCCESS") String mode,
      Authentication auth,
      RedirectAttributes flash) {
    repo.audit(
        auth.getName(), "SIMULATOR_RUN", null, "count=" + count + ",mode=" + mode, "REQUESTED");
    simulator.run(count, mode);
    flash.addFlashAttribute("notice", "Simulation queued. Refresh to follow the round trip.");
    return "redirect:/admin";
  }

  @PostMapping("/admin/uitv/preview")
  @PreAuthorize("hasRole('ADMIN')")
  public String preview(@RequestParam String payload, Authentication auth, RedirectAttributes flash)
      throws Exception {
    byte[] input = payload.getBytes(StandardCharsets.UTF_8);
    try {
      flash.addFlashAttribute("preview", transformer.transform(input).toPrettyString());
    } catch (IllegalArgumentException e) {
      flash.addFlashAttribute(
          "preview",
          "Invalid telemetry. Supply a JSON object or array within the configured size limit.");
    }
    repo.audit(auth.getName(), "UITV_PREVIEW", null, null, "OK");
    return "redirect:/admin#uitv";
  }

  @ExceptionHandler(FosException.class)
  public String errors(FosException e, RedirectAttributes flash) {
    flash.addFlashAttribute("notice", e.code);
    return "redirect:/admin";
  }
}
