package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.sql.Timestamp;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 收集单发布范围、经办授权和管理员授权管理。 */
@Service
@RequiredArgsConstructor
public class DemandForms {
  private final DemandStore store;
  private final DemandAccess access;
  private final DemandPermissions permissions;
  private final DemandRounds rounds;
  private final DemandRequired required;
  private final DemandNotices notices;
  /** 查询当前身份可见的收集单，管理查询不公开名单。 */
  public List<Map<String, Object>> list(Long userId, boolean management) {
    access.requireEnabled();
    if (management) access.requireDocumentManager(userId);
    boolean global = access.globalAdmin(userId);
    StringBuilder sql =
        new StringBuilder(
            "SELECT f.form_key,f.name,f.description,f.status,c.summary,c.visibility,c.collect_only,c.allow_anonymous,c.published,c.end_time,c.voided,c.revision,c.sort_no,c.round_mode,c.round_period,c.round_day,c.round_no,c.round_start_time,c.round_end_time,c.reminder_mode,c.require_members,c.requires_leader_approval,c.approval_mode,(SELECT COUNT(*) FROM rm_demand d WHERE d.form_key=f.form_key) AS demand_count FROM fm_user_form f "
                + (management ? "LEFT" : "INNER")
                + " JOIN rm_demand_form c ON c.form_key=f.form_key WHERE f.is_deleted=FALSE AND (f.is_folder=FALSE OR f.is_folder IS NULL)");
    List<Object> args = new ArrayList<>();
    if (management && !global) {
      sql.append(" AND (f.user_id=?");
      args.add(userId);
      Set<String> managed = access.effectiveGrants(userId).manageForms();
      if (!managed.isEmpty()) {
        sql.append(" OR f.form_key IN (");
        boolean first = true;
        for (String key : managed) {
          if (!first) sql.append(',');
          sql.append('?');
          args.add(key);
          first = false;
        }
        sql.append(')');
      }
      sql.append(")");
    }
    sql.append(
        " ORDER BY CASE WHEN c.sort_no IS NULL THEN 1 ELSE 0 END,c.sort_no,f.create_time DESC,f.form_key");
    List<Map<String, Object>> rows = store.rows(sql.toString(), args.toArray());
    rows.removeIf(
        row ->
            !management
                && (flag(row.get("voided"))
                    || !flag(row.get("published"))
                    || !"2".equals(String.valueOf(row.get("status")))
                    || (userId == null
                        ? !(flag(row.get("allowAnonymous"))
                            && "LOGIN".equals(row.get("visibility")))
                        : !access.visible(row, userId))));
    DemandGrants grants = access.effectiveGrants(userId);
    boolean globalAdmin = access.globalAdmin(userId);
    for (Map<String, Object> row : rows) {
      String key = String.valueOf(row.get("formKey"));
      boolean login = "LOGIN".equals(String.valueOf(row.get("visibility")));
      row.put("collectOnly", flag(row.get("collectOnly")));
      String approvalMode = modeOf(row);
      row.put("approvalMode", approvalMode);
      row.put("requiresLeaderApproval", !DemandApproval.NONE.equals(approvalMode));
      row.put("canFill", userId != null && (login || grants.canFill(key)));
      row.put("canHandle", userId != null && (globalAdmin || grants.canHandle(key)));
      row.put("canManage", userId != null && (globalAdmin || grants.canManage(key)));
      Map<String, Object> rounded = rounds.advanceIfExpired(row);
      copyRound(row, rounded);
      Map<String, Object> state = required.employeeState(key, userId, rounded);
      row.put("requiredOfMe", state.get("required"));
      row.put("requiredSubmitted", state.get("submitted"));
    }
    return rows;
  }
  /** 元数据不泄露授权名单，允许经办人在后台查看停用的收集单。 */
  public Map<String, Object> info(String key, Long userId) {
    if (!access.enabled()) return Collections.singletonMap("managed", false);
    Map<String, Object> form = access.form(key);
    if (form == null) return Collections.singletonMap("managed", false);
    form = rounds.advanceIfExpired(form);
    String approvalMode = modeOf(form);
    form.put("approvalMode", approvalMode);
    form.put("requiresLeaderApproval", !DemandApproval.NONE.equals(approvalMode));
    boolean deleted =
        store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=FALSE", key)
            == 0;
    if (userId == null) {
      Map<String, Object> gate = new LinkedHashMap<>();
      gate.put("managed", true);
      gate.put("deleted", deleted);
      gate.put("voided", deleted || flag(form.get("voided")));
      gate.put(
          "allowAnonymous",
          !deleted
              && !flag(form.get("voided"))
              && flag(form.get("published"))
              && flag(form.get("allowAnonymous"))
              && "LOGIN".equals(form.get("visibility")));
      gate.put("collectOnly", !deleted && flag(form.get("collectOnly")));
      gate.put("approvalMode", approvalMode);
      gate.put("requiresLeaderApproval", !DemandApproval.NONE.equals(approvalMode));
      gate.put("roundMode", DemandRounds.str(form.get("roundMode")));
      gate.put("roundDay", form.get("roundDay"));
      gate.put("roundEndTime", form.get("roundEndTime"));
      gate.put("roundDeadline", rounds.roundDeadline(form));
      return gate;
    }
    if (deleted) {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("managed", true);
      result.put("deleted", true);
      result.put("voided", true);
      result.put("allowAnonymous", false);
      return result;
    }
    if (!access.table(key, userId)) access.requireFill(key, userId, false);
    Map<String, Object> result = new LinkedHashMap<>(form);
    result.put("managed", true);
    result.put("roundDeadline", rounds.roundDeadline(form));
    Map<String, Object> state = required.employeeState(key, userId, form);
    result.put("requiredOfMe", state.get("required"));
    result.put("requiredSubmitted", state.get("submitted"));
    return result;
  }
  /** 设置独立业务收集配置，不能将指定人员收集单同时开放匿名。 */
  @Transactional
  public void configure(String key, Map<String, Object> body, Long userId) {
    if (store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=FALSE", key)
        == 0) throw new BaseException("表单不存在");
    if (!access.globalAdmin(userId) && !access.manage(key, userId) && !access.owner(key, userId))
      throw new BaseException("仅该收集单的创建人或单据管理员可执行此操作", 403);
    rounds.advanceIfExpired(access.form(key));
    Map<String, Object> old =
        store.one("SELECT * FROM rm_demand_form WHERE form_key=? FOR UPDATE", key);
    String visibility = String.valueOf(body.getOrDefault("visibility", "LOGIN"));
    if (!Arrays.asList("LOGIN", "SELECTED").contains(visibility)) throw new BaseException("填报范围无效");
    boolean anonymous = flag(body.get("allowAnonymous"));
    String approvalMode;
    if (body.containsKey("approvalMode")) {
      approvalMode = normalizeMode(body.get("approvalMode"));
    } else if (body.containsKey("requiresLeaderApproval")) {
      boolean requested = flag(body.get("requiresLeaderApproval"));
      String current = old == null ? DemandApproval.NONE : modeOf(old);
      approvalMode =
          requested
              ? (DemandApproval.HIERARCHY.equals(current)
                  ? DemandApproval.HIERARCHY
                  : DemandApproval.DIRECT)
              : DemandApproval.NONE;
    } else {
      approvalMode = old == null ? DemandApproval.NONE : modeOf(old);
    }
    boolean requiresLeaderApproval = !DemandApproval.NONE.equals(approvalMode);
    if (requiresLeaderApproval) anonymous = false;
    if (anonymous && !"LOGIN".equals(visibility)) throw new BaseException("指定人员收集单不能开放匿名填写");
    String summary = body.get("summary") == null ? "" : body.get("summary").toString();
    if (summary.length() > 2000) throw new BaseException("说明不能超过2000字");
    Object end = body.get("endTime");
    java.sql.Timestamp endTime = null;
    if (body.containsKey("endTime")) {
      if (end != null && !end.toString().trim().isEmpty()) {
        try {
          endTime = java.sql.Timestamp.valueOf(end.toString().trim());
        } catch (Exception error) {
          throw new BaseException("整单截止时间格式无效");
        }
      }
    } else if (old != null) {
      endTime = DemandRounds.timestamp(old.get("endTime"));
    }
    if (endTime == null && old != null) {
      String oldRoundMode =
          DemandRounds.str(old.get("roundMode")).trim().toUpperCase(Locale.ROOT);
      // 仅历史单轮把 round_end_time 当作整单截止；周期轮次的本轮截止不能升级为整单截止。
      if (oldRoundMode.isEmpty()
          || DemandRounds.NONE.equals(oldRoundMode)
          || DemandRounds.SINGLE.equals(oldRoundMode))
        endTime = DemandRounds.timestamp(old.get("roundEndTime"));
    }
    boolean collectOnly =
        body.containsKey("collectOnly")
            ? flag(body.get("collectOnly"))
            : old != null && flag(old.get("collectOnly"));
    String oldModeRaw =
        old == null ? DemandRounds.SINGLE : DemandRounds.str(old.get("roundMode"));
    if (oldModeRaw.isEmpty() || DemandRounds.NONE.equals(oldModeRaw))
      oldModeRaw = DemandRounds.SINGLE;
    String roundMode =
        body.containsKey("roundMode")
            ? DemandRounds.str(body.get("roundMode")).trim().toUpperCase(Locale.ROOT)
            : oldModeRaw;
    if (DemandRounds.NONE.equals(roundMode)) roundMode = DemandRounds.SINGLE;
    if (!Arrays.asList(DemandRounds.SINGLE, DemandRounds.PERIODIC).contains(roundMode))
      throw new BaseException("轮次模式无效");
    String roundPeriod =
        body.containsKey("roundPeriod")
            ? DemandRounds.str(body.get("roundPeriod")).trim().toUpperCase(Locale.ROOT)
            : old == null ? "" : DemandRounds.str(old.get("roundPeriod"));
    if (DemandRounds.PERIODIC.equals(roundMode)) {
      if (!Arrays.asList(
              DemandRounds.DAILY, DemandRounds.WEEKLY, DemandRounds.MONTH, DemandRounds.QUARTER)
          .contains(roundPeriod)) roundPeriod = DemandRounds.MONTH;
    } else roundPeriod = null;
    Integer roundDay =
        body.containsKey("roundDay")
            ? DemandRounds.intValue(body.get("roundDay"))
            : old == null ? null : DemandRounds.intValue(old.get("roundDay"));
    if (DemandRounds.PERIODIC.equals(roundMode)) {
      if (DemandRounds.DAILY.equals(roundPeriod)) roundDay = null;
      else if (DemandRounds.WEEKLY.equals(roundPeriod)
          && roundDay != null
          && (roundDay < 1 || roundDay > 7))
        throw new BaseException("每周锚点需为周一到周日");
      else if (DemandRounds.MONTH.equals(roundPeriod)
          && roundDay != null
          && (roundDay == 0 || Math.abs(roundDay) > 31))
        throw new BaseException("每月锚点需为 1-31 或 -1 到 -31");
      else if (DemandRounds.QUARTER.equals(roundPeriod)
          && roundDay != null
          && (roundDay == 0 || Math.abs(roundDay) > 90))
        throw new BaseException("每季度锚点需为 1-90 或 -1 到 -90");
    } else roundDay = null;
    String reminderMode =
        DemandRounds.normalizeReminder(
            body.containsKey("reminderMode")
                ? body.get("reminderMode")
                : old == null ? null : old.get("reminderMode"));
    boolean requireMembers =
        body.containsKey("requireMembers")
            ? flag(body.get("requireMembers"))
            : old != null && flag(old.get("requireMembers"));
    long roundNo = old == null ? 0 : number(old.get("roundNo"));
    Timestamp roundStart = old == null ? null : DemandRounds.timestamp(old.get("roundStartTime"));
    java.sql.Timestamp roundEnd = null;
    boolean modeChanged = !roundMode.equals(oldModeRaw);
    boolean periodChanged =
        old == null
            || !DemandRounds.str(old.get("roundPeriod")).equals(DemandRounds.str(roundPeriod));
    boolean dayChanged =
        old == null
            || !java.util.Objects.equals(
                DemandRounds.intValue(old.get("roundDay")), roundDay);
    if (DemandRounds.SINGLE.equals(roundMode)) {
      if (modeChanged || roundNo <= 0 || roundStart == null) {
        roundNo = 1;
        roundStart = rounds.now();
      }
      roundEnd = null;
    } else {
      if (modeChanged || periodChanged || dayChanged || roundNo <= 0 || roundStart == null) {
        roundNo = 1;
        roundStart = rounds.now();
      }
      roundEnd =
          old != null && !modeChanged && !periodChanged && !dayChanged
              ? DemandRounds.timestamp(old.get("roundEndTime"))
              : null;
      if (roundEnd == null) roundEnd = rounds.plusPeriod(roundStart, roundPeriod, roundDay);
    }
    if (old == null) {
      Map<String, Object> next =
          store.one("SELECT COALESCE(MIN(sort_no),0)-1 AS next_sort FROM rm_demand_form");
      long sortNo =
          next == null || next.get("nextSort") == null ? 0 : number(next.get("nextSort"));
      store.update(
          "INSERT INTO rm_demand_form(form_key,summary,visibility,allow_anonymous,published,end_time,sort_no,collect_only,round_mode,round_period,round_day,round_no,round_start_time,round_end_time,reminder_mode,require_members,requires_leader_approval,approval_mode) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          key,
          summary,
          visibility,
          anonymous,
          flag(body.get("published")),
          endTime,
          sortNo,
          collectOnly,
          roundMode,
          roundPeriod,
          roundDay,
          roundNo,
          roundStart,
          roundEnd,
          reminderMode,
          requireMembers,
          requiresLeaderApproval,
          approvalMode);
      grantCreatorManager(key);
    } else {
      long revision = Long.parseLong(String.valueOf(body.getOrDefault("revision", -1)));
      if (revision != number(old.get("revision"))) throw new BaseException("配置已变化，请刷新后重试", 409);
      store.update(
          "UPDATE rm_demand_form SET summary=?,visibility=?,allow_anonymous=?,published=?,end_time=?,collect_only=?,round_mode=?,round_period=?,round_day=?,round_no=?,round_start_time=?,round_end_time=?,reminder_mode=?,require_members=?,requires_leader_approval=?,approval_mode=?,revision=revision+1 WHERE form_key=?",
          summary,
          visibility,
          anonymous,
          flag(body.get("published")),
          endTime,
          collectOnly,
          roundMode,
          roundPeriod,
          roundDay,
          roundNo,
          roundStart,
          roundEnd,
          reminderMode,
          requireMembers,
          requiresLeaderApproval,
          approvalMode,
          key);
      if (!access.hasOtherManager(key, null, null)) grantCreatorManager(key);
    }
    // 必填成员必须可填报；保存配置时补齐历史 ACL 行的 can_fill。
    store.update("UPDATE rm_demand_acl SET can_fill=TRUE WHERE form_key=? AND required=TRUE", key);
    store.update(
        "UPDATE rm_demand_acl_dept SET can_fill=TRUE WHERE form_key=? AND required=TRUE", key);
  }
  /** 手工开启下一轮；单轮与周期均可用。 */
  @Transactional
  public void nextRound(String key, Map<String, Object> body, Long userId) {
    if (!access.globalAdmin(userId) && !access.manage(key, userId) && !access.owner(key, userId))
      throw new BaseException("仅该收集单的创建人或单据管理员可执行此操作", 403);
    Object value = body.get("roundEndTime");
    Timestamp end =
        value == null || value.toString().trim().isEmpty()
            ? null
            : rounds.parse(value);
    if (end == null && value != null && !value.toString().trim().isEmpty())
      throw new BaseException("本轮截止期限格式无效");
    rounds.next(key, end);
  }
  /** 将 SQL 行中的轮次字段同步到管理列表行。 */
  private void copyRound(Map<String, Object> row, Map<String, Object> source) {
    if (source == null) return;
    row.put("roundMode", source.get("roundMode"));
    row.put("roundPeriod", source.get("roundPeriod"));
    row.put("roundDay", source.get("roundDay"));
    row.put("roundNo", source.get("roundNo"));
    row.put("roundStartTime", source.get("roundStartTime"));
    row.put("roundEndTime", source.get("roundEndTime"));
    row.put("roundDeadline", rounds.roundDeadline(source));
    row.put("reminderMode", source.get("reminderMode"));
    row.put("requireMembers", source.get("requireMembers"));
  }

  /** 新审批模式优先；旧布尔列在没有模式时回退 DIRECT。 */
  private static String modeOf(Map<String, Object> row) {
    String value =
        row.get("approvalMode") == null
            ? ""
            : row.get("approvalMode").toString().trim().toUpperCase(Locale.ROOT);
    if (DemandApproval.DIRECT.equals(value) || DemandApproval.HIERARCHY.equals(value))
      return value;
    if (DemandApproval.NONE.equals(value) && !flag(row.get("requiresLeaderApproval")))
      return value;
    return flag(row.get("requiresLeaderApproval"))
        ? DemandApproval.DIRECT
        : DemandApproval.NONE;
  }

  private static String normalizeMode(Object value) {
    String mode =
        value == null ? "" : value.toString().trim().toUpperCase(Locale.ROOT);
    if (DemandApproval.NONE.equals(mode)
        || DemandApproval.DIRECT.equals(mode)
        || DemandApproval.HIERARCHY.equals(mode)) return mode;
    throw new BaseException("审批模式无效");
  }
  /** 全局管理员保存首页收集单全局顺序；未出现在请求中的收集单保持原位。 */
  @Transactional
  public void sort(Map<String, Object> body, Long userId) {
    access.requireGlobalAdmin(userId);
    Object raw = body.get("formKeys");
    if (!(raw instanceof List)) throw new BaseException("排序内容无效");
    List<String> requested = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    for (Object value : (List<?>) raw) {
      String key = value == null ? "" : value.toString().trim();
      if (key.isEmpty()) throw new BaseException("排序内容无效");
      if (!seen.add(key)) throw new BaseException("排序内容包含重复收集单");
      requested.add(key);
    }
    if (requested.size() > 500) throw new BaseException("一次最多调整500个收集单");
    List<Map<String, Object>> rows =
        store.rows("SELECT form_key FROM rm_demand_form ORDER BY sort_no,form_key FOR UPDATE");
    List<String> current = new ArrayList<>();
    for (Map<String, Object> row : rows) current.add(String.valueOf(row.get("formKey")));
    if (!new HashSet<>(current).containsAll(requested))
      throw new BaseException("收集单列表已变化，请刷新后重试", 409);
    List<String> merged = new ArrayList<>(current);
    int index = 0;
    for (int i = 0; i < merged.size(); i++) {
      if (seen.contains(merged.get(i))) merged.set(i, requested.get(index++));
    }
    for (int i = 0; i < merged.size(); i++)
      store.update("UPDATE rm_demand_form SET sort_no=? WHERE form_key=?", i + 1, merged.get(i));
  }
  /** 作废后停止新填报并从员工入口隐藏，需求历史保留，可恢复。 */
  @Transactional
  public void setVoided(String key, boolean voided, Long userId) {
    if (!access.globalAdmin(userId) && !access.manage(key, userId) && !access.owner(key, userId))
      throw new BaseException("仅该收集单的创建人或单据管理员可执行此操作", 403);
    int rows =
        voided
            ? store.update(
                "UPDATE rm_demand_form SET voided=TRUE,voided_by=?,voided_at=CURRENT_TIMESTAMP,revision=revision+1 WHERE form_key=?",
                userId,
                key)
            : store.update(
                "UPDATE rm_demand_form SET voided=FALSE,voided_by=NULL,voided_at=NULL,revision=revision+1 WHERE form_key=?",
                key);
    if (rows == 0) throw new BaseException("请先保存需求收集配置");
  }
  /** 全局回收站：已逻辑删除的收集单、需求数量与删除审计。 */
  public List<Map<String, Object>> deletedForms(Long userId) {
    access.requireGlobalAdmin(userId);
    return store.rows(
        "SELECT f.form_key,f.name,f.description,c.summary,c.deleted_at,c.deleted_by,u.name AS deleted_by_name,"
            + "(SELECT COUNT(*) FROM rm_demand d WHERE d.form_key=f.form_key) AS demand_count,"
            + "(SELECT COUNT(*) FROM fm_user_form_data x WHERE x.form_key=f.form_key) AS data_count "
            + "FROM fm_user_form f LEFT JOIN rm_demand_form c ON c.form_key=f.form_key "
            + "LEFT JOIN ac_user u ON u.id=c.deleted_by "
            + "WHERE f.is_deleted=TRUE ORDER BY c.deleted_at DESC, f.update_time DESC");
  }
  /** 逻辑删除收集单到全局回收站；需求、答卷和附件保留。 */
  @Transactional
  public Map<String, Object> deleteCollection(String key, Long userId) {
    if (!access.globalAdmin(userId) && !access.manage(key, userId) && !access.owner(key, userId))
      throw new BaseException("仅该收集单的创建人或单据管理员可执行此操作", 403);
    if (store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key=?", key) == 0)
      throw new BaseException("表单不存在");
    long demands = store.count("SELECT COUNT(*) FROM rm_demand WHERE form_key=?", key);
    long data = store.count("SELECT COUNT(*) FROM fm_user_form_data WHERE form_key=?", key);
    if (store.count(
            "SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=TRUE", key)
        == 0) {
      store.update("UPDATE fm_user_form SET is_deleted=TRUE WHERE form_key=?", key);
      store.update(
          "UPDATE rm_demand_form SET deleted_by=?,deleted_at=CURRENT_TIMESTAMP WHERE form_key=?",
          userId,
          key);
      notices.deleteForForm(key);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("demandCount", demands);
    result.put("dataCount", data);
    return result;
  }
  /** 从全局回收站恢复收集单。 */
  @Transactional
  public void restoreCollection(String key, Long userId) {
    if (!access.globalAdmin(userId) && !access.manage(key, userId) && !access.owner(key, userId))
      throw new BaseException("仅该收集单的创建人或单据管理员可执行此操作", 403);
    if (store.count(
            "SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=TRUE", key)
        == 0) throw new BaseException("表单不在回收站");
    store.update("UPDATE fm_user_form SET is_deleted=FALSE WHERE form_key=?", key);
    store.update(
        "UPDATE rm_demand_form SET deleted_by=NULL,deleted_at=NULL WHERE form_key=?", key);
  }
  /** 彻底删除仅限无需求、无答卷的收集单；事务清理原表单关联表。 */
  @Transactional
  public Map<String, Object> purgeCollection(String key, Long userId) {
    access.requireGlobalAdmin(userId);
    if (store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key=?", key) == 0)
      throw new BaseException("表单不存在");
    long demands = store.count("SELECT COUNT(*) FROM rm_demand WHERE form_key=?", key);
    long data = store.count("SELECT COUNT(*) FROM fm_user_form_data WHERE form_key=?", key);
    if (demands > 0 || data > 0)
      throw new BaseException("该收集单已有需求或答卷，不能彻底删除；请保留在回收站或先作废", 409);
    store.update(
        "DELETE FROM rm_demand_approval_approver WHERE step_id IN (SELECT id FROM rm_demand_approval_step WHERE demand_id IN (SELECT id FROM rm_demand WHERE form_key=?))",
        key);
    store.update(
        "DELETE FROM rm_demand_approval_step WHERE demand_id IN (SELECT id FROM rm_demand WHERE form_key=?)",
        key);
    for (String table :
        Arrays.asList(
            "fm_user_form_item",
            "fm_user_form_data",
            "fm_user_form_setting",
            "fm_user_form_theme",
            "fm_user_form_logic",
            "fm_user_form_auth",
            "rm_demand_acl",
            "rm_demand_acl_dept",
            "rm_demand_required",
            "rm_demand_notice",
            "rm_demand_form",
            "rm_demand",
            "fm_user_form")) {
      store.update("DELETE FROM " + table + " WHERE form_key=?", key);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("demandCount", demands);
    result.put("dataCount", data);
    result.put("purged", true);
    return result;
  }
  /** 创建人默认成为单据管理员，已有权限行只补管理标记。 */
  private void grantCreatorManager(String key) {
    Map<String, Object> form =
        store.one("SELECT user_id FROM fm_user_form WHERE form_key=? AND is_deleted=FALSE", key);
    Long creator = form == null ? null : number(form.get("userId"));
    if (creator == null
        || store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", creator) == 0)
      return;
    if (store.update(
            "UPDATE rm_demand_acl SET can_manage=TRUE WHERE form_key=? AND user_id=?", key, creator)
        == 0)
      store.update(
          "INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle,can_manage) VALUES(?,?,FALSE,FALSE,TRUE)",
          key,
          creator);
  }
  /** 查看具名权限。 */
  public List<Map<String, Object>> members(String key, Long userId) {
    access.requireManage(key, userId);
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT a.user_id,u.name,a.can_fill,a.can_handle,a.can_manage,a.required FROM rm_demand_acl a JOIN ac_user u ON u.id=a.user_id WHERE a.form_key=? ORDER BY u.name,a.user_id",
            key);
    normalizeMemberFlags(rows);
    return rows;
  }
  /** 数据源关闭 tinyInt1isBit 时布尔列返回 0/1，接口统一输出布尔值。 */
  static void normalizeMemberFlags(List<Map<String, Object>> rows) {
    for (Map<String, Object> row : rows) {
      row.put("canFill", flag(row.get("canFill")));
      row.put("canHandle", flag(row.get("canHandle")));
      row.put("canManage", flag(row.get("canManage")));
      row.put("required", flag(row.get("required")));
    }
  }
  /** 单据管理员读取可分派的经办人，不暴露完整成员名单。 */
  public List<Map<String, Object>> handlers(String key, Long userId) {
    if (userId == null) throw new BaseException("请先登录", 401);
    access.requireManage(key, userId);
    return store.rows(
        "SELECT DISTINCT u.id AS user_id,u.name FROM ac_user u WHERE u.deleted=FALSE AND ("
            + "EXISTS(SELECT 1 FROM rm_demand_acl a WHERE a.form_key=? AND a.user_id=u.id AND a.can_handle=TRUE) "
            + "OR EXISTS(SELECT 1 FROM rm_demand_acl_dept ad "
            + "JOIN rm_demand_user_dept ud ON ud.user_id=u.id "
            + "JOIN rm_demand_dept pd ON pd.dept_key=ud.dept_key AND pd.active=TRUE "
            + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
            + "WHERE ad.form_key=? AND ad.can_handle=TRUE AND (pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1))) "
            + "ORDER BY u.name",
        key,
        key);
  }
  /** 分别授权填报、经办与管理能力，撤权立即影响后续访问及待办。 */
  @Transactional
  public void member(String key, Long memberId, Map<String, Object> body, Long userId) {
    access.requireManage(key, userId);
    Map<String, Object> form =
        store.one("SELECT form_key,visibility FROM rm_demand_form WHERE form_key=? FOR UPDATE", key);
    if (form == null) throw new BaseException("请先保存需求收集配置");
    if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", memberId) == 0)
      throw new BaseException("用户不存在或已停用");
    Map<String, Object> old =
        store.one(
            "SELECT can_fill,can_handle,can_manage,required FROM rm_demand_acl WHERE form_key=? AND user_id=?",
            key,
            memberId);
    boolean canManage =
        body.containsKey("canManage")
            ? flag(body.get("canManage"))
            : old != null && flag(old.get("canManage"));
    if (old != null
        && flag(old.get("canManage"))
        && !canManage
        && !access.hasOtherManager(key, memberId, null))
      throw new BaseException("每个收集单至少保留一名单据管理员");
    boolean required =
        body.containsKey("required")
            ? flag(body.get("required"))
            : old != null && flag(old.get("required"));
    boolean canFill =
        body.containsKey("canFill")
            ? flag(body.get("canFill"))
            : old != null && flag(old.get("canFill"));
    boolean canHandle =
        body.containsKey("canHandle")
            ? flag(body.get("canHandle"))
            : old != null && flag(old.get("canHandle"));
    if (required) canFill = true;
    store.update("DELETE FROM rm_demand_acl WHERE form_key=? AND user_id=?", key, memberId);
    store.update(
        "INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle,can_manage,required) VALUES(?,?,?,?,?,?)",
        key,
        memberId,
        canFill,
        canHandle,
        canManage,
        required);
    syncLegacyUserRequired(key, memberId, required, userId);
  }

  /** 过渡期双写旧必填表，保证旧后端/旧前端回滚后名单仍可用。 */
  private void syncLegacyUserRequired(
      String key, Long memberId, boolean required, Long operator) {
    String subjectId = String.valueOf(memberId);
    if (required) {
      if (store.count(
              "SELECT COUNT(*) FROM rm_demand_required WHERE form_key=? AND subject_type='USER' AND subject_id=?",
              key,
              subjectId)
          == 0)
        store.update(
            "INSERT INTO rm_demand_required(form_key,subject_type,subject_id,created_by) VALUES(?,?,?,?)",
            key,
            "USER",
            subjectId,
            operator);
    } else {
      store.update(
          "DELETE FROM rm_demand_required WHERE form_key=? AND subject_type='USER' AND subject_id=?",
          key,
          subjectId);
    }
  }
  /** 移除成员授权；最后一名单据管理员不可移除。 */
  @Transactional
  public void removeMember(String key, Long memberId, Long userId) {
    access.requireManage(key, userId);
    if (store.one("SELECT form_key FROM rm_demand_form WHERE form_key=? FOR UPDATE", key) == null)
      throw new BaseException("请先保存需求收集配置");
    Map<String, Object> old =
        store.one(
            "SELECT can_manage FROM rm_demand_acl WHERE form_key=? AND user_id=?", key, memberId);
    if (old == null) return;
    if (flag(old.get("canManage")) && !access.hasOtherManager(key, memberId, null))
      throw new BaseException("每个收集单至少保留一名有效单据管理员");
    store.update("DELETE FROM rm_demand_acl WHERE form_key=? AND user_id=?", key, memberId);
    store.update(
        "DELETE FROM rm_demand_required WHERE form_key=? AND subject_type='USER' AND subject_id=?",
        key,
        String.valueOf(memberId));
  }
  /** 管理页面仅返回必要的账户标识和姓名。 */
  public List<Map<String, Object>> users(String keyword, Long userId) {
    access.requireDocumentManager(userId);
    String q = keyword == null ? "" : keyword;
    return store.rows(
        "SELECT id,name FROM ac_user WHERE deleted=FALSE AND (name LIKE ? OR CAST(id AS CHAR) LIKE ?) ORDER BY name LIMIT 200",
        "%" + q + "%",
        "%" + q + "%");
  }
  /** 旧接口兼容：列出拥有单据管理能力的用户，不返回认证信息。 */
  public List<Map<String, Object>> admins(Long userId) {
    access.requireGlobalAdmin(userId);
    List<Map<String, Object>> result = new ArrayList<>();
    for (Map<String, Object> row : permissions.users()) {
      Object raw = row.get("capabilities");
      if (raw instanceof List && ((List<?>) raw).contains(DemandPermissions.FORM_MANAGE)) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("userId", row.get("userId"));
        item.put("name", row.get("name"));
        result.add(item);
      }
    }
    return result;
  }
  /** 旧接口兼容：授予默认单据管理能力，或撤销全部平台能力。 */
  @Transactional
  public void admin(Long memberId, boolean grant, Long userId) {
    access.requireGlobalAdmin(userId);
    if (memberId.equals(userId) && !grant) throw new BaseException("不能在当前会话撤销自己的管理权限");
    if (grant) permissions.grantDefaults(memberId, userId);
    else permissions.revokeAll(memberId, userId);
  }
  /** 平台权限矩阵：能力定义、全局专属说明与已授权用户。 */
  public Map<String, Object> permissionMatrix(Long userId) {
    access.requireGlobalAdmin(userId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("capabilities", DemandPermissions.definitions());
    result.put("globalOnly", DemandPermissions.globalOnly());
    result.put("users", permissions.users());
    return result;
  }
  /** 整体保存用户平台能力；空数组表示撤销。 */
  @Transactional
  public void savePermissions(Long memberId, Map<String, Object> body, Long userId) {
    access.requireGlobalAdmin(userId);
    Object raw = body.get("capabilities");
    if (!(raw instanceof List)) throw new BaseException("能力清单无效");
    List<String> capabilities = new ArrayList<>();
    for (Object value : (List<?>) raw) capabilities.add(value == null ? "" : value.toString());
    permissions.replace(memberId, capabilities, userId);
  }
}
