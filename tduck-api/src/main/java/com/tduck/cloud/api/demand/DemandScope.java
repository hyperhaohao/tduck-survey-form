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
      pending(where, args, userId, grants);
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
  /** 未办结待办，加上填报者追问后回到经办人与单据管理员的待办。 */
  static void pending(StringBuilder where, List<Object> args, Long userId, DemandGrants grants) {
    where.append(" AND (");
    unresolved(where, args, userId, grants);
    where.append(" OR ");
    followup(where, args, userId, grants);
    where.append(')');
  }
  /** 原待办口径：经办人未办结事项，或单据管理员尚未公示/跳过的未办结事项。 */
  private static void unresolved(
      StringBuilder where, List<Object> args, Long userId, DemandGrants grants) {
    where.append("d.decision<>'REJECTED' AND d.progress<>'COMPLETED' AND (")
        .append("(d.handler_id=? AND ");
    args.add(userId);
    in(where, args, "d.form_key", grants.handleForms());
    where.append(") OR (");
    in(where, args, "d.form_key", grants.manageForms());
    where.append(
            " AND d.publication_skipped=FALSE AND NOT EXISTS(SELECT 1 FROM rm_demand_publication p WHERE p.demand_id=d.id AND p.published=TRUE AND p.deleted_at IS NULL))")
        .append(')');
  }
  /** 追问待办不受办结、公示或跳过限制，只按经办/管理范围过滤。 */
  private static void followup(
      StringBuilder where, List<Object> args, Long userId, DemandGrants grants) {
    where.append("d.followup_at IS NOT NULL AND (")
        .append("(d.handler_id=? AND ");
    args.add(userId);
    in(where, args, "d.form_key", grants.handleForms());
    where.append(") OR (");
    in(where, args, "d.form_key", grants.manageForms());
    where.append("))");
  }
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
