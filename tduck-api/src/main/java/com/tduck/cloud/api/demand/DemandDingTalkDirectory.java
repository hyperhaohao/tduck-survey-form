package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.sql.Timestamp;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 钉钉通讯录镜像与本地账户映射；只按邮箱精确匹配，不按姓名自动绑定。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandDingTalkDirectory {
  public static final String UNMATCHED = "UNMATCHED";
  public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
  public static final String CONFIRMED = "CONFIRMED";
  public static final String CONFLICT = "CONFLICT";
  public static final String INVALID = "INVALID";

  private final DemandStore store;
  private final DemandDingTalkClient client;
  private final DemandDingTalkProperties properties;

  /** 同步钉钉通讯录并尝试按邮箱匹配；已确认映射只更新资料，不改绑定。 */
  @Transactional
  public Map<String, Object> sync() {
    long started = System.currentTimeMillis();
    Map<Long, String> departments = new LinkedHashMap<>();
    departments.put(properties.getSyncRootDeptId(), "根部门");
    Deque<Long> queue = new ArrayDeque<>();
    queue.add(properties.getSyncRootDeptId());
    while (!queue.isEmpty()) {
      long deptId = queue.poll();
      for (Map<String, Object> department : client.listSubDepartments(deptId)) {
        Long child = number(department.get("dept_id"));
        if (child == null || departments.containsKey(child)) continue;
        departments.put(child, str(department.get("name")));
        queue.add(child);
      }
    }
    Map<String, Map<String, Object>> users = new LinkedHashMap<>();
    for (Long deptId : departments.keySet()) {
      long cursor = 0;
      while (true) {
        Map<String, Object> page =
            client.listUsers(deptId, cursor, properties.getSyncPageSize());
        for (Map<String, Object> user : maps(page.get("userlist"))) {
          String userId = str(user.get("userid"));
          if (userId.isEmpty()) continue;
          Map<String, Object> existing = users.computeIfAbsent(userId, k -> new LinkedHashMap<>());
          existing.putAll(user);
          Set<String> names =
              (Set<String>) existing.computeIfAbsent("deptNames", k -> new LinkedHashSet<String>());
          if (departments.containsKey(deptId)) names.add(departments.get(deptId));
        }
        boolean more = flag(page.get("has_more"));
        if (!more) break;
        Long next = number(page.get("next_cursor"));
        if (next == null || next <= cursor) break;
        cursor = next;
      }
    }
    int matched = 0, pending = 0, unmatched = 0, conflict = 0, invalid = 0;
    Set<String> seen = new LinkedHashSet<>();
    for (Map<String, Object> user : users.values()) {
      String dingUserId = str(user.get("userid"));
      if (dingUserId.isEmpty()) continue;
      seen.add(dingUserId);
      String unionId = str(user.get("unionid"));
      if (unionId.isEmpty()) unionId = str(user.get("unionId"));
      String email = str(user.get("email"));
      if (email.isEmpty()) email = str(user.get("org_email"));
      String mobile = str(user.get("mobile"));
      String name = str(user.get("name"));
      Set<String> deptNames =
          (Set<String>) user.get("deptNames");
      if (deptNames == null) deptNames = Collections.emptySet();
      Map<String, Object> existing =
          store.one(
              "SELECT dingtalk_user_id,user_id,status,confirmed_at FROM rm_demand_dingtalk_user WHERE dingtalk_user_id=?",
              dingUserId);
      if (existing != null && CONFIRMED.equals(str(existing.get("status")))
          && existing.get("confirmedAt") != null) {
        store.update(
            "UPDATE rm_demand_dingtalk_user SET union_id=?,name=?,mobile=?,email=?,dept_names=?,active=TRUE,status=?,synced_at=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE dingtalk_user_id=?",
            emptyToNull(unionId),
            name,
            emptyToNull(mobile),
            emptyToNull(email),
            String.join(" / ", deptNames),
            CONFIRMED,
            dingUserId);
        matched++;
        continue;
      }
      Long localUserId = null;
      String matchSource = null;
      String status = UNMATCHED;
      if (!email.trim().isEmpty()) {
        List<Map<String, Object>> candidates =
            store.rows(
                "SELECT id FROM ac_user WHERE deleted=FALSE AND LOWER(email)=?",
                email.trim().toLowerCase(Locale.ROOT));
        if (candidates.size() == 1) {
          localUserId = number(candidates.get(0).get("id"));
          if (localUserId != null) {
            long used =
                store.count(
                    "SELECT COUNT(*) FROM rm_demand_dingtalk_user WHERE user_id=? AND dingtalk_user_id<>?",
                    localUserId,
                    dingUserId);
            if (used > 0) status = CONFLICT;
            else {
              status = PENDING_CONFIRM;
              matchSource = "EMAIL";
              pending++;
            }
          }
        } else if (candidates.size() > 1) status = CONFLICT;
      }
      if (PENDING_CONFIRM.equals(status)) matched++;
      else if (CONFLICT.equals(status)) conflict++;
      else unmatched++;
      if (existing == null) {
        store.update(
            "INSERT INTO rm_demand_dingtalk_user(dingtalk_user_id,union_id,user_id,name,mobile,email,dept_names,active,status,match_source,synced_at) VALUES(?,?,?,?,?,?,?,TRUE,?,?,CURRENT_TIMESTAMP)",
            dingUserId,
            emptyToNull(unionId),
            localUserId,
            name,
            emptyToNull(mobile),
            emptyToNull(email),
            String.join(" / ", deptNames),
            status,
            matchSource);
      } else {
        store.update(
            "UPDATE rm_demand_dingtalk_user SET union_id=?,user_id=?,name=?,mobile=?,email=?,dept_names=?,active=TRUE,status=?,match_source=?,synced_at=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE dingtalk_user_id=?",
            emptyToNull(unionId),
            localUserId,
            name,
            emptyToNull(mobile),
            emptyToNull(email),
            String.join(" / ", deptNames),
            status,
            matchSource,
            dingUserId);
      }
    }
    for (Map<String, Object> row :
        store.rows("SELECT dingtalk_user_id FROM rm_demand_dingtalk_user WHERE active=TRUE")) {
      String dingUserId = str(row.get("dingtalkUserId"));
      if (!seen.contains(dingUserId)) {
        store.update(
            "UPDATE rm_demand_dingtalk_user SET active=FALSE,status=?,update_time=CURRENT_TIMESTAMP WHERE dingtalk_user_id=?",
            INVALID,
            dingUserId);
        invalid++;
      }
    }
    log.info(
        "钉钉通讯录同步完成 users={} departments={} matched={} pending={} unmatched={} conflict={} invalid={}",
        users.size(),
        departments.size(),
        matched,
        pending,
        unmatched,
        conflict,
        invalid);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("users", users.size());
    result.put("departments", departments.size());
    result.put("matched", matched);
    result.put("pendingConfirm", pending);
    result.put("unmatched", unmatched);
    result.put("conflict", conflict);
    result.put("invalid", invalid);
    result.put("durationMs", System.currentTimeMillis() - started);
    return result;
  }

  /** 已确认且在职的映射；派发时使用。 */
  public Map<String, Object> confirmed(Long userId) {
    if (userId == null) return null;
    return store.one(
        "SELECT d.dingtalk_user_id,d.union_id,d.user_id,d.name FROM rm_demand_dingtalk_user d "
            + "JOIN ac_user u ON u.id=d.user_id AND u.deleted=FALSE "
            + "WHERE d.user_id=? AND d.active=TRUE AND d.status=? AND d.confirmed_at IS NOT NULL "
            + "AND d.union_id IS NOT NULL LIMIT 1",
        userId,
        CONFIRMED);
  }

  /** 待办创建者 unionId：优先配置，其次 creator-user-id 的已确认映射。 */
  public String creatorUnionId() {
    String configured = properties.getCreatorUnionId();
    if (configured != null && !configured.trim().isEmpty()) return configured.trim();
    if (properties.getCreatorUserId() > 0) {
      Map<String, Object> mapping = confirmed(properties.getCreatorUserId());
      if (mapping != null) return str(mapping.get("unionId"));
    }
    return null;
  }

  @Transactional
  public void bind(String dingtalkUserId, Long userId, Long operator) {
    requireActiveUser(userId);
    Map<String, Object> row =
        store.one(
            "SELECT dingtalk_user_id,active FROM rm_demand_dingtalk_user WHERE dingtalk_user_id=?",
            dingtalkUserId);
    if (row == null) throw new BaseException("钉钉用户不存在，请先同步通讯录");
    long used =
        store.count(
            "SELECT COUNT(*) FROM rm_demand_dingtalk_user WHERE user_id=? AND dingtalk_user_id<>?",
            userId,
            dingtalkUserId);
    if (used > 0) throw new BaseException("该本地账户已绑定其他钉钉账号", 409);
    store.update(
        "UPDATE rm_demand_dingtalk_user SET user_id=?,status=?,match_source='MANUAL',confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP,active=TRUE,update_time=CURRENT_TIMESTAMP WHERE dingtalk_user_id=?",
        userId,
        CONFIRMED,
        operator,
        dingtalkUserId);
  }

  @Transactional
  public void unbind(String dingtalkUserId, Long operator) {
    store.update(
        "UPDATE rm_demand_dingtalk_user SET user_id=NULL,status=?,match_source=NULL,confirmed_by=NULL,confirmed_at=NULL,update_time=CURRENT_TIMESTAMP WHERE dingtalk_user_id=?",
        UNMATCHED,
        dingtalkUserId);
  }

  @Transactional
  public void confirm(String dingtalkUserId, Long operator) {
    Map<String, Object> row =
        store.one(
            "SELECT user_id,status,union_id FROM rm_demand_dingtalk_user WHERE dingtalk_user_id=?",
            dingtalkUserId);
    if (row == null || number(row.get("userId")) == null)
      throw new BaseException("该钉钉用户尚未匹配本地账户");
    if (str(row.get("unionId")).isEmpty()) throw new BaseException("钉钉用户缺少 unionId，无法创建待办");
    requireActiveUser(number(row.get("userId")));
    store.update(
        "UPDATE rm_demand_dingtalk_user SET status=?,confirmed_by=?,confirmed_at=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE dingtalk_user_id=?",
        CONFIRMED,
        operator,
        dingtalkUserId);
  }

  @Transactional
  public int confirmMatched(Long operator) {
    int count = 0;
    for (Map<String, Object> row :
        store.rows(
            "SELECT dingtalk_user_id FROM rm_demand_dingtalk_user WHERE status=? AND user_id IS NOT NULL AND union_id IS NOT NULL AND active=TRUE",
            PENDING_CONFIRM)) {
      confirm(str(row.get("dingtalkUserId")), operator);
      count++;
    }
    return count;
  }

  public Map<String, Object> list(String state, String keyword, int page, int size) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (state != null && !state.trim().isEmpty()) {
      where.append(" AND d.status=?");
      args.add(state.trim().toUpperCase(Locale.ROOT));
    }
    if (keyword != null && !keyword.trim().isEmpty()) {
      where.append(" AND (d.name LIKE ? OR d.email LIKE ? OR u.name LIKE ?)");
      String like = "%" + keyword.trim() + "%";
      args.add(like);
      args.add(like);
      args.add(like);
    }
    long total =
        store.count(
            "SELECT COUNT(*) FROM rm_demand_dingtalk_user d LEFT JOIN ac_user u ON u.id=d.user_id"
                + where,
            args.toArray());
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(Math.min(100, Math.max(1, size)));
    pageArgs.add((Math.max(1, page) - 1) * Math.min(100, Math.max(1, size)));
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT d.dingtalk_user_id,d.union_id,d.user_id,u.name AS local_name,d.name,d.mobile,d.email,d.dept_names,d.active,d.status,d.match_source,d.confirmed_at,d.synced_at FROM rm_demand_dingtalk_user d LEFT JOIN ac_user u ON u.id=d.user_id"
                + where
                + " ORDER BY d.status,d.name LIMIT ? OFFSET ?",
            pageArgs.toArray());
    for (Map<String, Object> row : rows) {
      row.put("mobileMasked", mask(str(row.get("mobile"))));
      row.put("emailMasked", maskEmail(str(row.get("email"))));
      row.remove("mobile");
      row.remove("email");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rows", rows);
    result.put("total", total);
    result.put("page", Math.max(1, page));
    result.put("size", Math.min(100, Math.max(1, size)));
    result.put("counts", counts());
    return result;
  }

  public Map<String, Object> counts() {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT status,COUNT(*) AS total FROM rm_demand_dingtalk_user GROUP BY status")) {
      result.put(str(row.get("status")), number(row.get("total")));
    }
    result.put("total", store.count("SELECT COUNT(*) FROM rm_demand_dingtalk_user"));
    result.put("confirmed", store.count(
        "SELECT COUNT(*) FROM rm_demand_dingtalk_user WHERE status=? AND active=TRUE AND confirmed_at IS NOT NULL",
        CONFIRMED));
    return result;
  }

  public Map<String, Object> localUsers(String keyword) {
    String like = "%" + (keyword == null ? "" : keyword.trim()) + "%";
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT u.id AS user_id,u.name,u.email,u.phone_number,d.dingtalk_user_id,d.status FROM ac_user u LEFT JOIN rm_demand_dingtalk_user d ON d.user_id=u.id WHERE u.deleted=FALSE AND (u.name LIKE ? OR u.email LIKE ? OR CAST(u.id AS CHAR) LIKE ?) ORDER BY u.name LIMIT 50",
            like,
            like,
            like);
    for (Map<String, Object> row : rows) {
      row.put("emailMasked", maskEmail(str(row.get("email"))));
      row.put("phoneMasked", mask(str(row.get("phoneNumber"))));
      row.remove("email");
      row.remove("phoneNumber");
      row.put("bound", row.get("dingtalkUserId") != null);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rows", rows);
    return result;
  }

  private void requireActiveUser(Long userId) {
    if (userId == null
        || store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", userId) == 0)
      throw new BaseException("本地账户不存在或已停用");
  }

  private static String mask(String value) {
    if (value == null || value.length() < 4) return "";
    return value.substring(0, 3) + "****" + value.substring(value.length() - 2);
  }

  private static String maskEmail(String value) {
    int at = value == null ? -1 : value.indexOf('@');
    if (at <= 0) return "";
    return value.substring(0, Math.min(2, at)) + "***" + value.substring(at);
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    if (!(value instanceof List)) return Collections.emptyList();
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : (List<?>) value)
      if (item instanceof Map) result.add(new LinkedHashMap<>((Map<String, Object>) item));
    return result;
  }

  private static boolean flag(Object value) {
    if (value instanceof Boolean) return (Boolean) value;
    return value != null && Boolean.parseBoolean(value.toString());
  }

  private static String emptyToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }
}
