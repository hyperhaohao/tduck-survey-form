package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.account.entity.UserEntity;
import com.tduck.cloud.account.service.UserService;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.SecurityUtils;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 填报、经办、管理权限并集；所有接口复用服务端判定。 */
@Service
@RequiredArgsConstructor
public class DemandAccess {
  private final DemandStore store;
  private final DemandProperties properties;
  private final UserService users;
  /** 模块是否启用。 */
  public boolean enabled() {
    return properties.isEnabled();
  }
  /** 全局管理员：原系统超级管理员或配置项，拥有全部收集单能力。 */
  public boolean globalAdmin(Long userId) {
    return userId != null
        && (SecurityUtils.isAdmin(userId)
            || (enabled() && properties.getAdminIds().contains(userId)));
  }
  /** 后台管理员：可进入后台并建单，数据仍按收集单授权。 */
  public boolean consoleAdmin(Long userId) {
    return globalAdmin(userId)
        || (userId != null
            && enabled()
            && store.count("SELECT COUNT(*) FROM rm_demand_admin WHERE user_id=?", userId) > 0);
  }
  /** 本地账户停用后立即拒绝访问，不等令牌自然过期。 */
  public void requireActive(Long userId) {
    if (userId != null
        && store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", userId) == 0)
      throw new BaseException("账户已停用", 403);
  }
  /** 要求模块已启用。 */
  public void requireEnabled() {
    if (!enabled()) throw new BaseException("需求模块尚未启用");
  }
  /** 要求全局管理员权限。 */
  public void requireGlobalAdmin(Long userId) {
    requireEnabled();
    if (!globalAdmin(userId)) throw new BaseException("仅全局管理员可执行此操作", 403);
  }
  /** 要求后台管理员权限，具体数据范围仍由收集单授权决定。 */
  public void requireConsoleAdmin(Long userId) {
    requireEnabled();
    if (!consoleAdmin(userId)) throw new BaseException("仅后台管理员可执行此操作", 403);
  }
  /** 查询收集单扩展配置。 */
  public Map<String, Object> form(String key) {
    return enabled() ? store.one("SELECT * FROM rm_demand_form WHERE form_key=?", key) : null;
  }
  /** 用户直授与部门授权的有效集合；部门授权默认包含下级部门。 */
  public DemandGrants effectiveGrants(Long userId) {
    DemandGrants grants = new DemandGrants();
    if (userId == null || !enabled()) return grants;
    for (Map<String, Object> row :
        store.rows(
            "SELECT form_key,can_fill,can_handle,can_manage FROM rm_demand_acl WHERE user_id=?",
            userId))
      grants.add(
          String.valueOf(row.get("formKey")),
          row.get("canFill"),
          row.get("canHandle"),
          row.get("canManage"));
    for (Map<String, Object> row :
        store.rows(
            "SELECT ad.form_key,ad.can_fill,ad.can_handle,ad.can_manage FROM rm_demand_acl_dept ad "
                + "JOIN rm_demand_user_dept ud ON ud.user_id=? "
                + "JOIN rm_demand_dept pd ON pd.dept_key=ud.dept_key AND pd.active=TRUE "
                + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
                + "WHERE pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1",
            userId))
      grants.add(
          String.valueOf(row.get("formKey")),
          row.get("canFill"),
          row.get("canHandle"),
          row.get("canManage"));
    return grants;
  }
  /** 判断指定账户是否具有经办权限。 */
  public boolean handler(String key, Long userId) {
    return userId != null && effectiveGrants(userId).canHandle(key);
  }
  /** 单据管理员可管理本收集单的全部需求，全局管理员天然拥有该能力。 */
  public boolean manage(String key, Long userId) {
    return globalAdmin(userId) || (userId != null && effectiveGrants(userId).canManage(key));
  }
  /** 是否可查看总表：经办人、单据管理员或全局管理员。 */
  public boolean table(String key, Long userId) {
    if (globalAdmin(userId)) return true;
    if (userId == null) return false;
    DemandGrants grants = effectiveGrants(userId);
    return grants.canHandle(key) || grants.canManage(key);
  }
  /** 是否有其他有效单据管理员；部门直授要求至少一名在职成员。 */
  public boolean hasOtherManager(String key, Long excludeUserId, String excludeDeptKey) {
    StringBuilder users =
        new StringBuilder(
            "SELECT COUNT(*) FROM rm_demand_acl WHERE form_key=? AND can_manage=TRUE");
    List<Object> userArgs = new ArrayList<>();
    userArgs.add(key);
    if (excludeUserId != null) {
      users.append(" AND user_id<>?");
      userArgs.add(excludeUserId);
    }
    if (store.count(users.toString(), userArgs.toArray()) > 0) return true;
    StringBuilder departments =
        new StringBuilder(
            "SELECT COUNT(*) FROM rm_demand_acl_dept ad "
                + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
                + "WHERE ad.form_key=? AND ad.can_manage=TRUE");
    List<Object> departmentArgs = new ArrayList<>();
    departmentArgs.add(key);
    if (excludeDeptKey != null) {
      departments.append(" AND ad.dept_key<>?");
      departmentArgs.add(excludeDeptKey);
    }
    departments.append(
        " AND EXISTS(SELECT 1 FROM rm_demand_user_dept ud "
            + "JOIN rm_demand_dept pd ON pd.dept_key=ud.dept_key AND pd.active=TRUE "
            + "JOIN ac_user u ON u.id=ud.user_id AND u.deleted=FALSE "
            + "WHERE pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1)");
    return store.count(departments.toString(), departmentArgs.toArray()) > 0;
  }
  /** 是否原表单创建人，用于首次配置的自有表单。 */
  public boolean owner(String key, Long userId) {
    return userId != null
        && store.count(
                "SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND user_id=? AND is_deleted=FALSE",
                key,
                userId)
            > 0;
  }

  /** 是否在填报名单中。 */
  public boolean visible(Map<String, Object> form, Long userId) {
    if (userId == null) return false;
    return "LOGIN".equals(form.get("visibility"))
        || effectiveGrants(userId).canFill(String.valueOf(form.get("formKey")));
  }
  /** 校验填报方式、发布状态和截止时间，不能用匿名绕过名单。 */
  public void requireFill(String key, Long userId, boolean anonymous) {
    Map<String, Object> form = form(key);
    if (form == null) return;
    if (store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=FALSE", key)
        == 0) throw new BaseException("该收集单已删除");
    if (flag(form.get("voided"))) throw new BaseException("该收集单已作废");
    if (!flag(form.get("published"))) throw new BaseException("该收集单尚未开放");
    if (form.get("endTime") instanceof java.util.Date
        && ((java.util.Date) form.get("endTime")).getTime() < System.currentTimeMillis())
      throw new BaseException("该收集单已截止");
    if (anonymous) {
      if (!flag(form.get("allowAnonymous")) || !"LOGIN".equals(form.get("visibility")))
        throw new BaseException("该收集单不允许匿名填写", 403);
    } else if (!visible(form, userId)) throw new BaseException("请登录后使用有填报权限的账户填写", 403);
  }
  /** 校验当前经办人，管理员身份不能代替经办分派。 */
  public void requireAssigned(Map<String, Object> demand, Long userId) {
    if (userId == null
        || !userId.equals(number(demand.get("handlerId")))
        || !handler(demand.get("formKey").toString(), userId))
      throw new BaseException("仅当前经办人可处理此需求", 403);
  }
  /** 要求本收集单的单据管理员或全局管理员。 */
  public void requireManage(String key, Long userId) {
    requireEnabled();
    if (!manage(key, userId)) throw new BaseException("仅该收集单的单据管理员可执行此操作", 403);
  }
  /** 当前经办人或单据管理员均可处理，管理员可代填结果与回复。 */
  public void requireHandle(Map<String, Object> demand, Long userId) {
    String key = demand.get("formKey").toString();
    if (userId == null) throw new BaseException("仅当前经办人或单据管理员可处理此需求", 403);
    if (manage(key, userId)) return;
    requireAssigned(demand, userId);
  }

  /** 获取可显示姓名，不返回完整用户实体。 */
  public String name(Long userId) {
    UserEntity user = userId == null ? null : users.getById(userId);
    return user == null ? "用户" : (user.getName() == null ? "用户" : user.getName());
  }
  /** 返回菜单能力与通知数量。 */
  public Map<String, Object> capabilities(Long userId) {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("enabled", enabled());
    if (!enabled() || userId == null) {
      m.put("admin", false);
      m.put("global", false);
      m.put("handler", false);
      m.put("manage", false);
      return m;
    }
    boolean global = globalAdmin(userId);
    DemandGrants grants = effectiveGrants(userId);
    m.put("admin", consoleAdmin(userId));
    m.put("global", global);
    m.put("handler", grants.anyHandle());
    m.put("manage", global || grants.anyManage());
    m.put(
        "mineUnread",
        store.count(
            "SELECT COUNT(*) FROM rm_demand d LEFT JOIN rm_demand_read r ON r.object_type='DEMAND' AND r.object_id=d.id AND r.user_id=? WHERE d.submitter_id=? AND d.public_version>COALESCE(r.seen_version,1)",
            userId,
            userId));
    StringBuilder pendingWhere =
        new StringBuilder(" WHERE f.is_deleted=FALSE");
    List<Object> pendingArgs = new ArrayList<>();
    DemandScope.pending(pendingWhere, pendingArgs, userId, grants);
    m.put(
        "pending",
        store.count(
            "SELECT COUNT(*) FROM rm_demand d JOIN fm_user_form f ON f.form_key=d.form_key"
                + pendingWhere,
            pendingArgs.toArray()));
    StringBuilder publicationUnread =
        new StringBuilder(
            "SELECT COUNT(*) FROM rm_demand_publication p JOIN rm_demand d ON d.id=p.demand_id JOIN rm_demand_form f ON f.form_key=d.form_key LEFT JOIN rm_demand_read r ON r.object_type='PUBLICATION' AND r.object_id=p.id AND r.user_id=? WHERE p.published=TRUE AND p.version>COALESCE(r.seen_version,0) AND (f.visibility='LOGIN'");
    List<Object> publicationArgs = new ArrayList<>();
    publicationArgs.add(userId);
    if (!global && !grants.allForms().isEmpty()) {
      publicationUnread.append(" OR f.form_key IN (");
      boolean first = true;
      for (String key : grants.allForms()) {
        if (!first) publicationUnread.append(',');
        publicationUnread.append('?');
        publicationArgs.add(key);
        first = false;
      }
      publicationUnread.append(')');
    }
    publicationUnread.append(" OR ?=TRUE)");
    publicationArgs.add(global);
    m.put(
        "publicationUnread",
        store.count(publicationUnread.toString(), publicationArgs.toArray()));
    return m;
  }
}
