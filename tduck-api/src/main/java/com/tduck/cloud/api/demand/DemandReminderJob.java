package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 每日必填提醒：强提醒每日一次，默认只在截止前 1 天与当天提醒。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemandReminderJob {
  private final DemandProperties properties;
  private final DemandStore store;
  private final DemandRounds rounds;
  private final DemandRequired required;
  private final DemandNotices notices;

  @Scheduled(
      cron = "${ruima.demand.reminder-cron:0 5 9 * * *}",
      zone = "${ruima.demand.zone:Asia/Shanghai}")
  public void remindDaily() {
    if (!properties.isEnabled() || !properties.isReminderEnabled()) return;
    try {
      int sent = remind(LocalDate.now(rounds.zone()));
      if (sent > 0) log.info("本轮必填提醒已生成 {} 条", sent);
    } catch (Exception error) {
      log.warn("必填提醒任务失败", error);
    }
  }

  /** 按指定日期执行，便于测试。 */
  public int remind(LocalDate today) {
    int sent = 0;
    List<Map<String, Object>> forms =
        store.rows(
            "SELECT c.* FROM rm_demand_form c "
                + "JOIN fm_user_form f ON f.form_key=c.form_key AND f.is_deleted=FALSE "
                + "WHERE c.round_mode<>'NONE' AND c.published=TRUE AND c.voided=FALSE");
    for (Map<String, Object> row : forms) {
      Map<String, Object> form = rounds.advanceIfExpired(row);
      if (DemandRounds.NONE.equals(DemandRounds.str(form.get("roundMode")))) continue;
      Timestamp end = DemandRounds.timestamp(form.get("roundEndTime"));
      if (end == null) continue;
      LocalDate endDate = end.toInstant().atZone(rounds.zone()).toLocalDate();
      if (endDate.isBefore(today)) continue;
      Timestamp start = rounds.roundStart(form);
      if (start == null) continue;
      if (!shouldRemind(DemandRounds.str(form.get("reminderMode")), today, endDate)) continue;
      String formKey = DemandRounds.str(form.get("formKey"));
      Set<Long> ids = required.userIds(formKey, form);
      Set<Long> submitted = required.submittedUserIds(formKey, start);
      Map<String, Object> meta =
          store.one("SELECT name FROM fm_user_form WHERE form_key=?", formKey);
      if (meta == null) continue;
      String formName =
          meta.get("name") == null ? "需求收集" : meta.get("name").toString();
      for (Long userId : ids) {
        if (submitted.contains(userId)) continue;
        notices.requiredReminder(form, userId, formName, end);
        sent++;
      }
    }
    return sent;
  }

  private boolean shouldRemind(String mode, LocalDate today, LocalDate endDate) {
    if ("STRONG".equals(mode)) return true;
    return endDate.minusDays(1).equals(today) || endDate.equals(today);
  }
}
