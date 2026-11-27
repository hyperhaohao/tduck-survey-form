package com.tduck.cloud.api.demand;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 钉钉工作通知/待办配置；真实密钥只放外部 secrets。 */
@Data
@Component
@ConfigurationProperties(prefix = "ruima.demand.dingtalk")
public class DemandDingTalkProperties {
  /** 部署总开关，默认关闭。 */
  private boolean enabled;
  private String appKey;
  private String appSecret;
  private long agentId;
  /** 待办创建者的本地 ac_user.id；未配置时尝试 creatorUnionId。 */
  private long creatorUserId;
  /** 待办创建者 unionId；配置后优先使用。 */
  private String creatorUnionId;
  private String apiBase = "https://oapi.dingtalk.com";
  private String apiV1Base = "https://api.dingtalk.com";
  private String linkBase;
  /** 管理员配置页写入的受限运行时文件；启动时覆盖上面的外部 secrets。 */
  private String runtimeConfig = "/data/runtime/dingtalk.json";
  private long dispatchDelayMs = 30000;
  private int dispatchBatchSize = 50;
  private int maxAttempts = 5;
  private int leaseSeconds = 120;
  private int monthlyCallLimit = 9000;
  private int connectTimeoutMs = 3000;
  private int readTimeoutMs = 10000;
  private long syncRootDeptId = 1;
  private int syncPageSize = 100;
  private boolean todoEnabled = true;
}
