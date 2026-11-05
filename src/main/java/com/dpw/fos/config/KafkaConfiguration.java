package com.dpw.fos.config;

import org.springframework.context.annotation.*;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfiguration {
  @Bean
  public CommonErrorHandler kafkaErrorHandler() {
    DefaultErrorHandler h =
        new DefaultErrorHandler(new FixedBackOff(2000, FixedBackOff.UNLIMITED_ATTEMPTS));
    h.setAckAfterHandle(false);
    h.setCommitRecovered(false);
    return h;
  }
}
