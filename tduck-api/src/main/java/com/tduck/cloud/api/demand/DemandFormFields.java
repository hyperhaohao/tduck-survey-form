package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import cn.hutool.json.JSONUtil;
import com.tduck.cloud.form.data.FormatDataContext;
import com.tduck.cloud.form.util.HtmlUtils;
import com.tduck.cloud.form.vo.FormFieldVO;
import java.lang.reflect.Array;
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
  private static final String LABEL_SUFFIX = "label";
  private static final String OTHER_SUFFIX = "other";
  private static final int MAX_TEXT = 30000;

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

  /** 导出时格式化字段显示值；兼容显示键、原始键、旧 vModel 与“其他”文本。 */
  public String displayValue(FormFieldVO field, Map<String, Object> data) {
    if (field == null || data == null || isDepartmentField(field)) return "";
    Object value = null;
    for (String key : fieldKeys(field)) {
      Object candidate = data.get(key);
      if (!empty(candidate)) {
        value = candidate;
        break;
      }
    }
    if (value == null) return "";
    String text = isUpload(field.getType()) ? attachments(value) : formatField(field, value);
    String other = otherValue(field, data);
    if (!other.isEmpty()) text = text.isEmpty() ? other : text + "（其他：" + other + "）";
    return clip(text);
  }

  /** 跨表单导出的可读填报内容。 */
  public String contentText(List<FormFieldVO> fields, Map<String, Object> data) {
    List<String> parts = new ArrayList<>();
    if (fields != null) {
      for (FormFieldVO field : fields) {
        if (isDepartmentField(field)) continue;
        String value = displayValue(field, data);
        if (!value.isEmpty()) parts.add(plain(field.getLabel()) + "：" + value);
      }
    }
    String other = otherText(fields, data);
    if (!other.isEmpty()) parts.add("其他：" + other);
    return clip(String.join("；", parts));
  }

  /** 当前表单题目之外的旧键，避免改题/删题后历史答案丢失。 */
  public String otherText(List<FormFieldVO> fields, Map<String, Object> data) {
    if (data == null || data.isEmpty()) return "";
    Set<String> known = knownKeys(fields);
    List<String> parts = new ArrayList<>();
    for (Map.Entry<String, Object> entry : data.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.startsWith("demandDept") || known.contains(key)) continue;
      String base = rawKey(key);
      if (known.contains(base)) continue;
      if (key.endsWith(LABEL_SUFFIX) && known.contains(base)) continue;
      if (key.endsWith(OTHER_SUFFIX) && known.contains(base)) continue;
      Object value = entry.getValue();
      if (empty(value)) continue;
      String text = formatUnknown(value);
      if (!text.isEmpty()) parts.add(key + "：" + text);
    }
    return clip(String.join("；", parts));
  }

  private List<String> fieldKeys(FormFieldVO field) {
    LinkedHashSet<String> keys = new LinkedHashSet<>();
    addKey(keys, field.getValue());
    addKey(keys, rawKey(field.getValue()));
    Map<?, ?> scheme = schemeMap(field.getScheme());
    if (scheme != null) {
      addKey(keys, scheme.get("vModel"));
      addKey(keys, scheme.get("formItemId"));
      Object config = scheme.get("config");
      if (config instanceof Map) {
        addKey(keys, ((Map<?, ?>) config).get("vModel"));
        addKey(keys, ((Map<?, ?>) config).get("formItemId"));
      }
    }
    List<String> expanded = new ArrayList<>();
    for (String key : keys) {
      expanded.add(key);
      if (!key.endsWith(LABEL_SUFFIX)) expanded.add(key + LABEL_SUFFIX);
    }
    return expanded;
  }

  private void addKey(Set<String> keys, Object value) {
    if (value == null) return;
    String text = String.valueOf(value).trim();
    if (!text.isEmpty()) keys.add(text);
  }

  private String rawKey(String value) {
    if (value == null) return "";
    return value.endsWith(LABEL_SUFFIX)
        ? value.substring(0, value.length() - LABEL_SUFFIX.length())
        : value;
  }

  private String formatField(FormFieldVO field, Object value) {
    if (!isChoice(field.getType()) && !isUpload(field.getType())) {
      String handled = handlerText(field, value);
      if (!handled.isEmpty()) return handled;
    }
    if (value instanceof Collection || value.getClass().isArray()) {
      List<String> parts = new ArrayList<>();
      for (Object item : items(value)) {
        String text = itemText(field, item);
        if (!text.isEmpty()) parts.add(text);
      }
      return String.join("、", parts);
    }
    return itemText(field, value);
  }

  private String handlerText(FormFieldVO field, Object value) {
    try {
      Object formatted =
          FormatDataContext.getHandler(String.valueOf(field.getValue()))
              .formatData(String.valueOf(field.getValue()), value, field.getScheme());
      if (formatted != null && !String.valueOf(formatted).trim().isEmpty())
        return plain(String.valueOf(formatted));
    } catch (Exception ignored) {
      // 复杂题型格式化失败时回退原值。
    }
    return "";
  }

  private String itemText(FormFieldVO field, Object value) {
    if (value == null) return "";
    if (value instanceof Map) return objectText((Map<?, ?>) value);
    String raw = plain(String.valueOf(value));
    if (raw.isEmpty()) return "";
    String option = optionLabel(field, raw);
    if (!option.isEmpty()) return option;
    return raw;
  }

  private String optionLabel(FormFieldVO field, String raw) {
    if (field == null) return "";
    Map<?, ?> scheme = schemeMap(field.getScheme());
    if (scheme == null) return "";
    Object config = scheme.get("config");
    Object options =
        config instanceof Map ? ((Map<?, ?>) config).get("options") : scheme.get("options");
    if (!(options instanceof Collection)) return "";
    for (Object item : (Collection<?>) options) {
      if (!(item instanceof Map)) continue;
      Map<?, ?> option = (Map<?, ?>) item;
      Object value = option.get("value");
      Object label = option.get("label");
      if ((value != null && raw.equals(String.valueOf(value).trim()))
          || (label != null && raw.equals(String.valueOf(label).trim())))
        return label == null ? raw : plain(String.valueOf(label));
    }
    return "";
  }

  private String otherValue(FormFieldVO field, Map<String, Object> data) {
    for (String key : fieldKeys(field)) {
      Object value = data.get(key + OTHER_SUFFIX);
      if (!empty(value)) return plain(String.valueOf(value));
    }
    return "";
  }

  private Set<String> knownKeys(List<FormFieldVO> fields) {
    Set<String> known = new HashSet<>();
    known.add("demandDept");
    known.add("demandDeptlabel");
    if (fields == null) return known;
    for (FormFieldVO field : fields) {
      for (String key : fieldKeys(field)) {
        known.add(key);
        known.add(rawKey(key));
        known.add(key + OTHER_SUFFIX);
      }
    }
    return known;
  }

  private String attachments(Object value) {
    List<String> parts = new ArrayList<>();
    for (Object item : items(value)) {
      if (item instanceof Map) {
        Map<?, ?> map = (Map<?, ?>) item;
        String name = plain(map.get("name") == null ? "" : String.valueOf(map.get("name")));
        String url = plain(map.get("url") == null ? "" : String.valueOf(map.get("url")));
        if (!name.isEmpty() && !url.isEmpty()) parts.add(name + "（" + url + "）");
        else if (!name.isEmpty()) parts.add(name);
        else if (!url.isEmpty()) parts.add(url);
      } else {
        String text = plain(String.valueOf(item));
        if (!text.isEmpty()) parts.add(text);
      }
    }
    return String.join("、", parts);
  }

  private String formatUnknown(Object value) {
    if (value instanceof Collection || value.getClass().isArray()) {
      List<String> parts = new ArrayList<>();
      for (Object item : items(value)) {
        String text = item instanceof Map ? objectText((Map<?, ?>) item) : plain(String.valueOf(item));
        if (!text.isEmpty()) parts.add(text);
      }
      return String.join("、", parts);
    }
    return value instanceof Map ? objectText((Map<?, ?>) value) : plain(String.valueOf(value));
  }

  private String objectText(Map<?, ?> map) {
    String name = plain(map.get("name") == null ? "" : String.valueOf(map.get("name")));
    String url = plain(map.get("url") == null ? "" : String.valueOf(map.get("url")));
    if (!name.isEmpty() || !url.isEmpty())
      return name.isEmpty() ? url : url.isEmpty() ? name : name + "（" + url + "）";
    Object label = map.get("label");
    Object value = map.get("value");
    if (label != null || value != null)
      return plain(label != null ? String.valueOf(label) : String.valueOf(value));
    return plain(String.valueOf(map));
  }

  private List<Object> items(Object value) {
    List<Object> items = new ArrayList<>();
    if (value == null) return items;
    if (value instanceof Collection) {
      items.addAll((Collection<?>) value);
      return items;
    }
    if (value.getClass().isArray()) {
      int length = Array.getLength(value);
      for (int i = 0; i < length; i++) items.add(Array.get(value, i));
      return items;
    }
    items.add(value);
    return items;
  }

  private Map<?, ?> schemeMap(Object raw) {
    if (raw instanceof Map) return (Map<?, ?>) raw;
    if (raw instanceof String && !String.valueOf(raw).trim().isEmpty()) {
      try {
        return JSONUtil.parseObj(String.valueOf(raw));
      } catch (Exception ignored) {
        // 非 JSON 配置按无配置处理。
      }
    }
    return null;
  }

  private boolean isUpload(String type) {
    if (type == null) return false;
    String value = type.toUpperCase();
    return "UPLOAD".equals(value) || "IMAGE_UPLOAD".equals(value);
  }

  private boolean isChoice(String type) {
    if (type == null) return false;
    String value = type.toUpperCase();
    return "SELECT".equals(value)
        || "RADIO".equals(value)
        || "CHECKBOX".equals(value)
        || "CASCADER".equals(value)
        || "IMAGE_SELECT".equals(value);
  }

  private boolean empty(Object value) {
    if (value == null) return true;
    if (value instanceof Collection) return ((Collection<?>) value).isEmpty();
    if (value.getClass().isArray()) return Array.getLength(value) == 0;
    String text = String.valueOf(value).trim();
    return text.isEmpty() || "null".equalsIgnoreCase(text);
  }

  private String plain(Object value) {
    if (value == null) return "";
    String text = HtmlUtils.cleanHtmlTag(String.valueOf(value));
    return text == null ? "" : text.replace(' ', ' ').trim();
  }

  private String clip(String value) {
    if (value == null) return "";
    return value.length() <= MAX_TEXT ? value : value.substring(0, MAX_TEXT);
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
