package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import cn.hutool.json.JSONUtil;
import com.tduck.cloud.form.vo.FormFieldVO;
import java.sql.Clob;
import java.sql.SQLException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 需求提交时的填报人部门快照；部门题已从表单移除，详情只读快照。 */
@Service
@RequiredArgsConstructor
public class DemandFormFields {
  private static final String NONE = "无";

  private final DemandStore store;

  /** 匿名固定“无”；登录用户返回所有所属部门的完整路径，无部门时返回“无”。 */
  public List<String> snapshot(Long userId, boolean anonymous) {
    if (anonymous || userId == null) return Collections.singletonList(NONE);
    List<Map<String, Object>> departments =
        store.rows(
            "SELECT dept_key,name,parent_key,sort_no FROM rm_demand_dept WHERE active=TRUE ORDER BY sort_no,name");
    Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
    for (Map<String, Object> department : departments)
      byKey.put(String.valueOf(department.get("deptKey")), department);
    Map<String, String> labelByKey = new LinkedHashMap<>();
    for (Map<String, Object> department : departments)
      labelByKey.put(
          String.valueOf(department.get("deptKey")), departmentLabel(department, byKey));
    Set<String> mine = new LinkedHashSet<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT d.dept_key FROM rm_demand_user_dept ud JOIN rm_demand_dept d ON d.dept_key=ud.dept_key AND d.active=TRUE WHERE ud.user_id=? ORDER BY d.sort_no,d.name",
            userId))
      mine.add(String.valueOf(row.get("deptKey")));
    List<String> values = new ArrayList<>();
    for (Map<String, Object> department : departments) {
      String key = String.valueOf(department.get("deptKey"));
      if (mine.contains(key)) values.add(labelByKey.get(key));
    }
    if (values.isEmpty()) values.add(NONE);
    return values;
  }

  /** 写入 rm_demand.department 的 JSON 数组文本。 */
  public String snapshotJson(Long userId, boolean anonymous) {
    return JSONUtil.toJsonStr(snapshot(userId, anonymous));
  }

  /** 详情读取快照；兼容旧答卷里的 demandDept / demandDeptlabel。 */
  public List<String> resolve(
      Object stored, Map<String, Object> originalData, boolean anonymous) {
    List<String> values = parse(stored);
    if (values.isEmpty() && originalData != null) {
      values = parse(originalData.get("demandDeptlabel"));
      if (values.isEmpty()) values = parse(originalData.get("demandDept"));
    }
    if (values.isEmpty() && anonymous) values.add(NONE);
    return values;
  }

  /** 识别历史表单里的部门题，详情返回前过滤，避免重复或显示原始键。 */
  public boolean isDepartmentField(FormFieldVO field) {
    if (field == null) return false;
    String value = String.valueOf(field.getValue() == null ? "" : field.getValue());
    if ("demandDept".equals(value) || "demandDeptlabel".equals(value)) return true;
    Object raw = field.getScheme();
    if (!(raw instanceof Map)) return false;
    Object config = ((Map<?, ?>) raw).get("config");
    if (!(config instanceof Map)) return false;
    Object marker = ((Map<?, ?>) config).get("demandDept");
    return Boolean.TRUE.equals(marker) || "true".equals(String.valueOf(marker));
  }

  /** 解析 JSON 数组、集合、Clob 或单值，去空去重。 */
  private List<String> parse(Object raw) {
    if (raw == null) return new ArrayList<>();
    if (raw instanceof Clob) return parse(clobText((Clob) raw));
    if (raw instanceof Collection) {
      List<String> values = new ArrayList<>();
      for (Object item : (Collection<?>) raw) add(values, item);
      return dedupe(values);
    }
    String text = String.valueOf(raw).trim();
    if (text.isEmpty() || "null".equalsIgnoreCase(text)) return new ArrayList<>();
    try {
      Object parsed = JSONUtil.parseArray(text);
      if (parsed instanceof Collection) {
        List<String> values = new ArrayList<>();
        for (Object item : (Collection<?>) parsed) add(values, item);
        return dedupe(values);
      }
    } catch (Exception ignored) {
      // 非 JSON 数组按单值处理。
    }
    List<String> values = new ArrayList<>();
    add(values, text);
    return dedupe(values);
  }

  private void add(List<String> values, Object raw) {
    if (raw == null) return;
    String text = raw instanceof Clob ? clobText((Clob) raw) : String.valueOf(raw);
    text = text.trim();
    if (text.isEmpty() || "null".equalsIgnoreCase(text)) return;
    if (text.length() >= 2 && text.startsWith("\"") && text.endsWith("\""))
      text = text.substring(1, text.length() - 1).trim();
    if (!text.isEmpty()) values.add(text);
  }

  private List<String> dedupe(List<String> values) {
    return new ArrayList<>(new LinkedHashSet<>(values));
  }

  private String clobText(Clob clob) {
    try {
      return clob.getSubString(1, (int) clob.length());
    } catch (SQLException error) {
      return "";
    }
  }

  private String departmentLabel(
      Map<String, Object> department, Map<String, Map<String, Object>> byKey) {
    List<String> names = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Map<String, Object> current = department;
    while (current != null && seen.add(String.valueOf(current.get("deptKey")))) {
      names.add(0, String.valueOf(current.get("name")));
      Object parentKey = current.get("parentKey");
      current = parentKey == null ? null : byKey.get(String.valueOf(parentKey));
    }
    return String.join(" / ", names);
  }
}
