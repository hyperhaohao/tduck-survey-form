package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.sql.Timestamp;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 收集单必填人：来源保存、人员展开与当前轮提交状态。 */
@Service
@RequiredArgsConstructor
public class DemandRequired {
  public static final String USER = "USER";
  public static final String DEPT = "DEPT";

  private final DemandStore store;
  private final DemandAccess access;
  private final DemandRounds rounds;

  /** 管理侧配置与名单：来源、展开后的必填人及本轮统计。 */
  public Map<String, Object> settings(String key, Long userId) {
    requireConfigure(key, userId);
    Map<String, Object> form = rounds.advanceIfExpired(access.form(key));
    if (form == null) throw new BaseException("收集单尚未配置");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("roundMode", DemandRounds.str(form.get("roundMode")));
    result.put("roundPeriod", DemandRounds.str(form.get("roundPeriod")));
    result.put("roundDay", DemandRounds.intValue(form.get("roundDay")));
    result.put("roundNo", number(form.get("roundNo")));
    result.put("roundStartTime", form.get("roundStartTime"));
    result.put("roundEndTime", form.get("roundEndTime"));
    result.put("roundDeadline", rounds.roundDeadline(form));
    result.put("reminderMode", DemandRounds.normalizeReminder(form.get("reminderMode")));
    result.put("requireMembers", flag(form.get("requireMembers")));
    result.put("subjects", subjects(key));
    List<Map<String, Object>> members = members(key, form);
    Timestamp start = rounds.roundStart(form);
    long submitted = 0;
    for (Map<String, Object> member : members) if (flag(member.get("submitted"))) submitted++;
    long anonymous =
        start == null
            ? 0
            : store.count(
                "SELECT COUNT(*) FROM rm_demand WHERE form_key=? AND anonymous=TRUE AND create_time>=?",
                key,
                start);
    result.put("members", members);
    result.put(
        "stats",
        body(
            "total", members.size(),
            "submitted", submitted,
            "pending", members.size() - submitted,
            "anonymous", anonymous,
            "roundStarted", start != null));
    return result;
  }

  /** 兼容旧必填接口：整体替换 ACL required 标志并双写旧表；只传 requireMembers 时仅更新批量规则。 */
  @Transactional
  public void save(String key, Map<String, Object> body, Long userId) {
    requireConfigure(key, userId);
    Map<String, Object> form =
        store.one("SELECT * FROM rm_demand_form WHERE form_key=? FOR UPDATE", key);
    if (form == null) throw new BaseException("收集单尚未配置");
    if (body.containsKey("subjects")) {
      List<Map<String, String>> subjects = subjects(body.get("subjects"));
      store.update("UPDATE rm_demand_acl SET required=FALSE WHERE form_key=?", key);
      store.update("UPDATE rm_demand_acl_dept SET required=FALSE WHERE form_key=?", key);
      store.update("DELETE FROM rm_demand_required WHERE form_key=?", key);
      for (Map<String, String> subject : subjects) {
        if (USER.equals(subject.get("type"))) {
          Long memberId = number(subject.get("id"));
          if (memberId == null) continue;
          int updated =
              store.update(
                  "UPDATE rm_demand_acl SET required=TRUE, can_fill=TRUE WHERE form_key=? AND user_id=?",
                  key,
                  memberId);
          if (updated == 0)
            store.update(
                "INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle,can_manage,required) VALUES(?,?,TRUE,FALSE,FALSE,TRUE)",
                key,
                memberId);
          store.update(
              "INSERT INTO rm_demand_required(form_key,subject_type,subject_id,created_by) VALUES(?,?,?,?)",
              key,
              USER,
              String.valueOf(memberId),
              userId);
        } else {
          String deptKey = subject.get("id");
          int updated =
              store.update(
                  "UPDATE rm_demand_acl_dept SET required=TRUE, can_fill=TRUE WHERE form_key=? AND dept_key=?",
                  key,
                  deptKey);
          if (updated == 0)
            store.update(
                "INSERT INTO rm_demand_acl_dept(form_key,dept_key,can_fill,can_handle,can_manage,created_by,required) VALUES(?,?,TRUE,FALSE,FALSE,?,TRUE)",
                key,
                deptKey,
                userId);
          store.update(
              "INSERT INTO rm_demand_required(form_key,subject_type,subject_id,created_by) VALUES(?,?,?,?)",
              key,
              DEPT,
              deptKey,
              userId);
        }
      }
    }
    if (body.containsKey("requireMembers"))
      store.update(
          "UPDATE rm_demand_form SET require_members=? WHERE form_key=?",
          flag(body.get("requireMembers")),
          key);
  }

  /** 首页/填写页使用的本人必填状态。 */
  public Map<String, Object> employeeState(String key, Long userId) {
    if (userId == null || !access.enabled()) return body("required", false, "submitted", false);
    return employeeState(key, userId, access.form(key));
  }

  /** 已知表单配置时复用，避免重复读取。 */
  public Map<String, Object> employeeState(
      String key, Long userId, Map<String, Object> form) {
    Map<String, Object> result = body("required", false, "submitted", false);
    if (userId == null || form == null) return result;
    if (DemandRounds.NONE.equals(DemandRounds.str(form.get("roundMode")))) return result;
    form = rounds.advanceIfExpired(form);
    boolean required = required(key, userId, form);
    result.put("required", required);
    if (required) result.put("submitted", submitted(key, userId, rounds.roundStart(form)));
    return result;
  }

  /** 有效必填人 id 集合；用于轮次提醒与提交校验。 */
  public Set<Long> userIds(String key, Map<String, Object> form) {
    Set<Long> ids = new LinkedHashSet<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT a.user_id FROM rm_demand_acl a JOIN ac_user u ON u.id=a.user_id AND u.deleted=FALSE WHERE a.form_key=? AND a.required=TRUE",
            key))
      add(ids, row.get("userId"));
    for (Map<String, Object> row :
        store.rows(
            "SELECT DISTINCT ud.user_id FROM rm_demand_acl_dept ad "
                + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
                + "JOIN rm_demand_dept pd ON pd.active=TRUE "
                + "JOIN rm_demand_user_dept ud ON ud.dept_key=pd.dept_key "
                + "JOIN ac_user u ON u.id=ud.user_id AND u.deleted=FALSE "
                + "WHERE ad.form_key=? AND ad.required=TRUE "
                + "AND (pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1)",
            key))
      add(ids, row.get("userId"));
    if (form != null && flag(form.get("requireMembers")))
      for (Map<String, Object> row :
          store.rows(
              "SELECT a.user_id FROM rm_demand_acl a JOIN ac_user u ON u.id=a.user_id AND u.deleted=FALSE WHERE a.form_key=? AND a.can_fill=TRUE UNION "
                  + "SELECT DISTINCT ud.user_id FROM rm_demand_acl_dept ad "
                  + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
                  + "JOIN rm_demand_dept pd ON pd.active=TRUE "
                  + "JOIN rm_demand_user_dept ud ON ud.dept_key=pd.dept_key "
                  + "JOIN ac_user u ON u.id=ud.user_id AND u.deleted=FALSE "
                  + "WHERE ad.form_key=? AND ad.can_fill=TRUE "
                  + "AND (pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1)",
              key,
              key))
        add(ids, row.get("userId"));
    ids.remove(null);
    return ids;
  }

  /** 当前轮已提交的必填人 id。 */
  public Set<Long> submittedUserIds(String key, Timestamp roundStart) {
    Set<Long> ids = new LinkedHashSet<>();
    if (roundStart == null) return ids;
    for (Map<String, Object> row :
        store.rows(
            "SELECT DISTINCT submitter_id FROM rm_demand WHERE form_key=? AND anonymous=FALSE AND submitter_id IS NOT NULL AND create_time>=?",
            key,
            roundStart))
      add(ids, row.get("submitterId"));
    return ids;
  }

  private boolean required(String key, Long userId, Map<String, Object> form) {
    long direct =
        store.count(
            "SELECT COUNT(*) FROM rm_demand_acl a JOIN ac_user u ON u.id=a.user_id AND u.deleted=FALSE WHERE a.form_key=? AND a.required=TRUE AND a.user_id=?",
            key,
            userId);
    if (direct > 0) return true;
    long department =
        store.count(
            "SELECT COUNT(*) FROM rm_demand_acl_dept ad "
                + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
                + "JOIN rm_demand_dept pd ON pd.active=TRUE "
                + "JOIN rm_demand_user_dept ud ON ud.dept_key=pd.dept_key "
                + "WHERE ad.form_key=? AND ad.required=TRUE AND ud.user_id=? "
                + "AND (pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1)",
            key,
            userId);
    if (department > 0) return true;
    return flag(form.get("requireMembers")) && access.effectiveGrants(userId).canFill(key);
  }

  private boolean submitted(String key, Long userId, Timestamp roundStart) {
    if (roundStart == null) return false;
    return store.count(
            "SELECT COUNT(*) FROM rm_demand WHERE form_key=? AND submitter_id=? AND anonymous=FALSE AND create_time>=?",
            key,
            userId,
            roundStart)
        > 0;
  }

  /** 展开后的必填人名单；来源取并集，停用账户不参与。 */
  private List<Map<String, Object>> members(String key, Map<String, Object> form) {
    Map<Long, Set<String>> byUser = new LinkedHashMap<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT a.user_id FROM rm_demand_acl a JOIN ac_user u ON u.id=a.user_id AND u.deleted=FALSE WHERE a.form_key=? AND a.required=TRUE",
            key))
      add(byUser, row.get("userId"), "USER");
    for (Map<String, Object> row :
        store.rows(
            "SELECT DISTINCT ud.user_id FROM rm_demand_acl_dept ad "
                + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
                + "JOIN rm_demand_dept pd ON pd.active=TRUE "
                + "JOIN rm_demand_user_dept ud ON ud.dept_key=pd.dept_key "
                + "JOIN ac_user u ON u.id=ud.user_id AND u.deleted=FALSE "
                + "WHERE ad.form_key=? AND ad.required=TRUE "
                + "AND (pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1)",
            key))
      add(byUser, row.get("userId"), "DEPT");
    if (flag(form.get("requireMembers")))
      for (Map<String, Object> row :
          store.rows(
              "SELECT user_id FROM rm_demand_acl WHERE form_key=? AND can_fill=TRUE UNION "
                  + "SELECT DISTINCT ud.user_id FROM rm_demand_acl_dept ad "
                  + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
                  + "JOIN rm_demand_dept pd ON pd.active=TRUE "
                  + "JOIN rm_demand_user_dept ud ON ud.dept_key=pd.dept_key "
                  + "WHERE ad.form_key=? AND ad.can_fill=TRUE "
                  + "AND (pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1)",
              key,
              key))
        add(byUser, row.get("userId"), "MEMBER");
    Timestamp start = rounds.roundStart(form);
    Set<Long> submitted = submittedUserIds(key, start);
    List<Map<String, Object>> list = new ArrayList<>();
    if (byUser.isEmpty()) return list;
    String placeholders = placeholders(byUser.size());
    Object[] ids = byUser.keySet().toArray();
    Map<Long, String> names = new LinkedHashMap<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT id,name FROM ac_user WHERE deleted=FALSE AND id IN (" + placeholders + ")",
            ids))
      names.put(number(row.get("id")), row.get("name") == null ? "用户" : row.get("name").toString());
    Map<Long, String> departments = departments(byUser.keySet());
    for (Map.Entry<Long, Set<String>> entry : byUser.entrySet()) {
      Long id = entry.getKey();
      if (!names.containsKey(id)) continue;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("userId", id);
      row.put("name", names.get(id));
      row.put("departments", departments.getOrDefault(id, ""));
      row.put("sources", new ArrayList<>(entry.getValue()));
      row.put("submitted", submitted.contains(id));
      list.add(row);
    }
    list.sort(
        Comparator.comparing((Map<String, Object> row) -> row.get("name").toString())
            .thenComparing(row -> row.get("userId").toString()));
    return list;
  }

  private Map<Long, String> departments(Collection<Long> ids) {
    Map<Long, String> result = new LinkedHashMap<>();
    if (ids.isEmpty()) return result;
    Object[] args = ids.toArray();
    for (Map<String, Object> row :
        store.rows(
            "SELECT ud.user_id,d.name FROM rm_demand_user_dept ud "
                + "JOIN rm_demand_dept d ON d.dept_key=ud.dept_key AND d.active=TRUE "
                + "WHERE ud.user_id IN ("
                + placeholders(ids.size())
                + ") ORDER BY d.sort_no,d.name",
            args)) {
      Long id = number(row.get("userId"));
      String name = row.get("name") == null ? "" : row.get("name").toString();
      result.merge(id, name, (a, b) -> a.isEmpty() ? b : a + "、" + b);
    }
    return result;
  }

  private List<Map<String, String>> subjects(Object raw) {
    List<Map<String, String>> result = new ArrayList<>();
    if (raw instanceof Collection) {
      Set<String> seen = new HashSet<>();
      for (Object item : (Collection<?>) raw) {
        if (!(item instanceof Map)) throw new BaseException("必填人来源无效");
        Object type = ((Map<?, ?>) item).get("type");
        Object id = ((Map<?, ?>) item).get("id");
        String subjectType = type == null ? "" : type.toString().trim().toUpperCase(Locale.ROOT);
        String subjectId = id == null ? "" : id.toString().trim();
        if (!USER.equals(subjectType) && !DEPT.equals(subjectType))
          throw new BaseException("必填人来源类型无效");
        if (subjectId.isEmpty()) throw new BaseException("必填人来源不能为空");
        if (!seen.add(subjectType + ":" + subjectId)) continue;
        if (result.size() >= 500) throw new BaseException("必填人来源一次最多500项");
        if (USER.equals(subjectType)) {
          Long userId;
          try {
            userId = Long.valueOf(subjectId);
          } catch (NumberFormatException error) {
            throw new BaseException("必填人成员无效");
          }
          if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", userId) == 0)
            throw new BaseException("必填人成员不存在或已停用");
        } else if (store.count(
                "SELECT COUNT(*) FROM rm_demand_dept WHERE dept_key=? AND active=TRUE", subjectId)
            == 0) throw new BaseException("必填人部门不存在或已停用");
        Map<String, String> subject = new LinkedHashMap<>();
        subject.put("type", subjectType);
        subject.put("id", subjectId);
        result.add(subject);
      }
    }
    return result;
  }

  private List<Map<String, Object>> subjects(String key) {
    List<Map<String, Object>> result = new ArrayList<>();
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT user_id AS subject_id FROM rm_demand_acl WHERE form_key=? AND required=TRUE",
            key)) {
      row.put("subjectType", USER);
      rows.add(row);
    }
    for (Map<String, Object> row :
        store.rows(
            "SELECT dept_key AS subject_id FROM rm_demand_acl_dept WHERE form_key=? AND required=TRUE",
            key)) {
      row.put("subjectType", DEPT);
      rows.add(row);
    }
    rows.sort(
        Comparator.comparing((Map<String, Object> row) -> DemandRounds.str(row.get("subjectType")))
            .thenComparing(row -> DemandRounds.str(row.get("subjectId"))));
    for (Map<String, Object> row : rows) {
      String type = DemandRounds.str(row.get("subjectType"));
      String id = DemandRounds.str(row.get("subjectId"));
      Map<String, Object> subject = new LinkedHashMap<>();
      subject.put("type", type);
      subject.put("id", id);
      if (USER.equals(type)) {
        subject.put("name", access.name(number(id)));
      } else {
        Map<String, Object> department =
            store.one("SELECT name FROM rm_demand_dept WHERE dept_key=?", id);
        subject.put(
            "name", department == null ? id : DemandRounds.str(department.get("name")));
      }
      result.add(subject);
    }
    return result;
  }

  private List<String> rowsOf(String sql, Object... args) {
    List<String> values = new ArrayList<>();
    for (Map<String, Object> row : store.rows(sql, args)) {
      Object value = row.values().stream().findFirst().orElse(null);
      if (value != null) values.add(value.toString());
    }
    return values;
  }

  private void add(Map<Long, Set<String>> byUser, Object userId, String source) {
    Long id = number(userId);
    if (id == null) return;
    byUser.computeIfAbsent(id, k -> new LinkedHashSet<>()).add(source);
  }

  private void add(Set<Long> ids, Object userId) {
    Long id = number(userId);
    if (id != null) ids.add(id);
  }

  private String placeholders(int count) {
    StringBuilder builder = new StringBuilder();
    for (int i = 0; i < count; i++) {
      if (i > 0) builder.append(',');
      builder.append('?');
    }
    return builder.toString();
  }

  /** 必填设置与成员权限一致：全局管理员或该收集单单据管理员。 */
  private void requireConfigure(String key, Long userId) {
    access.requireManage(key, userId);
  }

  private static Map<String, Object> body(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
    return map;
  }
}
