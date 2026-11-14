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
  /** 普通登录不自动获得管理权限。 */
  public boolean admin(Long userId) {
    return userId != null
        && (SecurityUtils.isAdmin(userId)
            || (enabled()
                && (properties.getAdminIds().contains(userId)
                    || store.count("SELECT COUNT(*) FROM rm_demand_admin WHERE user_id=?", userId)
                        > 0)));
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
  /** 要求管理员权限。 */
  public void requireAdmin(Long userId) {
    requireEnabled();
    if (!admin(userId)) throw new BaseException("仅管理员可执行此操作", 403);
  }
  /** 查询收集单扩展配置。 */
  public Map<String, Object> form(String key) {
    return enabled() ? store.one("SELECT * FROM rm_demand_form WHERE form_key=?", key) : null;
  }
  /** 判断指定账户是否具有经办权限。 */
  public boolean handler(String key, Long userId) {
    return userId != null
        && store.count(
                "SELECT COUNT(*) FROM rm_demand_acl WHERE form_key=? AND user_id=? AND can_handle=TRUE",
                key,
                userId)
            > 0;
  }
  /** 单据管理员可管理本收集单的全部需求，全局管理员天然拥有该能力。 */
  public boolean manage(String key, Long userId) {
    return admin(userId)
        || (userId != null
            && store.count(
                    "SELECT COUNT(*) FROM rm_demand_acl WHERE form_key=? AND user_id=? AND can_manage=TRUE",
                    key,
                    userId)
                > 0);
  }
  /** 是否可查看总表：经办人、单据管理员或全局管理员。 */
  public boolean table(String key, Long userId) {
    return admin(userId) || handler(key, userId) || manage(key, userId);
  }

  /** 是否在填报名单中。 */
  public boolean visible(Map<String, Object> form, Long userId) {
    return userId != null
        && ("LOGIN".equals(form.get("visibility"))
            || store.count(
                    "SELECT COUNT(*) FROM rm_demand_acl WHERE form_key=? AND user_id=? AND can_fill=TRUE",
                    form.get("formKey"),
                    userId)
                > 0);
  }
  /** 校验填报方式、发布状态和截止时间，不能用匿名绕过名单。 */
  public void requireFill(String key, Long userId, boolean anonymous) {
    Map<String, Object> form = form(key);
    if (form == null) return;
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
      m.put("handler", false);
      m.put("manage", false);
      return m;
    }
    m.put("admin", admin(userId));
    m.put(
        "handler",
        store.count(
                "SELECT COUNT(*) FROM rm_demand_acl WHERE user_id=? AND (can_handle=TRUE OR can_manage=TRUE)",
                userId)
            > 0);
    m.put(
        "manage",
        admin(userId)
            || store.count(
                    "SELECT COUNT(*) FROM rm_demand_acl WHERE user_id=? AND can_manage=TRUE",
                    userId)
                > 0);
    m.put(
        "mineUnread",
        store.count(
            "SELECT COUNT(*) FROM rm_demand d LEFT JOIN rm_demand_read r ON r.object_type='DEMAND' AND r.object_id=d.id AND r.user_id=? WHERE d.submitter_id=? AND d.public_version>COALESCE(r.seen_version,1)",
            userId,
            userId));
    StringBuilder pendingWhere =
        new StringBuilder(" WHERE f.is_deleted=FALSE");
    List<Object> pendingArgs = new ArrayList<>();
    DemandScope.pending(pendingWhere, pendingArgs, userId);
    m.put(
        "pending",
        store.count(
            "SELECT COUNT(*) FROM rm_demand d JOIN fm_user_form f ON f.form_key=d.form_key"
                + pendingWhere,
            pendingArgs.toArray()));
    m.put(
        "publicationUnread",
        store.count(
            "SELECT COUNT(*) FROM rm_demand_publication p JOIN rm_demand d ON d.id=p.demand_id JOIN rm_demand_form f ON f.form_key=d.form_key LEFT JOIN rm_demand_read r ON r.object_type='PUBLICATION' AND r.object_id=p.id AND r.user_id=? WHERE p.published=TRUE AND p.version>COALESCE(r.seen_version,0) AND (f.visibility='LOGIN' OR EXISTS(SELECT 1 FROM rm_demand_acl a WHERE a.form_key=f.form_key AND a.user_id=? AND (a.can_fill=TRUE OR a.can_handle=TRUE OR a.can_manage=TRUE)) OR ?=TRUE)",
            userId,
            userId,
            admin(userId)));
    return m;
  }
}
