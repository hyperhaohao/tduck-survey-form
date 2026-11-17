package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
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
  /** 查询当前身份可见的收集单，管理查询不公开名单。 */
  public List<Map<String, Object>> list(Long userId, boolean management) {
    access.requireEnabled();
    if (management) access.requireConsoleAdmin(userId);
    boolean global = access.globalAdmin(userId);
    StringBuilder sql =
        new StringBuilder(
            "SELECT f.form_key,f.name,f.description,f.status,c.summary,c.visibility,c.allow_anonymous,c.published,c.end_time,c.voided,c.revision,(SELECT COUNT(*) FROM rm_demand d WHERE d.form_key=f.form_key) AS demand_count FROM fm_user_form f "
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
    sql.append(" ORDER BY f.create_time DESC");
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
    return rows;
  }
  /** 元数据不泄露授权名单，允许经办人在后台查看停用的收集单。 */
  public Map<String, Object> info(String key, Long userId) {
    if (!access.enabled()) return Collections.singletonMap("managed", false);
    Map<String, Object> form = access.form(key);
    if (form == null) return Collections.singletonMap("managed", false);
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
    return result;
  }
  /** 设置独立业务收集配置，不能将指定人员收集单同时开放匿名。 */
  @Transactional
  public void configure(String key, Map<String, Object> body, Long userId) {
    access.requireConsoleAdmin(userId);
    if (store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=FALSE", key)
        == 0) throw new BaseException("表单不存在");
    if (!access.globalAdmin(userId) && !access.manage(key, userId) && !access.owner(key, userId))
      throw new BaseException("仅该收集单的创建人或单据管理员可执行此操作", 403);
    String visibility = String.valueOf(body.getOrDefault("visibility", "LOGIN"));
    if (!Arrays.asList("LOGIN", "SELECTED").contains(visibility)) throw new BaseException("填报范围无效");
    boolean anonymous = flag(body.get("allowAnonymous"));
    if (anonymous && !"LOGIN".equals(visibility)) throw new BaseException("指定人员收集单不能开放匿名填写");
    String summary = body.get("summary") == null ? "" : body.get("summary").toString();
    if (summary.length() > 2000) throw new BaseException("说明不能超过2000字");
    Object end = body.get("endTime");
    java.sql.Timestamp endTime =
        end == null || end.toString().isEmpty() ? null : java.sql.Timestamp.valueOf(end.toString());
    Map<String, Object> old =
        store.one("SELECT * FROM rm_demand_form WHERE form_key=? FOR UPDATE", key);
    if (old == null) {
      store.update(
          "INSERT INTO rm_demand_form(form_key,summary,visibility,allow_anonymous,published,end_time) VALUES(?,?,?,?,?,?)",
          key,
          summary,
          visibility,
          anonymous,
          flag(body.get("published")),
          endTime);
      grantCreatorManager(key);
    } else {
      long revision = Long.parseLong(String.valueOf(body.getOrDefault("revision", -1)));
      if (revision != number(old.get("revision"))) throw new BaseException("配置已变化，请刷新后重试", 409);
      store.update(
          "UPDATE rm_demand_form SET summary=?,visibility=?,allow_anonymous=?,published=?,end_time=?,revision=revision+1 WHERE form_key=?",
          summary,
          visibility,
          anonymous,
          flag(body.get("published")),
          endTime,
          key);
      if (!access.hasOtherManager(key, null, null)) grantCreatorManager(key);
    }
  }
  /** 作废后停止新填报并从员工入口隐藏，需求历史保留，可恢复。 */
  @Transactional
  public void setVoided(String key, boolean voided, Long userId) {
    access.requireConsoleAdmin(userId);
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
    access.requireConsoleAdmin(userId);
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
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("demandCount", demands);
    result.put("dataCount", data);
    return result;
  }
  /** 从全局回收站恢复收集单。 */
  @Transactional
  public void restoreCollection(String key, Long userId) {
    access.requireConsoleAdmin(userId);
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
    access.requireConsoleAdmin(userId);
    access.requireManage(key, userId);
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT a.user_id,u.name,a.can_fill,a.can_handle,a.can_manage FROM rm_demand_acl a JOIN ac_user u ON u.id=a.user_id WHERE a.form_key=? ORDER BY u.name,a.user_id",
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
    access.requireConsoleAdmin(userId);
    access.requireManage(key, userId);
    if (store.one("SELECT form_key FROM rm_demand_form WHERE form_key=? FOR UPDATE", key) == null)
      throw new BaseException("请先保存需求收集配置");
    if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", memberId) == 0)
      throw new BaseException("用户不存在或已停用");
    Map<String, Object> old =
        store.one(
            "SELECT can_manage FROM rm_demand_acl WHERE form_key=? AND user_id=?", key, memberId);
    boolean canManage =
        body.containsKey("canManage")
            ? flag(body.get("canManage"))
            : old != null && flag(old.get("canManage"));
    if (old != null
        && flag(old.get("canManage"))
        && !canManage
        && !access.hasOtherManager(key, memberId, null))
      throw new BaseException("每个收集单至少保留一名单据管理员");
    store.update("DELETE FROM rm_demand_acl WHERE form_key=? AND user_id=?", key, memberId);
    store.update(
        "INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle,can_manage) VALUES(?,?,?,?,?)",
        key,
        memberId,
        flag(body.get("canFill")),
        flag(body.get("canHandle")),
        canManage);
  }
  /** 移除成员授权；最后一名单据管理员不可移除。 */
  @Transactional
  public void removeMember(String key, Long memberId, Long userId) {
    access.requireConsoleAdmin(userId);
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
  }
  /** 管理页面仅返回必要的账户标识和姓名。 */
  public List<Map<String, Object>> users(String keyword, Long userId) {
    access.requireConsoleAdmin(userId);
    String q = keyword == null ? "" : keyword;
    return store.rows(
        "SELECT id,name FROM ac_user WHERE deleted=FALSE AND (name LIKE ? OR CAST(id AS CHAR) LIKE ?) ORDER BY name LIMIT 200",
        "%" + q + "%",
        "%" + q + "%");
  }
  /** 查看需求模块额外管理员，不返回认证信息。 */
  public List<Map<String, Object>> admins(Long userId) {
    access.requireGlobalAdmin(userId);
    return store.rows(
        "SELECT a.user_id,u.name FROM rm_demand_admin a JOIN ac_user u ON u.id=a.user_id");
  }
  /** 显式增加或撤销管理能力，禁止误撤当前管理会话的能力。 */
  @Transactional
  public void admin(Long memberId, boolean grant, Long userId) {
    access.requireGlobalAdmin(userId);
    if (memberId.equals(userId) && !grant) throw new BaseException("不能在当前会话撤销自己的管理权限");
    if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", memberId) == 0)
      throw new BaseException("用户不存在或已停用");
    store.update("DELETE FROM rm_demand_admin WHERE user_id=?", memberId);
    if (grant) store.update("INSERT INTO rm_demand_admin(user_id) VALUES(?)", memberId);
  }
}
