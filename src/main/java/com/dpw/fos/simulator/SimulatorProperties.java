package com.dpw.fos.simulator;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("fos.simulator")
public record SimulatorProperties(
    @DefaultValue("false") boolean available,
    @DefaultValue("8090") int port,
    @DefaultValue("http://127.0.0.1:8080") String apiBaseUrl,
    @DefaultValue("tos-local") String tosUser,
    @DefaultValue("fms-local") String fmsUser,
    @DefaultValue("") String password) {}
