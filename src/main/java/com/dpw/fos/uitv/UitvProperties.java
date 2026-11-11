package com.dpw.fos.uitv;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("fos.uitv")
public record UitvProperties(
    @DefaultValue("false") boolean available,
    @DefaultValue("localhost") String host,
    @DefaultValue("5671") int port,
    @DefaultValue("") String username,
    @DefaultValue("") String password,
    @DefaultValue("/") String virtualHost,
    @DefaultValue("true") boolean tls,
    @DefaultValue("tenant51.telemetry") String inputQueue,
    @DefaultValue("dpwfms.business.v1") String outputExchange,
    @DefaultValue("telemetry.asset.position.v1") String routingKey,
    @DefaultValue("FUEL") String defaultVehicleType,
    @DefaultValue("TRACTOR") String defaultEquipmentType,
    @DefaultValue("DIESEL") String defaultFuelType,
    @DefaultValue("20") double lowFuelThreshold,
    @DefaultValue("20") double lowBatteryThreshold,
    @DefaultValue("262144") int maxBytes,
    @DefaultValue("5000") int confirmTimeoutMs) {}
