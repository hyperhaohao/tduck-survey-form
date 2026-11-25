package com.tduck.cloud.api.demand;

import java.util.ArrayList;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 需求模块配置；默认关闭，原问卷行为保持。 */
@Data
@Component
@ConfigurationProperties(prefix = "ruima.demand")
public class DemandProperties {
  /** 是否启用需求闭环及管理员建单限制。 */
  private boolean enabled;
  /** 额外管理员由运维配置或管理页面授权，普通登录不自动提权。 */
  private List<Long> adminIds = new ArrayList<>();
  /** 轮次与提醒使用的业务时区。 */
  private String zone = "Asia/Shanghai";
  /** 每日必填提醒任务开关。 */
  private boolean reminderEnabled = true;
  /** 每日必填提醒任务 cron；默认 09:05 执行。 */
  private String reminderCron = "0 5 9 * * *";
}
