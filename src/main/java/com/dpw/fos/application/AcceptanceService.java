package com.dpw.fos.application;

import com.dpw.fos.contract.ContractValidator;
import com.dpw.fos.domain.MessageType;
import com.dpw.fos.persistence.InboxRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Service;

@Service
public class AcceptanceService {
  private final ContractValidator validator;
  private final InboxRepository repo;
  private final MeterRegistry metrics;

  public AcceptanceService(
      ContractValidator validator, InboxRepository repo, MeterRegistry metrics) {
    this.validator = validator;
    this.repo = repo;
    this.metrics = metrics;
  }

  public InboxRepository.Receipt accept(MessageType type, byte[] bytes, String metadata) {
    var v = validator.validate(type, bytes);
    var r = repo.accept(type, v, metadata);
    metrics.counter("fos.ingress.accepted", "type", type.name()).increment();
    return r;
  }
}
