package com.tduck.cloud.api.demand;

import com.tduck.cloud.common.exception.BaseException;
import java.util.Collection;
import java.util.List;

/** 列表、汇总与角标共用的范围条件，避免三处待办口径漂移。 */
final class DemandScope {
  private DemandScope() {}
  /** 按本人、待办、获授权总表或后台管理范围限定。 */
  static void apply(
      String scope,
      Long userId,
      boolean global,
      DemandGrants grants,
      StringBuilder where,
      List<Object> args) {
    if ("mine".equals(scope)) {
      where.append(" AND d.submitter_id=?");
      args.add(userId);
    } else if ("pending".equals(scope)) {
      pending(where, args, userId, grants, global);
    } else if ("table".equals(scope)) {
      if (!global) {
        where.append(" AND ");
        in(where, args, "d.form_key", grants.tableForms());
      }
    } else if ("managed".equals(scope)) {
      if (!global) {
        where.append(" AND (f.user_id=? OR ");
        args.add(userId);
        in(where, args, "f.form_key", grants.manageForms());
        where.append(")");
      }
    } else throw new BaseException("列表类型无效");
  }
  /** 未办结待办、只收集未读与填报者追问三类待办。 */
  static void pending(
      StringBuilder where,
      List<Object> args,
      Long userId,
      DemandGrants grants,
      boolean global) {
    where.append(" AND (");
    unresolved(where, args, userId, grants, global);
    where.append(" OR ");
    collectOnly(where, args, userId, grants, global);
    where.append(" OR ");
    followup(where, args, userId, grants, global);
    where.append(')');
  }
  /** 普通未办结：排除只收集，保留经办/管理/全局范围与公示、跳过条件。 */
  private static void unresolved(
      StringBuilder where,
      List<Object> args,
      Long userId,
      DemandGrants grants,
      boolean global) {
    where.append(NOT_COLLECT_ONLY)
        .append("d.decision<>'REJECTED' AND d.progress<>'COMPLETED' AND (")
        .append("(d.handler_id=? AND ");
    args.add(userId);
    in(where, args, "d.form_key", grants.handleForms());
    where.append(") OR (");
    if (global) {
      where.append("1=1");
    } else {
      in(where, args, "d.form_key", grants.manageForms());
    }
    where.append(
            " AND d.publication_skipped=FALSE AND NOT EXISTS(SELECT 1 FROM rm_demand_publication p WHERE p.demand_id=d.id AND p.published=TRUE AND p.deleted_at IS NULL))")
        .append(')');
  }
  /** 只收集需求：未读才进入待办，已读即移出；不要求采纳或公示。 */
  private static void collectOnly(
      StringBuilder where,
      List<Object> args,
      Long userId,
      DemandGrants grants,
      boolean global) {
    where.append(COLLECT_ONLY)
        .append(
            "NOT EXISTS(SELECT 1 FROM rm_demand_read cr WHERE cr.object_type='DEMAND' AND cr.object_id=d.id AND cr.user_id=? AND cr.seen_version>=d.public_version) AND (")
        .append("(d.handler_id=? AND ");
    args.add(userId);
    args.add(userId);
    in(where, args, "d.form_key", grants.handleForms());
    where.append(") OR (");
    if (global) {
      where.append("1=1");
    } else {
      in(where, args, "d.form_key", grants.manageForms());
    }
    where.append("))");
  }
  /** 追问待办不受办结、公示或跳过限制，排除只收集。 */
  private static void followup(
      StringBuilder where,
      List<Object> args,
      Long userId,
      DemandGrants grants,
      boolean global) {
    where.append(NOT_COLLECT_ONLY)
        .append("d.followup_at IS NOT NULL AND (")
        .append("(d.handler_id=? AND ");
    args.add(userId);
    in(where, args, "d.form_key", grants.handleForms());
    where.append(") OR (");
    if (global) {
      where.append("1=1");
    } else {
      in(where, args, "d.form_key", grants.manageForms());
    }
    where.append("))");
  }
  private static final String COLLECT_ONLY =
      "EXISTS(SELECT 1 FROM rm_demand_form cf WHERE cf.form_key=d.form_key AND cf.collect_only=TRUE) AND ";
  private static final String NOT_COLLECT_ONLY =
      "NOT EXISTS(SELECT 1 FROM rm_demand_form cf WHERE cf.form_key=d.form_key AND cf.collect_only=TRUE) AND ";
  /** 追加收集单 IN 条件；空集合使用恒假条件，保证 SQL 结构完整。 */
  private static void in(
      StringBuilder where, List<Object> args, String column, Collection<String> forms) {
    if (forms.isEmpty()) {
      where.append("1=0");
      return;
    }
    where.append(column).append(" IN (");
    boolean first = true;
    for (String form : forms) {
      if (!first) where.append(',');
      where.append('?');
      args.add(form);
      first = false;
    }
    where.append(')');
  }
}
