package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.account.entity.UserEntity;
import com.tduck.cloud.account.service.UserService;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.SecurityUtils;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 填报、经办、管理权限并集；所有接口复用服务端判定。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandAccess {
  private final DemandStore store;
  private final DemandProperties properties;
  private final UserService users;
  private final DemandPermissions permissions;
  private final DemandRounds rounds;
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
  /** 平台能力点判定；全局管理员恒真。 */
  public boolean capability(Long userId, String capability) {
    if (globalAdmin(userId)) return true;
    if (userId == null || !enabled()) return false;
    return permissions.has(userId, capability);
  }
  /** 单据管理页准入：单据管理、组织架构或任一收集单的单据管理员。 */
  public boolean documentManager(Long userId) {
    if (globalAdmin(userId)) return true;
    if (userId == null || !enabled()) return false;
    if (capability(userId, DemandPermissions.FORM_MANAGE)
        || capability(userId, DemandPermissions.ORG)) return true;
    return effectiveGrants(userId).anyManage();
  }
  /** 用户管理页准入：用户管理能力或全局管理员。 */
  public boolean userManager(Long userId) {
    return globalAdmin(userId) || capability(userId, DemandPermissions.USER_MANAGE);
  }
  /** 兼容别名：旧“后台管理员”语义已由单据管理页替代。 */
  public boolean consoleAdmin(Long userId) {
    return documentManager(userId);
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
  /** 要求指定平台能力。 */
  public void requireCapability(Long userId, String capability, String message) {
    requireEnabled();
    if (!capability(userId, capability)) throw new BaseException(message, 403);
  }
  /** 要求单据管理页准入。 */
  public void requireDocumentManager(Long userId) {
    requireEnabled();
    if (!documentManager(userId)) throw new BaseException("仅单据管理者可执行此操作", 403);
  }
  /** 要求用户管理页准入；模块关闭时原超级管理员仍可维护本地用户。 */
  public void requireUserManager(Long userId) {
    if (!userManager(userId)) throw new BaseException("仅用户管理者可执行此操作", 403);
  }
  /** 兼容别名：后台管理员入口已改为单据管理页。 */
  public void requireConsoleAdmin(Long userId) {
    requireDocumentManager(userId);
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

  /** 是否可填报：LOGIN 收集单对所有登录用户开放，指定人员只认 can_fill。 */
  public boolean canFill(Map<String, Object> form, Long userId) {
    if (userId == null) return false;
    return "LOGIN".equals(form.get("visibility"))
        || effectiveGrants(userId).canFill(String.valueOf(form.get("formKey")));
  }
  /** 列表可见：LOGIN 或任一成员权限（填报/经办/管理）。 */
  public boolean visible(Map<String, Object> form, Long userId) {
    if (userId == null) return false;
    if ("LOGIN".equals(form.get("visibility"))) return true;
    return effectiveGrants(userId).allForms().contains(String.valueOf(form.get("formKey")));
  }
  /** 校验填报方式、发布状态和截止时间，不能用匿名绕过名单。 */
  public void requireFill(String key, Long userId, boolean anonymous) {
    Map<String, Object> form = form(key);
    if (form == null) return;
    form = rounds.advanceIfExpired(form);
    if (store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=FALSE", key)
        == 0) throw new BaseException("该收集单已删除");
    if (flag(form.get("voided"))) throw new BaseException("该收集单已作废");
    if (!flag(form.get("published"))) throw new BaseException("该收集单尚未开放");
    if (form.get("endTime") instanceof java.util.Date
        && ((java.util.Date) form.get("endTime")).getTime() < System.currentTimeMillis())
      throw new BaseException("该收集单已截止");
    if (form.get("roundEndTime") instanceof java.util.Date
        && ((java.util.Date) form.get("roundEndTime")).getTime() < System.currentTimeMillis())
      throw new BaseException("本轮收集已截止");
    if (anonymous) {
      if (approvalRequired(form))
        throw new BaseException("该收集单需部门主管审批，不允许匿名填写", 403);
      if (!flag(form.get("allowAnonymous")) || !"LOGIN".equals(form.get("visibility")))
        throw new BaseException("该收集单不允许匿名填写", 403);
    } else if (!canFill(form, userId)) throw new BaseException("请登录后使用有填报权限的账户填写", 403);
  }

  private boolean approvalRequired(Map<String, Object> form) {
    if (flag(form.get("requiresLeaderApproval"))) return true;
    String mode = form.get("approvalMode") == null ? "" : form.get("approvalMode").toString();
    return "DIRECT".equalsIgnoreCase(mode) || "HIERARCHY".equalsIgnoreCase(mode);
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
      m.put("documentAccess", false);
      m.put("formManage", false);
      m.put("org", false);
      m.put("userManage", false);
      m.put("global", false);
      m.put("handler", false);
      m.put("manage", false);
      m.put("granted", Collections.emptyList());
      m.put("permissionApi", true);
      m.put("noticeUnread", 0);
      m.put("approvalPending", 0);
      m.put("approver", false);
      return m;
    }
    boolean global = globalAdmin(userId);
    DemandGrants grants = effectiveGrants(userId);
    Set<String> platform =
        global ? DemandPermissions.GRANTABLE : permissions.granted(userId);
    boolean formManage = global || platform.contains(DemandPermissions.FORM_MANAGE);
    boolean org = global || platform.contains(DemandPermissions.ORG);
    boolean userManage = global || platform.contains(DemandPermissions.USER_MANAGE);
    boolean documentAccess = formManage || org || grants.anyManage();
    m.put("admin", documentAccess);
    m.put("documentAccess", documentAccess);
    m.put("formManage", formManage);
    m.put("org", org);
    m.put("userManage", userManage);
    m.put("global", global);
    m.put("handler", grants.anyHandle());
    m.put("manage", global || grants.anyManage());
    m.put("granted", new ArrayList<>(platform));
    m.put("permissionApi", true);
    m.put(
        "mineUnread",
        store.count(
            "SELECT COUNT(*) FROM rm_demand d LEFT JOIN rm_demand_read r ON r.object_type='DEMAND' AND r.object_id=d.id AND r.user_id=? WHERE d.submitter_id=? AND d.public_version>COALESCE(r.seen_version,1)",
            userId,
            userId));
    StringBuilder pendingWhere =
        new StringBuilder(" WHERE f.is_deleted=FALSE");
    List<Object> pendingArgs = new ArrayList<>();
    DemandScope.pending(pendingWhere, pendingArgs, userId, grants, global);
    m.put(
        "pending",
        store.count(
            "SELECT COUNT(*) FROM rm_demand d JOIN fm_user_form f ON f.form_key=d.form_key"
                + pendingWhere,
            pendingArgs.toArray()));
    StringBuilder publicationUnread =
        new StringBuilder(
            "SELECT COUNT(*) FROM rm_demand_publication p JOIN rm_demand d ON d.id=p.demand_id JOIN rm_demand_form f ON f.form_key=d.form_key JOIN fm_user_form u ON u.form_key=d.form_key AND u.is_deleted=FALSE LEFT JOIN rm_demand_read r ON r.object_type='PUBLICATION' AND r.object_id=p.id AND r.user_id=? WHERE p.published=TRUE AND p.deleted_at IS NULL AND p.archived_at IS NULL AND p.version>COALESCE(r.seen_version,0) AND (f.visibility='LOGIN'");
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
    long noticeUnread = 0;
    try {
      noticeUnread =
          store.count(
              "SELECT COUNT(*) FROM rm_demand_notice WHERE user_id=? AND read_at IS NULL", userId);
    } catch (org.springframework.dao.DataAccessException error) {
      log.warn("消息表尚未就绪，暂不返回未读消息数");
    }
    m.put("noticeUnread", noticeUnread);
    long approvalPending = 0;
    try {
      approvalPending =
          store.count(
              "SELECT COUNT(*) FROM rm_demand d JOIN fm_user_form f ON f.form_key=d.form_key AND f.is_deleted=FALSE"
                  + " WHERE d.approval_status='PENDING' AND (EXISTS(SELECT 1 FROM rm_demand_approval_step s "
                  + "JOIN rm_demand_approval_approver a ON a.step_id=s.id WHERE s.demand_id=d.id "
                  + "AND s.status='PENDING' AND a.user_id=?) OR (d.approval_leader_id=? AND "
                  + "NOT EXISTS(SELECT 1 FROM rm_demand_approval_step s2 WHERE s2.demand_id=d.id)))",
              userId,
              userId);
    } catch (org.springframework.dao.DataAccessException error) {
      log.warn("审批字段尚未就绪，暂不返回待审批数");
    }
    boolean departmentLeader = false;
    try {
      departmentLeader =
          store.count(
                  "SELECT COUNT(*) FROM rm_demand_dept_leader l "
                      + "JOIN rm_demand_dept d ON d.dept_key=l.dept_key AND d.active=TRUE "
                      + "JOIN ac_user u ON u.id=l.user_id AND u.deleted=FALSE WHERE l.user_id=?",
                  userId)
              > 0;
    } catch (org.springframework.dao.DataAccessException error) {
      log.warn("多主管表尚未就绪，暂用单主管字段");
    }
    if (!departmentLeader)
      departmentLeader =
          store.count("SELECT COUNT(*) FROM rm_demand_dept WHERE leader_id=? AND active=TRUE", userId)
              > 0;
    m.put("approvalPending", approvalPending);
    m.put("approver", departmentLeader || approvalPending > 0);
    return m;
  }
}
