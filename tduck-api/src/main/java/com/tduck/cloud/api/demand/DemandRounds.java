package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 收集单轮次：不启用轮次（隐式单轮）、周期轮次的初始化、推进与截止判断。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandRounds {
  public static final String NONE = "NONE";
  public static final String SINGLE = "SINGLE";
  public static final String PERIODIC = "PERIODIC";
  public static final String DAILY = "DAILY";
  public static final String WEEKLY = "WEEKLY";
  public static final String MONTH = "MONTH";
  public static final String QUARTER = "QUARTER";
  public static final String REMINDER_NONE = "NONE";
  public static final String REMINDER_DEFAULT = "DEFAULT";
  public static final String REMINDER_STRONG = "STRONG";

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
      end = plusPeriod(start, str(form.get("roundPeriod")), intValue(form.get("roundDay")));
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

  /** 手工开启下一轮；不启用轮次/单轮回退整单截止，周期按锚点推算。 */
  @Transactional
  public Map<String, Object> next(String formKey, Timestamp endTime) {
    Map<String, Object> form =
        store.one("SELECT * FROM rm_demand_form WHERE form_key=? FOR UPDATE", formKey);
    if (form == null) throw new BaseException("收集单不存在", 404);
    String mode = str(form.get("roundMode"));
    if (NONE.equals(mode)) throw new BaseException("未启用轮次");
    Timestamp start = now();
    Timestamp end = endTime;
    if (end == null && PERIODIC.equals(mode))
      end = plusPeriod(start, str(form.get("roundPeriod")), intValue(form.get("roundDay")));
    if (end == null && SINGLE.equals(mode)) end = timestamp(form.get("endTime"));
    if (end == null) throw new BaseException("请选择本轮截止期限或先设置整单截止时间");
    if (end.getTime() <= start.getTime())
      throw new BaseException("本轮截止期限需晚于当前时间");
    long nextNo = number(form.get("roundNo")) + 1;
    if (SINGLE.equals(mode)) {
      // 不启用轮次只有一个整单截止：手工下一轮更新整单截止并清空轮次截止，避免写掉无效字段。
      store.update(
          "UPDATE rm_demand_form SET round_no=?,round_start_time=?,round_end_time=NULL,end_time=? WHERE form_key=?",
          nextNo,
          start,
          end,
          formKey);
      form.put("endTime", end);
    } else {
      store.update(
          "UPDATE rm_demand_form SET round_no=?,round_start_time=?,round_end_time=? WHERE form_key=?",
          nextNo,
          start,
          end,
          formKey);
      form.put("roundEndTime", end);
    }
    form.put("roundNo", nextNo);
    form.put("roundStartTime", start);
    return form;
  }

  /** 有效轮次截止：SINGLE/不启用轮次使用整单截止，PERIODIC 使用本轮截止。 */
  public Timestamp roundDeadline(Map<String, Object> form) {
    if (form == null) return null;
    String mode = str(form.get("roundMode"));
    if (SINGLE.equals(mode)) {
      Timestamp end = timestamp(form.get("endTime"));
      // 兼容升级前只写了 round_end_time 的历史单轮数据。
      return end != null ? end : timestamp(form.get("roundEndTime"));
    }
    if (PERIODIC.equals(mode)) return timestamp(form.get("roundEndTime"));
    return null;
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

  /** 按周期推进；roundDay 正数从周期开始，负数从周期结束倒数。 */
  public Timestamp plusPeriod(Timestamp start, String period) {
    return plusPeriod(start, period, null);
  }

  public Timestamp plusPeriod(Timestamp start, String period, Integer roundDay) {
    ZoneId zone = zone();
    LocalDateTime time = start.toInstant().atZone(zone).toLocalDateTime();
    LocalDateTime next;
    if (DAILY.equals(period)) next = time.plusDays(1);
    else if (WEEKLY.equals(period)) next = nextWeekday(time, roundDay);
    else if (QUARTER.equals(period))
      next = roundDay == null ? time.plusMonths(3) : nextPeriodAnchor(time, true, roundDay);
    else next = roundDay == null ? time.plusMonths(1) : nextPeriodAnchor(time, false, roundDay);
    return Timestamp.from(next.atZone(zone).toInstant());
  }

  private LocalDateTime nextWeekday(LocalDateTime time, Integer roundDay) {
    int target =
        roundDay == null ? time.getDayOfWeek().getValue() : Math.min(7, Math.max(1, roundDay));
    int delta = (target - time.getDayOfWeek().getValue() + 7) % 7;
    if (delta == 0) delta = 7;
    return time.plusDays(delta);
  }

  /** 月度/季度锚点：先看当前周期，候选不晚于起点则顺延一个周期。 */
  private LocalDateTime nextPeriodAnchor(LocalDateTime time, boolean quarter, int roundDay) {
    LocalDateTime cursor = time;
    for (int i = 0; i < 2; i++) {
      YearMonth month = YearMonth.from(cursor);
      LocalDate start;
      LocalDate end;
      if (quarter) {
        int startMonth = ((month.getMonthValue() - 1) / 3) * 3 + 1;
        YearMonth quarterStart = YearMonth.of(month.getYear(), startMonth);
        start = quarterStart.atDay(1);
        end = quarterStart.plusMonths(3).atDay(1).minusDays(1);
      } else {
        start = month.atDay(1);
        end = month.atEndOfMonth();
      }
      LocalDate anchor = anchorDate(start, end, roundDay);
      LocalDateTime candidate = anchor.atTime(cursor.toLocalTime());
      if (candidate.isAfter(time)) return candidate;
      cursor = end.plusDays(1).atTime(cursor.toLocalTime());
    }
    return cursor;
  }

  private LocalDate anchorDate(LocalDate start, LocalDate end, int roundDay) {
    long span = ChronoUnit.DAYS.between(start, end);
    if (roundDay > 0) return start.plusDays(Math.min(roundDay - 1L, span));
    if (roundDay < 0) return end.plusDays(Math.max(roundDay + 1L, -span));
    return end;
  }

  /** 当前轮次起点；无轮次或未开始返回 null。 */
  public Timestamp roundStart(Map<String, Object> form) {
    return form == null ? null : timestamp(form.get("roundStartTime"));
  }

  /** 提醒方式白名单；旧的小写/未知值统一回落到截止前提醒。 */
  public static String normalizeReminder(Object value) {
    String mode = str(value).trim().toUpperCase(java.util.Locale.ROOT);
    return REMINDER_NONE.equals(mode) || REMINDER_STRONG.equals(mode) ? mode : REMINDER_DEFAULT;
  }

  static String str(Object value) {
    return value == null ? "" : value.toString();
  }

  static Integer intValue(Object value) {
    if (value == null) return null;
    try {
      return Integer.valueOf(String.valueOf(value).trim());
    } catch (NumberFormatException error) {
      return null;
    }
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
