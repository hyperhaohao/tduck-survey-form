package com.tduck.cloud.api.demand;

import lombok.Getter;

/** 钉钉 API 错误；transient 决定是否退避重试。 */
@Getter
public class DemandDingTalkException extends RuntimeException {
  private final int code;
  private final boolean transientError;

  public DemandDingTalkException(int code, String message, boolean transientError) {
    super(message);
    this.code = code;
    this.transientError = transientError;
  }
}
