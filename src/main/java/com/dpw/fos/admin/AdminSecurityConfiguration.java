package com.dpw.fos.admin;

import com.dpw.fos.config.FosProperties;
import jakarta.servlet.DispatcherType;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@ConditionalOnProperty(name = "fos.admin.enabled", havingValue = "true")
public class AdminSecurityConfiguration {
  @Bean
  @Order(1)
  SecurityFilterChain adminChain(HttpSecurity http, FosProperties p, Environment env)
      throws Exception {
    var encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();
    String username = env.getProperty("fos.admin.username", "admin");
    String hash = env.getProperty("fos.admin.password-hash", "");
    if (hash.isBlank() && p.devAuth() && !p.production())
      hash = encoder.encode(env.getRequiredProperty("fos.local-password"));
    if (!hash.startsWith("{bcrypt}"))
      throw new IllegalStateException("Admin requires an external {bcrypt} password hash");
    var users =
        new InMemoryUserDetailsManager(
            User.withUsername(username).password(hash).roles("ADMIN").build());
    var provider = new DaoAuthenticationProvider(users);
    provider.setPasswordEncoder(encoder);
    http.securityMatcher("/admin", "/admin/**", "/WEB-INF/views/**")
        .authenticationManager(new ProviderManager(provider))
        .authorizeHttpRequests(
            a ->
                a.dispatcherTypeMatchers(DispatcherType.FORWARD)
                    .permitAll()
                    .requestMatchers("/admin/login", "/admin/assets/**")
                    .permitAll()
                    .anyRequest()
                    .hasAnyRole("ADMIN", "VIEWER"))
        .formLogin(
            f ->
                f.loginPage("/admin/login")
                    .loginProcessingUrl("/admin/login")
                    .defaultSuccessUrl("/admin", true)
                    .failureUrl("/admin/login?error"))
        .logout(l -> l.logoutUrl("/admin/logout").logoutSuccessUrl("/admin/login?logout"))
        .sessionManagement(s -> s.sessionFixation(f -> f.migrateSession()))
        .headers(
            h ->
                h.contentSecurityPolicy(
                    c ->
                        c.policyDirectives(
                            "default-src 'self'; style-src 'self'; script-src 'none';"
                                + " frame-ancestors 'none'; form-action 'self'")));
    // CSRF remains enabled for every browser mutation including login and logout.
    return http.build();
  }
}
