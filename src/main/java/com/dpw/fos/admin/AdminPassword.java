package com.dpw.fos.admin;

import java.util.Arrays;
import org.springframework.core.env.Environment;
import org.springframework.security.crypto.password.PasswordEncoder;

final class AdminPassword {
  private AdminPassword() {}

  static String resolve(Environment env, boolean devAuth, boolean production, PasswordEncoder encoder) {
    String hash = env.getProperty("fos.admin.password-hash", "").trim();
    boolean local = Arrays.asList(env.getActiveProfiles()).contains("local");
    if (hash.isEmpty() && local && devAuth && !production) {
      String password = env.getProperty("fos.local-password", "");
      if (password.length() < 16)
        throw new IllegalStateException("Set FOS_LOCAL_PASSWORD to at least 16 characters for local admin/API login");
      return encoder.encode(password);
    }
    // Accept standard raw BCrypt hashes as well as Spring's delegated format.
    if (hash.matches("\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}")) hash = "{bcrypt}" + hash;
    if (!hash.matches("\\{bcrypt}\\$2[aby]\\$\\d{2}\\$[./A-Za-z0-9]{53}"))
      throw new IllegalStateException("Invalid fos.admin.password-hash: supply a BCrypt hash without quotes; "
          + "for local login leave FOS_LOCAL_ADMIN_PASSWORD_HASH unset and set FOS_LOCAL_PASSWORD");
    return hash;
  }
}
