package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.form.entity.UserFormDataEntity;
import com.tduck.cloud.form.entity.UserFormItemEntity;
import com.tduck.cloud.form.entity.enums.FormItemTypeEnum;
import com.tduck.cloud.form.vo.FormFieldVO;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 收集单的「填报人部门」系统题：默认添加、填写时带出、提交时覆盖。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandFormFields {
  private static final String ITEM_ID = "demandDept";
  private static final String NONE = "无";

  private final DemandStore store;
  private final DemandAccess access;

  /** 后台打开设计器前补入系统题，幂等。 */
  @Transactional
  public void ensureDepartmentField(String formKey, Long userId) {
    access.requireConsoleAdmin(userId);
    access.requireManage(formKey, userId);
    if (store.count("SELECT COUNT(*) FROM rm_demand_form WHERE form_key=?", formKey) == 0)
      throw new BaseException("收集单不存在");
    persistDepartmentField(formKey);
  }

  /** 为需求收集单落库部门题；调用方负责权限校验，失败不阻塞填写。 */
  public synchronized void persistDepartmentField(String formKey) {
    if (findDepartmentItemId(formKey) != null) return;
    try {
      Map<String, Object> next =
          store.one(
              "SELECT COALESCE(MAX(sort),0)+1 AS next_sort FROM fm_user_form_item WHERE form_key=?",
              formKey);
      long sort = next == null || next.get("nextSort") == null ? 1 : number(next.get("nextSort"));
      store.update(
          "INSERT INTO fm_user_form_item(form_key,form_item_id,type,label,is_display_type,is_hide_type,is_special_type,show_label,default_value,required,placeholder,sort,span,scheme,reg_list) "
              + "VALUES(?,?,?,?,FALSE,FALSE,FALSE,TRUE,NULL,TRUE,?,?,?,?,?)",
          formKey,
          ITEM_ID,
          "SELECT",
          "填报人部门",
          "请选择",
          sort,
          24,
          JSONUtil.toJsonStr(departmentScheme()),
          "[]");
    } catch (Exception error) {
      log.warn("补入填报人部门题失败：{}", formKey);
    }
  }

  /** 填写页返回前注入选项、默认部门并锁定。 */
  public void decorate(String formKey, List<UserFormItemEntity> items, Long userId) {
    if (store.count("SELECT COUNT(*) FROM rm_demand_form WHERE form_key=?", formKey) == 0) return;
    DepartmentData data = departmentData(userId);
    UserFormItemEntity target = null;
    for (UserFormItemEntity item : items) if (isDepartmentItem(item)) target = item;
    if (target == null) {
      persistDepartmentField(formKey);
      target = departmentItem();
      items.add(target);
    }
    apply(target, data);
  }

  /** 需求详情补充部门字段，历史表单未落库时也能显示中文标签。 */
  public void decorateFormFields(String formKey, List<FormFieldVO> fields) {
    if (store.count("SELECT COUNT(*) FROM rm_demand_form WHERE form_key=?", formKey) == 0) return;
    for (FormFieldVO field : fields) {
      if (field == null) continue;
      if (ITEM_ID.equals(field.getValue()) || ITEM_ID.concat("label").equals(field.getValue()))
        return;
      Object raw = field.getScheme();
      if (!(raw instanceof Map)) continue;
      Object config = ((Map<?, ?>) raw).get("config");
      if (!(config instanceof Map)) continue;
      Object marker = ((Map<?, ?>) config).get("demandDept");
      if (Boolean.TRUE.equals(marker) || "true".equals(String.valueOf(marker))) return;
    }
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("label", "填报人部门");
    config.put("demandDept", true);
    Map<String, Object> scheme = new LinkedHashMap<>();
    scheme.put("typeId", "SELECT");
    scheme.put("config", config);
    fields.add(new FormFieldVO(ITEM_ID + "label", "填报人部门", "SELECT", scheme));
  }

  /** 提交前用服务端结果覆盖客户端值。 */
  public void applyToSubmission(
      UserFormDataEntity entity, Long userId, boolean anonymous) {
    Map<String, Object> originalData = entity.getOriginalData();
    if (originalData == null) return;
    if (store.count("SELECT COUNT(*) FROM rm_demand_form WHERE form_key=?", entity.getFormKey())
        == 0) return;
    String itemId = findDepartmentItemId(entity.getFormKey());
    Map<String, Object> data = new LinkedHashMap<>(originalData);
    data.put(
        itemId == null ? ITEM_ID : itemId,
        anonymous ? Collections.singletonList(NONE) : departmentData(userId).values);
    entity.setOriginalData(data);
  }

  private boolean isDepartmentItem(UserFormItemEntity item) {
    if (item == null || item.getScheme() == null) return false;
    Object value = item.getScheme().get("config");
    if (!(value instanceof Map)) return false;
    Object marker = ((Map<?, ?>) value).get("demandDept");
    return Boolean.TRUE.equals(marker) || "true".equals(String.valueOf(marker));
  }

  private String findDepartmentItemId(String formKey) {
    for (Map<String, Object> row :
        store.rows("SELECT form_item_id,scheme FROM fm_user_form_item WHERE form_key=?", formKey)) {
      Object raw = row.get("scheme");
      if (raw == null) continue;
      String text = schemeText(raw);
      if (text == null || text.trim().isEmpty()) continue;
      JSONObject scheme =
          raw instanceof JSONObject ? (JSONObject) raw : JSONUtil.parseObj(text);
      JSONObject config = scheme.getJSONObject("config");
      if (config != null && Boolean.TRUE.equals(config.getBool("demandDept")))
        return String.valueOf(row.get("formItemId"));
    }
    return null;
  }

  private void apply(UserFormItemEntity item, DepartmentData data) {
    Map<String, Object> scheme =
        item.getScheme() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(item.getScheme());
    Object rawConfig = scheme.get("config");
    Map<String, Object> config =
        rawConfig instanceof Map
            ? new LinkedHashMap<>((Map<String, Object>) rawConfig)
            : new LinkedHashMap<>();
    List<Map<String, Object>> options = new ArrayList<>();
    for (String label : data.labels) options.add(option(label, label));
    options.add(option(NONE, NONE));
    config.put("label", "填报人部门");
    config.put("optionsType", 0);
    config.put("options", options);
    config.put("defaultValue", data.values);
    config.put("demandDept", true);
    scheme.put("typeId", "SELECT");
    scheme.put("config", config);
    scheme.put("multiple", true);
    scheme.put("disabled", true);
    item.setScheme(scheme);
    item.setType(FormItemTypeEnum.SELECT);
    item.setRequired(true);
    item.setDefaultValue(JSONUtil.toJsonStr(data.values));
  }

  private UserFormItemEntity departmentItem() {
    UserFormItemEntity item = new UserFormItemEntity();
    item.setFormItemId(ITEM_ID);
    item.setType(FormItemTypeEnum.SELECT);
    item.setLabel("填报人部门");
    item.setShowLabel(true);
    item.setDisplayType(false);
    item.setHideType(false);
    item.setSpecialType(false);
    item.setRequired(true);
    item.setPlaceholder("请选择");
    item.setSpan(24);
    item.setSort(0L);
    item.setRegList(new ArrayList<>());
    item.setScheme(departmentScheme());
    return item;
  }

  private Map<String, Object> departmentScheme() {
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("label", "填报人部门");
    config.put("showLabel", true);
    config.put("labelWidth", null);
    config.put("tag", "t-select");
    config.put("tagIcon", "select");
    config.put("mobileTag", "mobile-t-select");
    config.put("span", 24);
    config.put("required", true);
    config.put("regList", new ArrayList<>());
    config.put("changeTag", true);
    config.put("optionsType", 0);
    config.put("options", new ArrayList<>());
    config.put("demandDept", true);
    config.put("formId", ITEM_ID);
    Map<String, Object> scheme = new LinkedHashMap<>();
    scheme.put("typeId", "SELECT");
    scheme.put("config", config);
    scheme.put("placeholder", "请选择");
    scheme.put("style", Collections.singletonMap("width", "100%"));
    scheme.put("clearable", false);
    scheme.put("disabled", false);
    scheme.put("filterable", false);
    scheme.put("multiple", true);
    scheme.put("vModel", ITEM_ID);
    scheme.put("formItemId", ITEM_ID);
    return scheme;
  }

  private DepartmentData departmentData(Long userId) {
    List<Map<String, Object>> departments =
        store.rows(
            "SELECT dept_key,name,parent_key,sort_no FROM rm_demand_dept WHERE active=TRUE ORDER BY sort_no,name");
    Map<String, Map<String, Object>> byKey = new LinkedHashMap<>();
    for (Map<String, Object> department : departments)
      byKey.put(String.valueOf(department.get("deptKey")), department);
    List<String> labels = new ArrayList<>();
    Map<String, String> labelByKey = new LinkedHashMap<>();
    for (Map<String, Object> department : departments) {
      String label = departmentLabel(department, byKey);
      labels.add(label);
      labelByKey.put(String.valueOf(department.get("deptKey")), label);
    }
    Set<String> mine = new LinkedHashSet<>();
    if (userId != null)
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
    return new DepartmentData(labels, values);
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

  private static String schemeText(Object raw) {
    if (raw instanceof java.sql.Clob) {
      try {
        java.sql.Clob clob = (java.sql.Clob) raw;
        return clob.getSubString(1, (int) clob.length());
      } catch (java.sql.SQLException error) {
        throw new BaseException("读取表单项失败");
      }
    }
    return raw.toString();
  }

  private static Map<String, Object> option(String label, String value) {
    Map<String, Object> option = new LinkedHashMap<>();
    option.put("label", label);
    option.put("value", value);
    return option;
  }

  private static final class DepartmentData {
    private final List<String> labels;
    private final List<String> values;

    private DepartmentData(List<String> labels, List<String> values) {
      this.labels = labels;
      this.values = values;
    }
  }
}
