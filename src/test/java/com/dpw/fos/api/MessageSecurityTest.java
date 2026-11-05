package com.dpw.fos.api;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.dpw.fos.application.AcceptanceService;
import com.dpw.fos.config.FosProperties;
import com.dpw.fos.persistence.InboxRepository;
import com.dpw.fos.security.SecurityConfiguration;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MessageController.class)
@Import(SecurityConfiguration.class)
class MessageSecurityTest {
  @Autowired MockMvc mvc;
  @MockBean AcceptanceService acceptance;
  @MockBean InboxRepository repo;
  @MockBean FosProperties p;
  @MockBean JwtDecoder decoder;

  @BeforeEach
  void init() {
    when(p.principals()).thenReturn(Map.of("tos-client", "TOS", "fms-client", "FMS"));
    when(p.maxBytes()).thenReturn(262144);
  }

  @Test
  void noTokenAndWrongScopeAreRejected() throws Exception {
    mvc.perform(
            post("/fos/api/v1/inbound/tos/vehicle-jobs")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post("/fos/api/v1/inbound/tos/vehicle-jobs")
                .with(jwt())
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(acceptance);
  }

  @Test
  void scopeDoesNotConferSourceIdentity() throws Exception {
    mvc.perform(
            post("/fos/api/v1/inbound/tos/vehicle-jobs")
                .with(
                    jwt()
                        .jwt(j -> j.subject("fms-client"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_fos.jobs.write")))
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isForbidden());
    verifyNoInteractions(acceptance);
  }

  @Test
  void otherSourceCannotInspectReceipt() throws Exception {
    UUID id = UUID.randomUUID();
    when(repo.get(id)).thenReturn(new InboxRepository.Receipt(id, "job", "TOS", "PENDING", null));
    mvc.perform(get("/fos/api/v1/messages/" + id).with(jwt().jwt(j -> j.subject("fms-client"))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/fos/api/v1/messages/" + id).with(jwt().jwt(j -> j.subject("tos-client"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value("ACCEPTED"));
  }

  @Test
  void databaseFailureNeverReturnsAccepted() throws Exception {
    when(acceptance.accept(any(), any(), any()))
        .thenThrow(new DataAccessResourceFailureException("private database host"));
    mvc.perform(
            post("/fos/api/v1/inbound/tos/vehicle-jobs")
                .with(
                    jwt()
                        .jwt(j -> j.subject("tos-client"))
                        .authorities(new SimpleGrantedAuthority("SCOPE_fos.jobs.write")))
                .contentType("application/json")
                .content(Files.readAllBytes(Path.of("contracts/examples/vehicle-job.json"))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("DURABLE_STORE_UNAVAILABLE"));
  }
}
