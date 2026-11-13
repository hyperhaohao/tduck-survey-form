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
    if (management) access.requireAdmin(userId);
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT f.form_key,f.name,f.description,f.status,c.summary,c.visibility,c.allow_anonymous,c.published,c.end_time,c.revision FROM fm_user_form f "
                + (management ? "LEFT" : "INNER")
                + " JOIN rm_demand_form c ON c.form_key=f.form_key WHERE f.is_deleted=FALSE AND (f.is_folder=FALSE OR f.is_folder IS NULL) ORDER BY f.create_time DESC");
    rows.removeIf(
        row ->
            !management
                && (!flag(row.get("published"))
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
    if (userId == null) {
      Map<String, Object> gate = new LinkedHashMap<>();
      gate.put("managed", true);
      gate.put(
          "allowAnonymous",
          flag(form.get("published"))
              && flag(form.get("allowAnonymous"))
              && "LOGIN".equals(form.get("visibility")));
      return gate;
    }
    if (!access.table(key, userId)) access.requireFill(key, userId, false);
    Map<String, Object> result = new LinkedHashMap<>(form);
    result.put("managed", true);
    return result;
  }
  /** 设置独立业务收集配置，不能将指定人员收集单同时开放匿名。 */
  @Transactional
  public void configure(String key, Map<String, Object> body, Long userId) {
    access.requireAdmin(userId);
    if (store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=FALSE", key)
        == 0) throw new BaseException("表单不存在");
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
    }
  }
  /** 查看具名权限。 */
  public List<Map<String, Object>> members(String key, Long userId) {
    access.requireAdmin(userId);
    return store.rows(
        "SELECT a.user_id,u.name,a.can_fill,a.can_handle FROM rm_demand_acl a JOIN ac_user u ON u.id=a.user_id WHERE a.form_key=?",
        key);
  }
  /** 分别授权填报与经办能力，撤权立即影响后续访问及待办。 */
  @Transactional
  public void member(String key, Long memberId, Map<String, Object> body, Long userId) {
    access.requireAdmin(userId);
    if (access.form(key) == null) throw new BaseException("请先保存需求收集配置");
    if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", memberId) == 0)
      throw new BaseException("用户不存在或已停用");
    store.update("DELETE FROM rm_demand_acl WHERE form_key=? AND user_id=?", key, memberId);
    store.update(
        "INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle) VALUES(?,?,?,?)",
        key,
        memberId,
        flag(body.get("canFill")),
        flag(body.get("canHandle")));
  }
  /** 管理页面仅返回必要的账户标识和姓名。 */
  public List<Map<String, Object>> users(String keyword, Long userId) {
    access.requireAdmin(userId);
    String q = keyword == null ? "" : keyword;
    return store.rows(
        "SELECT id,name FROM ac_user WHERE deleted=FALSE AND (name LIKE ? OR CAST(id AS CHAR) LIKE ?) ORDER BY name LIMIT 200",
        "%" + q + "%",
        "%" + q + "%");
  }
  /** 查看需求模块额外管理员，不返回认证信息。 */
  public List<Map<String, Object>> admins(Long userId) {
    access.requireAdmin(userId);
    return store.rows(
        "SELECT a.user_id,u.name FROM rm_demand_admin a JOIN ac_user u ON u.id=a.user_id");
  }
  /** 显式增加或撤销管理能力，禁止误撤当前管理会话的能力。 */
  @Transactional
  public void admin(Long memberId, boolean grant, Long userId) {
    access.requireAdmin(userId);
    if (memberId.equals(userId) && !grant) throw new BaseException("不能在当前会话撤销自己的管理权限");
    if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", memberId) == 0)
      throw new BaseException("用户不存在或已停用");
    store.update("DELETE FROM rm_demand_admin WHERE user_id=?", memberId);
    if (grant) store.update("INSERT INTO rm_demand_admin(user_id) VALUES(?)", memberId);
  }
}
