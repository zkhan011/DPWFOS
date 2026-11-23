package com.dpw.fos.admin;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.dpw.fos.TestProperties;
import com.dpw.fos.domain.FosException;
import com.dpw.fos.persistence.InboxRepository;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.transaction.support.TransactionTemplate;

class EndpointSettingsTest {
  @Test
  void rejectsUnapprovedAndCredentialBearingUrls() {
    var env = new MockEnvironment();
    env.setActiveProfiles("local");
    var settings =
        new EndpointSettings(
            mock(JdbcTemplate.class),
            mock(TransactionTemplate.class),
            TestProperties.create(),
            env,
            mock(InboxRepository.class));
    assertEquals("http://localhost:8080/jobs", settings.validate("http://localhost:8080/jobs"));
    for (String url :
        java.util.List.of(
            "http://evil.example/jobs",
            "http://user:secret@localhost/jobs",
            "http://localhost/jobs?token=secret",
            "http://localhost/jobs#x",
            "file:///tmp/a",
            "http://localhost:0/jobs",
            "http://localhost:65536/jobs"))
      assertThrows(FosException.class, () -> settings.validate(url));
    env.setActiveProfiles("uat");
    assertThrows(FosException.class, () -> settings.validate("http://localhost/jobs"));
    assertEquals("https://localhost/jobs", settings.validate("https://localhost/jobs"));
  }

  @Test
  void previewsMaskNestedCredentialsWithoutChangingBusinessData() {
    var history =
        new MessageHistory(
            mock(JdbcTemplate.class), new com.fasterxml.jackson.databind.ObjectMapper());
    String result =
        history.display(
            "{\"vehicleId\":\"T501\",\"nested\":[{\"access_token\":\"sensitive\",\"password\":\"hidden\"}]}");
    assertTrue(result.contains("T501"));
    assertTrue(result.contains("[REDACTED]"));
    assertFalse(result.contains("sensitive"));
    assertFalse(result.contains("hidden"));
  }
}
