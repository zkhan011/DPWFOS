package com.dpw.fos.admin;

import com.dpw.fos.domain.FosException;
import com.dpw.fos.simulator.SimulatorConsole;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/admin/simulator/console")
@ConditionalOnProperty(name = "fos.admin.enabled", havingValue = "true")
@PreAuthorize("hasRole('ADMIN')")
public class SimulatorConsoleController {
  private final SimulatorConsole console;

  public SimulatorConsoleController(SimulatorConsole console) {
    this.console = console;
  }

  @GetMapping("/status")
  public ResponseEntity<?> status() {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(console.snapshot());
  }

  @GetMapping("/template")
  public Map<String, String> template(@RequestParam(defaultValue = "SIM-TRUCK-01") String vehicle) {
    if (vehicle.isBlank() || vehicle.length() > 128) throw new FosException(400, "INVALID_VEHICLE");
    return Map.of("payload", console.template(vehicle));
  }

  @PostMapping("/settings")
  public Map<String, Object> configure(
      @RequestParam String mode,
      @RequestParam(defaultValue = "") String url,
      @RequestParam(defaultValue = "false") boolean idempotency,
      @RequestParam long revision,
      Authentication auth) {
    console.configure(mode, url, idempotency, revision, auth.getName());
    return console.settings();
  }

  @PostMapping("/validate")
  public Object validate(@RequestParam String payload) {
    return console.validate(payload);
  }

  @PostMapping("/dispatch")
  public Object dispatch(
      @RequestParam String payload,
      @RequestParam String mode,
      @RequestParam long revision,
      Authentication auth) {
    return console.dispatch(payload, mode, revision, auth.getName());
  }

  @PostMapping("/session")
  public Map<String, Boolean> session(
      @RequestParam String vehicle, @RequestParam String action, Authentication auth) {
    console.session(vehicle, action, auth.getName());
    return Map.of("accepted", true);
  }

  @ExceptionHandler(FosException.class)
  public ResponseEntity<?> error(FosException e) {
    return ResponseEntity.status(e.status).body(Map.of("error", e.code));
  }
}
