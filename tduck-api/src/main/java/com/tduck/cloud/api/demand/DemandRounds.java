package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 收集单轮次：单轮与周期轮次的初始化、推进与截止判断。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandRounds {
  public static final String NONE = "NONE";
  public static final String SINGLE = "SINGLE";
  public static final String PERIODIC = "PERIODIC";
  public static final String MONTH = "MONTH";
  public static final String QUARTER = "QUARTER";

  private final DemandStore store;
  private final DemandProperties properties;

  /** 当前时区；轮次与提醒统一按业务时区计算。 */
  public ZoneId zone() {
    String zone = properties.getZone();
    try {
      return ZoneId.of(zone == null || zone.trim().isEmpty() ? "Asia/Shanghai" : zone.trim());
    } catch (Exception error) {
      return ZoneId.of("Asia/Shanghai");
    }
  }

  public Timestamp now() {
    return Timestamp.from(java.time.Instant.now());
  }

  public boolean expired(Map<String, Object> form) {
    if (form == null || !PERIODIC.equals(str(form.get("roundMode")))) return false;
    Timestamp end = timestamp(form.get("roundEndTime"));
    return end != null && end.getTime() <= System.currentTimeMillis();
  }

  /** 周期轮次到期时按行锁推进；非周期或未到期直接返回原配置。 */
  @Transactional
  public Map<String, Object> advanceIfExpired(Map<String, Object> form) {
    if (!expired(form)) return form;
    Map<String, Object> updated = advance(String.valueOf(form.get("formKey")));
    return updated == null ? form : updated;
  }

  /** 行锁推进周期轮次；一次可跨过多个已过期周期。 */
  @Transactional
  public Map<String, Object> advance(String formKey) {
    Map<String, Object> form =
        store.one("SELECT * FROM rm_demand_form WHERE form_key=? FOR UPDATE", formKey);
    if (form == null || !PERIODIC.equals(str(form.get("roundMode")))) return form;
    Timestamp end = timestamp(form.get("roundEndTime"));
    if (end == null) return form;
    long nextNo = number(form.get("roundNo"));
    int guard = 0;
    long now = System.currentTimeMillis();
    while (end.getTime() <= now && guard++ < 240) {
      Timestamp start = end;
      end = plusPeriod(start, str(form.get("roundPeriod")));
      nextNo++;
      store.update(
          "UPDATE rm_demand_form SET round_no=?,round_start_time=?,round_end_time=? WHERE form_key=?",
          nextNo,
          start,
          end,
          formKey);
      form.put("roundNo", nextNo);
      form.put("roundStartTime", start);
      form.put("roundEndTime", end);
    }
    return form;
  }

  /** 手工开启下一轮：单轮与周期均可用，周期未传截止时间时按周期推算。 */
  @Transactional
  public Map<String, Object> next(String formKey, Timestamp endTime) {
    Map<String, Object> form =
        store.one("SELECT * FROM rm_demand_form WHERE form_key=? FOR UPDATE", formKey);
    if (form == null) throw new BaseException("收集单不存在", 404);
    String mode = str(form.get("roundMode"));
    if (NONE.equals(mode)) throw new BaseException("未启用轮次");
    Timestamp start = now();
    Timestamp end = endTime;
    if (end == null && PERIODIC.equals(mode)) end = plusPeriod(start, str(form.get("roundPeriod")));
    if (end == null) throw new BaseException("请选择本轮截止期限");
    if (end.getTime() <= start.getTime())
      throw new BaseException("本轮截止期限需晚于当前时间");
    long nextNo = number(form.get("roundNo")) + 1;
    store.update(
        "UPDATE rm_demand_form SET round_no=?,round_start_time=?,round_end_time=? WHERE form_key=?",
        nextNo,
        start,
        end,
        formKey);
    form.put("roundNo", nextNo);
    form.put("roundStartTime", start);
    form.put("roundEndTime", end);
    return form;
  }

  /** 解析前端提交的本地日期时间，按业务时区换算；兼容 ISO 与空格分隔。 */
  public Timestamp parse(Object value) {
    if (value == null) return null;
    if (value instanceof Timestamp || value instanceof java.util.Date) return timestamp(value);
    String text = value.toString().trim();
    if (text.isEmpty() || "null".equalsIgnoreCase(text)) return null;
    try {
      String normalized = text.replace(' ', 'T');
      if (normalized.length() == 16) normalized = normalized + ":00";
      if (normalized.length() > 19) normalized = normalized.substring(0, 19);
      return Timestamp.from(LocalDateTime.parse(normalized).atZone(zone()).toInstant());
    } catch (Exception error) {
      return null;
    }
  }

  /** 按自然月或自然季度推进一个周期。 */
  public Timestamp plusPeriod(Timestamp start, String period) {
    ZoneId zone = zone();
    LocalDateTime time = start.toInstant().atZone(zone).toLocalDateTime();
    LocalDateTime next = QUARTER.equals(period) ? time.plusMonths(3) : time.plusMonths(1);
    return Timestamp.from(next.atZone(zone).toInstant());
  }

  /** 当前轮次起点；无轮次或未开始返回 null。 */
  public Timestamp roundStart(Map<String, Object> form) {
    return form == null ? null : timestamp(form.get("roundStartTime"));
  }

  static String str(Object value) {
    return value == null ? "" : value.toString();
  }

  static Timestamp timestamp(Object value) {
    if (value == null) return null;
    if (value instanceof Timestamp) return (Timestamp) value;
    if (value instanceof java.util.Date) return new Timestamp(((java.util.Date) value).getTime());
    String text = value.toString().trim();
    if (text.isEmpty() || "null".equalsIgnoreCase(text)) return null;
    try {
      return Timestamp.valueOf(text.replace('T', ' ').substring(0, Math.min(19, text.length())));
    } catch (Exception error) {
      return null;
    }
  }
}
