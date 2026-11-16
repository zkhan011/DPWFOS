package com.dpw.fos.admin;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;

class AdminPasswordTest {
  private final org.springframework.security.crypto.password.PasswordEncoder encoder =
      PasswordEncoderFactories.createDelegatingPasswordEncoder();

  @Test
  void localFallbackMatchesLocalPassword() {
    var env = new MockEnvironment().withProperty("fos.local-password", "local-test-password-only");
    env.setActiveProfiles("local");
    assertTrue(encoder.matches("local-test-password-only", AdminPassword.resolve(env, true, false, encoder)));
  }

  @Test
  void fallbackRequiresLocalProfileAndNonProduction() {
    var env = new MockEnvironment().withProperty("fos.local-password", "local-test-password-only");
    assertThrows(IllegalStateException.class, () -> AdminPassword.resolve(env, true, false, encoder));
    env.setActiveProfiles("local");
    assertThrows(IllegalStateException.class, () -> AdminPassword.resolve(env, true, true, encoder));
    assertThrows(IllegalStateException.class, () -> AdminPassword.resolve(env, false, false, encoder));
  }

  @Test
  void shortLocalPasswordIsRejected() {
    var env = new MockEnvironment().withProperty("fos.local-password", "short");
    env.setActiveProfiles("local");
    assertThrows(IllegalStateException.class, () -> AdminPassword.resolve(env, true, false, encoder));
  }

  @Test
  void acceptsDelegatedAndRawBcryptWithoutChangingPassword() {
    String hash = encoder.encode("admin-test-password-only");
    for (String input : new String[] {hash, hash.substring(8), "  " + hash + "  "}) {
      var env = new MockEnvironment().withProperty("fos.admin.password-hash", input);
      assertTrue(encoder.matches("admin-test-password-only", AdminPassword.resolve(env, false, true, encoder)));
    }
  }

  @Test
  void malformedExplicitHashIsNotSilentlyReplaced() {
    for (String input : new String[] {"plain-password", "{bcrypt}broken", "\"{bcrypt}quoted\""}) {
      var env = new MockEnvironment().withProperty("fos.admin.password-hash", input)
          .withProperty("fos.local-password", "local-test-password-only");
      env.setActiveProfiles("local");
      assertThrows(IllegalStateException.class, () -> AdminPassword.resolve(env, true, false, encoder));
    }
  }
}
