package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 部门目录、用户部门关系与收集单部门授权。 */
@Service
@RequiredArgsConstructor
public class DemandDepartments {
  private final DemandStore store;
  private final DemandAccess access;

  /** 可授权部门列表，成员数包含下级部门。 */
  public List<Map<String, Object>> list(Long userId) {
    access.requireConsoleAdmin(userId);
    return store.rows(
        "SELECT d.dept_key,d.name,d.parent_key,d.path,d.sort_no,"
            + "(SELECT COUNT(DISTINCT ud.user_id) FROM rm_demand_user_dept ud "
            + "JOIN ac_user u ON u.id=ud.user_id AND u.deleted=FALSE "
            + "JOIN rm_demand_dept pd ON pd.dept_key=ud.dept_key AND pd.active=TRUE "
            + "WHERE pd.path=d.path OR LOCATE(CONCAT(d.path,'/'),pd.path)=1) AS member_count "
            + "FROM rm_demand_dept d WHERE d.active=TRUE ORDER BY d.sort_no,d.name");
  }

  /** 收集单的部门授权。 */
  public List<Map<String, Object>> members(String key, Long userId) {
    access.requireConsoleAdmin(userId);
    access.requireManage(key, userId);
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT ad.dept_key,d.name,ad.can_fill,ad.can_handle,ad.can_manage,"
                + "(SELECT COUNT(DISTINCT ud.user_id) FROM rm_demand_user_dept ud "
                + "JOIN ac_user u ON u.id=ud.user_id AND u.deleted=FALSE "
                + "JOIN rm_demand_dept pd ON pd.dept_key=ud.dept_key AND pd.active=TRUE "
                + "WHERE pd.path=d.path OR LOCATE(CONCAT(d.path,'/'),pd.path)=1) AS member_count "
                + "FROM rm_demand_acl_dept ad JOIN rm_demand_dept d ON d.dept_key=ad.dept_key "
                + "WHERE ad.form_key=? ORDER BY d.sort_no,d.name",
            key);
    DemandForms.normalizeMemberFlags(rows);
    return rows;
  }

  /** 保存部门授权；撤掉可管理时保证至少一名有效管理员。 */
  @Transactional
  public void save(String key, String deptKey, Map<String, Object> body, Long userId) {
    access.requireConsoleAdmin(userId);
    access.requireManage(key, userId);
    if (store.one("SELECT form_key FROM rm_demand_form WHERE form_key=? FOR UPDATE", key) == null)
      throw new BaseException("请先保存需求收集配置");
    if (store.count(
            "SELECT COUNT(*) FROM rm_demand_dept WHERE dept_key=? AND active=TRUE", deptKey)
        == 0) throw new BaseException("部门不存在或已停用");
    Map<String, Object> old =
        store.one(
            "SELECT can_manage FROM rm_demand_acl_dept WHERE form_key=? AND dept_key=?",
            key,
            deptKey);
    boolean canManage =
        body.containsKey("canManage")
            ? flag(body.get("canManage"))
            : old != null && flag(old.get("canManage"));
    if (old != null
        && flag(old.get("canManage"))
        && !canManage
        && !access.hasOtherManager(key, null, deptKey))
      throw new BaseException("每个收集单至少保留一名单据管理员");
    store.update("DELETE FROM rm_demand_acl_dept WHERE form_key=? AND dept_key=?", key, deptKey);
    store.update(
        "INSERT INTO rm_demand_acl_dept(form_key,dept_key,can_fill,can_handle,can_manage,created_by) VALUES(?,?,?,?,?,?)",
        key,
        deptKey,
        flag(body.get("canFill")),
        flag(body.get("canHandle")),
        canManage,
        userId);
  }

  /** 移除部门授权。 */
  @Transactional
  public void remove(String key, String deptKey, Long userId) {
    access.requireConsoleAdmin(userId);
    access.requireManage(key, userId);
    Map<String, Object> old =
        store.one(
            "SELECT can_manage FROM rm_demand_acl_dept WHERE form_key=? AND dept_key=?",
            key,
            deptKey);
    if (old == null) return;
    if (flag(old.get("canManage")) && !access.hasOtherManager(key, null, deptKey))
      throw new BaseException("每个收集单至少保留一名单据管理员");
    store.update("DELETE FROM rm_demand_acl_dept WHERE form_key=? AND dept_key=?", key, deptKey);
  }

  /** 全局管理员导入部门树与成员关系；replace=true 时停用未出现的部门并重建成员关系。 */
  @Transactional
  public Map<String, Object> importTree(Map<String, Object> body, Long userId) {
    access.requireGlobalAdmin(userId);
    boolean replace = flag(body.get("replace"));
    List<Map<String, Object>> departments = maps(body.get("departments"));
    List<Map<String, Object>> members = maps(body.get("members"));
    if (replace && departments.isEmpty()) throw new BaseException("部门清单不能为空");

    Map<String, String> names = new LinkedHashMap<>();
    Map<String, String> parents = new LinkedHashMap<>();
    Map<String, Integer> sorts = new LinkedHashMap<>();
    Set<String> existingKeys = new LinkedHashSet<>();
    for (Map<String, Object> row : store.rows("SELECT dept_key FROM rm_demand_dept"))
      existingKeys.add(String.valueOf(row.get("deptKey")));
    for (Map<String, Object> department : departments) {
      String key = required(department.get("key"), 64, "部门标识");
      if (key.contains("/")) throw new BaseException("部门标识不能包含 /");
      String name = required(department.get("name"), 100, "部门名称");
      Object parentValue = department.get("parentKey");
      String parent =
          parentValue == null || parentValue.toString().trim().isEmpty()
              ? null
              : parentValue.toString().trim();
      if (names.containsKey(key)) throw new BaseException("部门标识重复：" + key);
      if (parent != null && !existingKeys.contains(parent) && !containsKey(departments, parent))
        throw new BaseException("上级部门不存在：" + parent);
      names.put(key, name);
      parents.put(key, parent);
      sorts.put(key, integer(department.get("sort")));
    }

    for (String key : names.keySet()) {
      String parent = parents.get(key);
      if (store.update(
              "UPDATE rm_demand_dept SET name=?,parent_key=?,sort_no=?,active=TRUE WHERE dept_key=?",
              names.get(key),
              parent,
              sorts.get(key),
              key)
          == 0)
        store.update(
            "INSERT INTO rm_demand_dept(dept_key,name,parent_key,path,sort_no,active) VALUES(?,?,?,?,?,TRUE)",
            key,
            names.get(key),
            parent,
            "/" + key,
            sorts.get(key));
    }
    if (replace) {
      if (names.isEmpty()) {
        store.update("UPDATE rm_demand_dept SET active=FALSE");
      } else {
        StringBuilder sql =
            new StringBuilder("UPDATE rm_demand_dept SET active=FALSE WHERE dept_key NOT IN (");
        List<Object> args = new ArrayList<>();
        boolean first = true;
        for (String key : names.keySet()) {
          if (!first) sql.append(',');
          sql.append('?');
          args.add(key);
          first = false;
        }
        sql.append(')');
        store.update(sql.toString(), args.toArray());
      }
      store.update("DELETE FROM rm_demand_user_dept");
    }
    rebuildPaths();

    int memberships = 0;
    for (Map<String, Object> member : members) {
      Long memberId = resolveUser(member);
      for (Object value : items(member.get("deptKeys"))) {
        String deptKey = String.valueOf(value);
        if (store.count(
                "SELECT COUNT(*) FROM rm_demand_dept WHERE dept_key=? AND active=TRUE", deptKey)
            == 0) throw new BaseException("部门不存在或已停用：" + deptKey);
        if (store.count(
                "SELECT COUNT(*) FROM rm_demand_user_dept WHERE user_id=? AND dept_key=?",
                memberId,
                deptKey)
            == 0)
          store.update(
              "INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(?,?)", memberId, deptKey);
        memberships++;
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("departments", names.size());
    result.put("memberships", memberships);
    result.put("replaced", replace);
    return result;
  }

  /** 重算全部部门路径，保证上级调整后下级范围仍正确。 */
  private void rebuildPaths() {
    Map<String, String> parents = new LinkedHashMap<>();
    for (Map<String, Object> row : store.rows("SELECT dept_key,parent_key FROM rm_demand_dept")) {
      Object parent = row.get("parentKey");
      parents.put(
          String.valueOf(row.get("deptKey")),
          parent == null ? null : String.valueOf(parent));
    }
    Map<String, String> paths = new LinkedHashMap<>();
    for (String key : parents.keySet())
      resolvePath(key, parents, paths, Collections.emptyMap(), new HashSet<>());
    for (Map.Entry<String, String> entry : paths.entrySet())
      store.update(
          "UPDATE rm_demand_dept SET path=? WHERE dept_key=?", entry.getValue(), entry.getKey());
  }

  private static String resolvePath(
      String key,
      Map<String, String> parents,
      Map<String, String> paths,
      Map<String, String> existingPaths,
      Set<String> visiting) {
    if (paths.containsKey(key)) return paths.get(key);
    if (!visiting.add(key)) throw new BaseException("部门层级存在循环");
    String parent = parents.get(key);
    String base;
    if (parent == null || parent.isEmpty()) base = "";
    else if (parents.containsKey(parent))
      base = resolvePath(parent, parents, paths, existingPaths, visiting);
    else if (existingPaths.containsKey(parent)) base = existingPaths.get(parent);
    else throw new BaseException("上级部门不存在：" + parent);
    String path = base + "/" + key;
    paths.put(key, path);
    visiting.remove(key);
    return path;
  }

  private Long resolveUser(Map<String, Object> member) {
    if (member.get("userId") != null) {
      Long memberId = number(member.get("userId"));
      if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", memberId) == 0)
        throw new BaseException("用户不存在或已停用：" + memberId);
      return memberId;
    }
    String name = member.get("userName") == null ? "" : member.get("userName").toString().trim();
    if (name.isEmpty()) throw new BaseException("成员缺少 userId 或 userName");
    List<Map<String, Object>> users =
        store.rows("SELECT id FROM ac_user WHERE name=? AND deleted=FALSE", name);
    if (users.size() != 1) throw new BaseException("用户姓名不唯一或不存在：" + name);
    return number(users.get(0).get("id"));
  }

  private static boolean containsKey(List<Map<String, Object>> departments, String key) {
    for (Map<String, Object> department : departments)
      if (department.get("key") != null && department.get("key").toString().equals(key)) return true;
    return false;
  }

  private static String required(Object value, int max, String label) {
    String text = value == null ? "" : value.toString().trim();
    if (text.isEmpty() || text.length() > max)
      throw new BaseException(label + "不能为空且不能超过" + max + "字");
    return text;
  }

  private static int integer(Object value) {
    if (value == null || value.toString().trim().isEmpty()) return 0;
    try {
      return Integer.parseInt(value.toString().trim());
    } catch (NumberFormatException error) {
      throw new BaseException("排序值必须是整数");
    }
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> maps(Object value) {
    if (value == null) return Collections.emptyList();
    if (!(value instanceof List)) throw new BaseException("清单格式无效");
    List<Map<String, Object>> result = new ArrayList<>();
    for (Object item : (List<Object>) value) {
      if (!(item instanceof Map)) throw new BaseException("清单格式无效");
      result.add((Map<String, Object>) item);
    }
    return result;
  }

  @SuppressWarnings("unchecked")
  private static List<Object> items(Object value) {
    if (value == null) return Collections.emptyList();
    if (!(value instanceof List)) throw new BaseException("部门成员格式无效");
    return (List<Object>) value;
  }
}
