package com.tduck.cloud.api.demand;

import com.tduck.cloud.common.exception.BaseException;
import java.util.List;

/** 列表、汇总与角标共用的范围条件，避免三处待办口径漂移。 */
final class DemandScope {
  private DemandScope() {}
  /** 按本人、待办或获授权总表限定范围。 */
  static void apply(
      String scope, Long userId, boolean admin, StringBuilder where, List<Object> args) {
    if ("mine".equals(scope)) {
      where.append(" AND d.submitter_id=?");
      args.add(userId);
    } else if ("pending".equals(scope)) {
      pending(where, args, userId);
    } else if ("table".equals(scope)) {
      if (!admin) {
        where.append(
            " AND EXISTS(SELECT 1 FROM rm_demand_acl a WHERE a.form_key=d.form_key AND a.user_id=? AND (a.can_handle=TRUE OR a.can_manage=TRUE))");
        args.add(userId);
      }
    } else throw new BaseException("列表类型无效");
  }
  /** 当前经办人的未办结事项，加上单据管理员尚未公示或跳过的未办结事项。 */
  static void pending(StringBuilder where, List<Object> args, Long userId) {
    where.append(" AND d.decision<>'REJECTED' AND d.progress<>'COMPLETED' AND (")
        .append(
            "(d.handler_id=? AND EXISTS(SELECT 1 FROM rm_demand_acl a WHERE a.form_key=d.form_key AND a.user_id=? AND a.can_handle=TRUE))")
        .append(
            " OR (EXISTS(SELECT 1 FROM rm_demand_acl m WHERE m.form_key=d.form_key AND m.user_id=? AND m.can_manage=TRUE)")
        .append(
            " AND d.publication_skipped=FALSE AND NOT EXISTS(SELECT 1 FROM rm_demand_publication p WHERE p.demand_id=d.id AND p.published=TRUE))")
        .append(")");
    args.add(userId);
    args.add(userId);
    args.add(userId);
  }
}
