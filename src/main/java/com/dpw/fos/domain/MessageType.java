package com.dpw.fos.domain;

public enum MessageType {
  VEHICLE_JOB("TOS", "FMS", "vehicle-job"),
  LOGIN_LOGOUT("FMS", "TOS", "vehicle-login-logout"),
  YARD_EVENT("FMS", "TOS", "vehicle-yard-event"),
  OPERATION_STATUS("FMS", "TOS", "vehicle-operation-status");
  public final String source, target, route;

  MessageType(String source, String target, String route) {
    this.source = source;
    this.target = target;
    this.route = route;
  }
}
