package com.dpw.fos.domain;

public class FosException extends RuntimeException {
  public final int status;
  public final String code;

  public FosException(int status, String code) {
    super(code);
    this.status = status;
    this.code = code;
  }
}
