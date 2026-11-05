package com.dpw.fos.security;

import com.dpw.fos.config.FosProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableMethodSecurity
public class SecurityConfiguration {
  @Bean
  SecurityFilterChain chain(HttpSecurity http, FosProperties p) throws Exception {
    http.csrf(c -> c.disable())
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            a ->
                a.requestMatchers("/actuator/health/**", "/actuator/health")
                    .hasAuthority("SCOPE_fos.health")
                    .requestMatchers("/actuator/prometheus")
                    .hasAuthority("SCOPE_fos.metrics")
                    .requestMatchers("/fos/api/v1/inbound/tos/vehicle-jobs")
                    .hasAuthority("SCOPE_fos.jobs.write")
                    .requestMatchers("/fos/api/v1/inbound/fms/vehicle-login-logout")
                    .hasAuthority("SCOPE_fos.sessions.write")
                    .requestMatchers("/fos/api/v1/operations/deliveries/*/replay")
                    .hasAuthority("SCOPE_fos.replay")
                    .requestMatchers("/fos/api/v1/operations/**")
                    .hasAuthority("SCOPE_fos.support.read")
                    .requestMatchers("/fos/api/v1/messages/*")
                    .authenticated()
                    .anyRequest()
                    .denyAll());
    if (p.devAuth()) http.httpBasic(Customizer.withDefaults());
    else http.oauth2ResourceServer(o -> o.jwt(Customizer.withDefaults()));
    return http.build();
  }

  @Bean
  @ConditionalOnProperty(name = "fos.dev-auth", havingValue = "false", matchIfMissing = true)
  JwtDecoder decoder(FosProperties p, Environment env) {
    String issuer = env.getRequiredProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri");
    if (p.audience() == null || p.audience().isBlank())
      throw new IllegalStateException("JWT audience required");
    NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuer);
    OAuth2TokenValidator<Jwt> audience =
        jwt ->
            jwt.getAudience().contains(p.audience())
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Wrong audience", null));
    decoder.setJwtValidator(
        new DelegatingOAuth2TokenValidator<>(
            JwtValidators.createDefaultWithIssuer(issuer), audience));
    return decoder;
  }

  @Bean
  @ConditionalOnProperty(name = "fos.dev-auth", havingValue = "true")
  UserDetailsService mockUsers(Environment env) {
    String pw = env.getRequiredProperty("fos.local-password");
    if (pw.length() < 16)
      throw new IllegalStateException("Local password must be at least 16 characters");
    var enc =
        org.springframework.security.crypto.factory.PasswordEncoderFactories
            .createDelegatingPasswordEncoder();
    String hash = enc.encode(pw);
    return new InMemoryUserDetailsManager(
        User.withUsername("tos-local").password(hash).authorities("SCOPE_fos.jobs.write").build(),
        User.withUsername("fms-local")
            .password(hash)
            .authorities("SCOPE_fos.sessions.write")
            .build(),
        User.withUsername("support-local")
            .password(hash)
            .authorities(
                "SCOPE_fos.support.read",
                "SCOPE_fos.replay",
                "SCOPE_fos.health",
                "SCOPE_fos.metrics")
            .build());
  }
}
