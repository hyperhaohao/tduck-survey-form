package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.number;

import com.tduck.cloud.common.exception.BaseException;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 平台能力点：单据管理、组织架构、用户管理；单据级权限仍由 rm_demand_acl 决定。 */
@Service
@RequiredArgsConstructor
public class DemandPermissions {
  public static final String FORM_MANAGE = "form.manage";
  public static final String ORG = "org";
  public static final String USER_MANAGE = "user.manage";
  public static final Set<String> GRANTABLE =
      Collections.unmodifiableSet(
          new LinkedHashSet<>(Arrays.asList(FORM_MANAGE, ORG, USER_MANAGE)));
  public static final Set<String> DEFAULT_ADMIN =
      Collections.unmodifiableSet(new LinkedHashSet<>(Collections.singletonList(FORM_MANAGE)));
  private static final Logger log = LoggerFactory.getLogger(DemandPermissions.class);
  private static final AtomicBoolean TABLE_ERROR_LOGGED = new AtomicBoolean();
  private final DemandStore store;
  private final DemandProperties properties;

  /** 读取用户当前平台能力；表不可读时失败关闭。 */
  public Set<String> granted(Long userId) {
    if (userId == null) return Collections.emptySet();
    try {
      Set<String> capabilities = new LinkedHashSet<>();
      for (Map<String, Object> row :
          store.rows("SELECT capability FROM rm_demand_permission WHERE user_id=?", userId))
        capabilities.add(String.valueOf(row.get("capability")));
      return capabilities;
    } catch (DataAccessException e) {
      if (TABLE_ERROR_LOGGED.compareAndSet(false, true))
        log.error(
            "rm_demand_permission 不可读，请执行 ruima-demand-migration-20261125-capability.sql；非全局用户平台能力已关闭",
            e);
      return Collections.emptySet();
    }
  }

  public boolean has(Long userId, String capability) {
    return capability != null && granted(userId).contains(capability);
  }

  /** 整体替换用户能力；只接受可授予能力，空集合表示撤销。 */
  @Transactional
  public void replace(Long userId, Collection<String> capabilities, Long grantedBy) {
    if (userId == null) throw new BaseException("用户不存在");
    Set<String> next = normalize(capabilities);
    if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", userId) == 0)
      throw new BaseException("用户不存在或已停用");
    store.update("DELETE FROM rm_demand_permission WHERE user_id=?", userId);
    for (String capability : next)
      store.update(
          "INSERT INTO rm_demand_permission(user_id,capability,granted_by) VALUES(?,?,?)",
          userId,
          capability,
          grantedBy);
    mirrorLegacy(userId, next.contains(FORM_MANAGE));
  }

  /** 旧接口兼容：并入默认能力，不覆盖已单独授予的组织架构/用户管理。 */
  @Transactional
  public void grantDefaults(Long userId, Long grantedBy) {
    Set<String> capabilities = new LinkedHashSet<>(granted(userId));
    capabilities.addAll(DEFAULT_ADMIN);
    replace(userId, capabilities, grantedBy);
  }

  /** 旧接口兼容：清空全部平台能力。 */
  @Transactional
  public void revokeAll(Long userId, Long grantedBy) {
    replace(userId, Collections.emptyList(), grantedBy);
  }

  /** 已授权用户与能力集，供全局管理员授权界面使用。 */
  public List<Map<String, Object>> users() {
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT p.user_id,u.name,p.capability FROM rm_demand_permission p "
                + "JOIN ac_user u ON u.id=p.user_id AND u.deleted=FALSE "
                + "ORDER BY u.name,u.id,p.capability");
    Map<Long, Map<String, Object>> grouped = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      Long userId = number(row.get("userId"));
      Map<String, Object> item =
          grouped.computeIfAbsent(
              userId,
              key -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("userId", key == null ? null : key.toString());
                value.put("name", row.get("name"));
                value.put("capabilities", new ArrayList<String>());
                return value;
              });
      @SuppressWarnings("unchecked")
      List<String> capabilities = (List<String>) item.get("capabilities");
      capabilities.add(String.valueOf(row.get("capability")));
    }
    return new ArrayList<>(grouped.values());
  }

  /** 能力定义，前端矩阵直接渲染，避免键名漂移。 */
  public static List<Map<String, Object>> definitions() {
    List<Map<String, Object>> list = new ArrayList<>();
    list.add(definition(FORM_MANAGE, "单据管理", "进入单据管理页，可新建收集单、设计发布与使用 AI；数据范围仍按收集单授权"));
    list.add(definition(ORG, "组织架构", "维护部门、成员、部门主管；可进入单据管理页的组织架构页签"));
    list.add(definition(USER_MANAGE, "用户管理", "进入用户管理页，查看、新增、编辑、停用用户；不含重置密码"));
    return list;
  }

  /** 仍由全局管理员专属的功能，授权界面只读展示。 */
  public static List<Map<String, Object>> globalOnly() {
    List<Map<String, Object>> list = new ArrayList<>();
    list.add(definition("console.all", "后台管理全部收集单", "查看全部收集单与全部需求"));
    list.add(definition("admin.grant", "平台权限授权", "授予或撤销其他用户的平台能力"));
    list.add(definition("form.purge", "彻底删除收集单", "仅无需求、无答卷的收集单"));
    list.add(definition("form.sort", "首页全局排序", "调整所有用户看到的收集单顺序"));
    list.add(definition("org.import", "部门目录导入", "批量导入部门树与成员关系"));
    list.add(definition("original.console", "原表单后台/数据/报表/回收站", "原系统表单管理与数据查看"));
    list.add(definition("ai.config", "AI 智能建表与配置", "独立 AI 建表入口与模型配置"));
    return list;
  }

  private static Map<String, Object> definition(String key, String name, String description) {
    Map<String, Object> item = new LinkedHashMap<>();
    item.put("key", key);
    item.put("name", name);
    item.put("description", description);
    return item;
  }

  private static Set<String> normalize(Collection<String> raw) {
    Set<String> next = new LinkedHashSet<>();
    if (raw == null) return next;
    for (Object value : raw) {
      String key = value == null ? "" : value.toString().trim();
      if (key.isEmpty()) continue;
      if (!GRANTABLE.contains(key)) throw new BaseException("无效或不可授予的能力：" + key, 400);
      next.add(key);
    }
    return next;
  }

  /** 镜像旧表，保证回滚到旧版本后仍能识别后台管理员。 */
  private void mirrorLegacy(Long userId, boolean formManage) {
    store.update("DELETE FROM rm_demand_admin WHERE user_id=?", userId);
    if (formManage) store.update("INSERT INTO rm_demand_admin(user_id) VALUES(?)", userId);
  }

  /** 启动时检查迁移是否执行；旧表只用于回滚与 OIDC 锁。 */
  @EventListener(ApplicationReadyEvent.class)
  public void checkMigration() {
    if (!properties.isEnabled()) return;
    try {
      long legacy = store.count("SELECT COUNT(*) FROM rm_demand_admin WHERE user_id<>0");
      long missing =
          store.count(
              "SELECT COUNT(*) FROM rm_demand_admin a WHERE a.user_id<>0 AND NOT EXISTS("
                  + "SELECT 1 FROM rm_demand_permission p WHERE p.user_id=a.user_id AND p.capability='form.manage')");
      if (missing > 0)
        log.warn(
            "检测到 {} 个存量后台管理员尚未迁移 form.manage，请执行 ruima-demand-migration-20261125-capability.sql",
            missing);
      if (legacy > 0) log.info("旧后台管理员 {} 个；新权限表为准，旧表仅用于回滚与登录锁", legacy);
    } catch (DataAccessException e) {
      log.error("检查平台权限迁移状态失败，请确认 rm_demand_permission 已创建", e);
    }
  }
}
