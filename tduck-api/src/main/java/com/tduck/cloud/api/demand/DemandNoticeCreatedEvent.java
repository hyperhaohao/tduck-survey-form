package com.tduck.cloud.api.demand;

import java.sql.Timestamp;
import lombok.AllArgsConstructor;
import lombok.Getter;

/** 站内消息创建事件；用于事务提交后写钉钉 outbox。 */
@Getter
@AllArgsConstructor
public class DemandNoticeCreatedEvent {
  private final String noticeId;
  private final Long userId;
  private final String kind;
  private final String formKey;
  private final String demandId;
  private final String publicationId;
  private final Integer roundNo;
  private final Integer stepNo;
  private final String title;
  private final String content;
  private final Timestamp dueAt;
}
