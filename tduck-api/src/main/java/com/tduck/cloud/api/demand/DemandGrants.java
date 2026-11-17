package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import java.util.*;

/** 当前账户的有效授权，用户直授与部门授权取并集。 */
public final class DemandGrants {
  private final Set<String> fill = new LinkedHashSet<>();
  private final Set<String> handle = new LinkedHashSet<>();
  private final Set<String> manage = new LinkedHashSet<>();

  /** 合并一行授权；同一收集单的多个来源按权限并集处理。 */
  void add(String formKey, Object canFill, Object canHandle, Object canManage) {
    if (formKey == null) return;
    if (flag(canFill)) fill.add(formKey);
    if (flag(canHandle)) handle.add(formKey);
    if (flag(canManage)) manage.add(formKey);
  }

  /** 是否可填报指定收集单。 */
  public boolean canFill(String formKey) {
    return fill.contains(formKey);
  }

  /** 是否可经办指定收集单。 */
  public boolean canHandle(String formKey) {
    return handle.contains(formKey);
  }

  /** 是否可管理指定收集单。 */
  public boolean canManage(String formKey) {
    return manage.contains(formKey);
  }

  /** 可填报的收集单。 */
  public Set<String> fillForms() {
    return Collections.unmodifiableSet(fill);
  }

  /** 可经办的收集单。 */
  public Set<String> handleForms() {
    return Collections.unmodifiableSet(handle);
  }

  /** 可管理的收集单。 */
  public Set<String> manageForms() {
    return Collections.unmodifiableSet(manage);
  }

  /** 可经办或可管理的收集单，用于总表范围。 */
  public Set<String> tableForms() {
    Set<String> all = new LinkedHashSet<>(handle);
    all.addAll(manage);
    return all;
  }

  /** 任一权限涉及的收集单，用于公示可见范围。 */
  public Set<String> allForms() {
    Set<String> all = new LinkedHashSet<>(fill);
    all.addAll(handle);
    all.addAll(manage);
    return all;
  }

  /** 是否存在任一经办或管理授权。 */
  public boolean anyHandle() {
    return !handle.isEmpty() || !manage.isEmpty();
  }

  /** 是否存在任一管理授权。 */
  public boolean anyManage() {
    return !manage.isEmpty();
  }
}
