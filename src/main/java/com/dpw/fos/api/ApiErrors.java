package com.dpw.fos.api;

import com.dpw.fos.domain.FosException;
import java.util.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class ApiErrors {
  @ExceptionHandler(FosException.class)
  ResponseEntity<?> business(FosException e) {
    return error(e.status, e.code);
  }

  @ExceptionHandler(DataAccessException.class)
  ResponseEntity<?> database(DataAccessException e) {
    return error(503, "DURABLE_STORE_UNAVAILABLE");
  }

  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  ResponseEntity<?> argument(Exception e) {
    return error(400, "INVALID_PARAMETER");
  }

  private ResponseEntity<?> error(int status, String code) {
    return ResponseEntity.status(status)
        .body(
            new MessageController.Envelope(
                null, code, code, Map.of("correlationId", UUID.randomUUID())));
  }
}
