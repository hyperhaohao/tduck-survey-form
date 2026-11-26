package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 钉钉推送非敏感开关；30 秒内存快照，保存后立即失效。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandPushSettings {
  public static final String WORK = "WORK";
  public static final String TODO = "TODO";

  private static final Map<String, String> DEFAULTS = new LinkedHashMap<>();

  static {
    DEFAULTS.put("enabled", "false");
    DEFAULTS.put("event.APPROVAL.todo", "true");
    DEFAULTS.put("event.APPROVAL.work", "true");
    DEFAULTS.put("event.REQUIRED.todo", "true");
    DEFAULTS.put("event.REQUIRED.work", "true");
    DEFAULTS.put("event.APPROVAL_RESULT.work", "true");
    DEFAULTS.put("event.REPLY.work", "true");
    DEFAULTS.put("event.RESULT.work", "true");
    DEFAULTS.put("event.ASSIGN.work", "false");
    DEFAULTS.put("event.FOLLOWUP.work", "false");
    DEFAULTS.put("event.NEW_DEMAND.work", "false");
    DEFAULTS.put("event.PUBLICATION.work", "false");
    DEFAULTS.put("quiet.start", "20:00");
    DEFAULTS.put("quiet.end", "08:00");
    DEFAULTS.put("quiet.bypass.APPROVAL", "true");
  }

  private final DemandStore store;
  private final DemandProperties properties;
  private final DemandDingTalkProperties dingTalk;

  private volatile Map<String, String> cache;
  private volatile long loadedAt;

  public Map<String, String> all() {
    long now = System.currentTimeMillis();
    Map<String, String> current = cache;
    if (current != null && now - loadedAt < 30_000) return current;
    Map<String, String> values = new LinkedHashMap<>(DEFAULTS);
    try {
      for (Map<String, Object> row :
          store.rows("SELECT setting_key,setting_value FROM rm_demand_push_setting"))
        values.put(str(row.get("settingKey")), str(row.get("settingValue")));
    } catch (DataAccessException error) {
      log.debug("钉钉推送设置表尚未就绪，使用默认值");
    }
    cache = Collections.unmodifiableMap(values);
    loadedAt = now;
    return cache;
  }

  public String get(String key, String fallback) {
    return all().getOrDefault(key, fallback);
  }

  public boolean flag(String key, boolean fallback) {
    String value = all().get(key);
    return value == null ? fallback : Boolean.parseBoolean(value);
  }

  /** 总开关 + 凭证 + 链接是否就绪。 */
  public boolean enabled() {
    if (!dingTalk.isEnabled() || !flag("enabled", false)) return false;
    if (blank(dingTalk.getAppKey()) || blank(dingTalk.getAppSecret())
        || dingTalk.getAgentId() <= 0 || blank(linkBase())) return false;
    return true;
  }

  public boolean channel(String kind, String channel) {
    if (TODO.equals(channel) && !dingTalk.isTodoEnabled()) return false;
    return flag("event." + kind + "." + channel.toLowerCase(Locale.ROOT), false);
  }

  public String linkBase() {
    String value = all().get("link.base");
    if (!blank(value)) return trimSlash(value);
    return trimSlash(dingTalk.getLinkBase());
  }

  public boolean quietActive(LocalDateTime now) {
    String start = get("quiet.start", "20:00");
    String end = get("quiet.end", "08:00");
    try {
      LocalTime startTime = LocalTime.parse(start.length() == 5 ? start : "20:00");
      LocalTime endTime = LocalTime.parse(end.length() == 5 ? end : "08:00");
      LocalTime current = now.toLocalTime();
      if (startTime.equals(endTime)) return false;
      if (startTime.isBefore(endTime))
        return !current.isBefore(startTime) && current.isBefore(endTime);
      return !current.isBefore(startTime) || current.isBefore(endTime);
    } catch (Exception error) {
      return false;
    }
  }

  public LocalDateTime nextAllowed(LocalDateTime now, String kind) {
    if (flag("quiet.bypass." + kind, "APPROVAL".equals(kind))) return now;
    if (!quietActive(now)) return now;
    String end = get("quiet.end", "08:00");
    try {
      LocalTime endTime = LocalTime.parse(end.length() == 5 ? end : "08:00");
      LocalDateTime candidate = now.toLocalDate().atTime(endTime);
      if (!candidate.isAfter(now)) candidate = candidate.plusDays(1);
      return candidate;
    } catch (Exception error) {
      return now;
    }
  }

  public ZoneId zone() {
    try {
      String value = properties.getZone();
      return ZoneId.of(blank(value) ? "Asia/Shanghai" : value);
    } catch (Exception error) {
      return ZoneId.of("Asia/Shanghai");
    }
  }

  public void save(Map<String, Object> body, Long operator) {
    for (Map.Entry<String, Object> entry : body.entrySet()) {
      String key = entry.getKey();
      if (key == null || !allowedKey(key)) continue;
      String value = entry.getValue() == null ? "" : entry.getValue().toString();
      if (value.length() > 500) throw new IllegalArgumentException("设置值过长");
      int updated =
          store.update(
              "UPDATE rm_demand_push_setting SET setting_value=?,updated_by=?,update_time=CURRENT_TIMESTAMP WHERE setting_key=?",
              value,
              operator,
              key);
      if (updated == 0)
        store.update(
            "INSERT INTO rm_demand_push_setting(setting_key,setting_value,updated_by) VALUES(?,?,?)",
            key,
            value,
            operator);
    }
    cache = null;
    loadedAt = 0;
  }

  private boolean allowedKey(String key) {
    return key.equals("enabled")
        || key.equals("link.base")
        || key.equals("quiet.start")
        || key.equals("quiet.end")
        || key.equals("quiet.bypass.APPROVAL")
        || key.startsWith("event.");
  }

  private static String trimSlash(String value) {
    if (value == null) return "";
    String result = value.trim();
    while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
    return result;
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }
}
