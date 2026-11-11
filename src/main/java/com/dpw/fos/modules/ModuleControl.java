package com.dpw.fos.modules;

import com.dpw.fos.domain.FosException;
import com.dpw.fos.persistence.InboxRepository;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ModuleControl {
  private static final Set<String> NAMES = Set.of("simulator", "uitv");
  private final JdbcTemplate db;
  private final TransactionTemplate tx;
  private final InboxRepository audit;

  public ModuleControl(JdbcTemplate db, TransactionTemplate tx, InboxRepository audit) {
    this.db = db;
    this.tx = tx;
    this.audit = audit;
  }

  public boolean enabled(String name) {
    check(name);
    return Boolean.TRUE.equals(
        db.queryForObject("SELECT enabled FROM module_control WHERE name=?", Boolean.class, name));
  }

  public List<Map<String, Object>> list() {
    return db.queryForList(
        "SELECT name,enabled,updated_at,updated_by FROM module_control ORDER BY name");
  }

  public void set(String name, boolean enabled, String actor, String reason) {
    check(name);
    if (reason == null || reason.isBlank() || reason.length() > 512)
      throw new FosException(400, "REASON_REQUIRED");
    tx.executeWithoutResult(
        s -> {
          db.update(
              "UPDATE module_control SET enabled=?,updated_by=?,updated_at=clock_timestamp() WHERE"
                  + " name=?",
              enabled,
              actor,
              name);
          audit.audit(
              actor,
              "MODULE_" + (enabled ? "ENABLE" : "DISABLE"),
              null,
              name + ": " + reason,
              "OK");
        });
  }

  private void check(String name) {
    if (!NAMES.contains(name)) throw new FosException(404, "MODULE_NOT_FOUND");
  }
}
