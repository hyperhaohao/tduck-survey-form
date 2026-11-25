package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.form.service.UserFormItemService;
import com.tduck.cloud.form.vo.FormFieldVO;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 需求列表、分派、回复、采纳与实施进度服务。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandWorkflow {
  private final DemandStore store;
  private final DemandAccess access;
  private final UserFormItemService fields;
  private final DemandFormFields formFields;
  private final DemandNotices notices;
  private final DemandApproval approval;
  private static final String FROM =
      " FROM rm_demand d JOIN fm_user_form f ON f.form_key=d.form_key";
  private static final String ROW_SELECT =
      "SELECT d.id,d.form_key,d.request_no,d.title,d.submitter_name,d.submitted_by,d.handler_id,d.decision,d.progress,d.reason,d.completion,d.create_time,d.update_time,d.public_version,d.publication_skipped,d.followup_at AS followup_at,d.approval_status,d.revision,c.collect_only,f.name AS form_name,h.name AS handler_name,pub.published AS publication_published,pub.archived_at AS publication_archived_at,pub.deleted_at AS publication_deleted_at,pub.version AS publication_version,(SELECT MAX(m.create_time) FROM rm_demand_message m WHERE m.demand_id=d.id AND m.kind='REPLY') AS reply_time,CASE WHEN c.collect_only=TRUE THEN CASE WHEN r.seen_version IS NULL OR r.seen_version<d.public_version THEN TRUE ELSE FALSE END ELSE d.public_version>COALESCE(r.seen_version,1) END AS unread"
          + FROM
          + " LEFT JOIN rm_demand_form c ON c.form_key=d.form_key LEFT JOIN ac_user h ON h.id=d.handler_id LEFT JOIN rm_demand_publication pub ON pub.demand_id=d.id LEFT JOIN rm_demand_read r ON r.object_type='DEMAND' AND r.object_id=d.id AND r.user_id=?";
  /** 分页查询，按权限过滤后再统计数量。 */
  public Map<String, Object> list(
      Long userId,
      String scope,
      String keyword,
      String decision,
      String progress,
      String formKey,
      String handlerId,
      String submitStart,
      String submitEnd,
      int page,
      int size) {
    access.requireEnabled();
    if (userId == null) throw new BaseException("请先登录", 401);
    Query query =
        query(userId, scope, keyword, decision, progress, formKey, handlerId, submitStart, submitEnd);
    long total = store.count("SELECT COUNT(*)" + FROM + query.where, query.args.toArray());
    int limit = Math.min(100, Math.max(1, size));
    int current = Math.max(1, page);
    List<Object> rowArgs = new ArrayList<>();
    rowArgs.add(userId);
    rowArgs.addAll(query.args);
    rowArgs.add(limit);
    rowArgs.add((current - 1) * limit);
    List<Map<String, Object>> rows =
        store.rows(
            ROW_SELECT + query.where + " ORDER BY d.create_time DESC,d.id LIMIT ? OFFSET ?",
            rowArgs.toArray());
    decorate(rows, userId, scope);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rows", rows);
    result.put("total", total);
    return result;
  }
  /** 导出复用同一权限与筛选条件，最多读取上限加一行用于判断截断。 */
  public Map<String, Object> exportRows(
      Long userId,
      String scope,
      String keyword,
      String decision,
      String progress,
      String formKey,
      String handlerId,
      String submitStart,
      String submitEnd,
      int maxRows) {
    access.requireEnabled();
    if (userId == null) throw new BaseException("请先登录", 401);
    Query query =
        query(userId, scope, keyword, decision, progress, formKey, handlerId, submitStart, submitEnd);
    List<Object> rowArgs = new ArrayList<>();
    rowArgs.add(userId);
    rowArgs.addAll(query.args);
    rowArgs.add(Math.max(1, maxRows) + 1);
    List<Map<String, Object>> rows =
        store.rows(
            ROW_SELECT + query.where + " ORDER BY d.create_time DESC,d.id LIMIT ?",
            rowArgs.toArray());
    boolean truncated = rows.size() > maxRows;
    if (truncated) rows = new ArrayList<>(rows.subList(0, maxRows));
    decorate(rows, userId, scope);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rows", rows);
    result.put("truncated", truncated);
    return result;
  }
  /** 导出时批量读取原表单答案与部门快照，避免列表接口返回原始答案。 */
  public Map<String, Map<String, Object>> exportAnswers(List<String> demandIds) {
    Map<String, Map<String, Object>> result = new LinkedHashMap<>();
    if (demandIds == null || demandIds.isEmpty()) return result;
    List<String> ids = new ArrayList<>(new LinkedHashSet<>(demandIds));
    for (int start = 0; start < ids.size(); start += 500) {
      List<String> chunk = ids.subList(start, Math.min(ids.size(), start + 500));
      StringBuilder sql =
          new StringBuilder(
              "SELECT d.id,d.department,d.anonymous,x.original_data FROM rm_demand d "
                  + "LEFT JOIN fm_user_form_data x ON x.id=CAST(d.data_id AS DECIMAL(20,0)) WHERE d.id IN (");
      List<Object> args = new ArrayList<>();
      for (int i = 0; i < chunk.size(); i++) {
        if (i > 0) sql.append(',');
        sql.append('?');
        args.add(chunk.get(i));
      }
      sql.append(')');
      for (Map<String, Object> row : store.rows(sql.toString(), args.toArray())) {
        Map<String, Object> original;
        try {
          original = store.json(row.get("originalData"));
        } catch (Exception error) {
          log.warn("导出答案解析失败 demandId={}", row.get("id"));
          original = new LinkedHashMap<>();
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("originalData", original);
        data.put(
            "department",
            formFields.resolve(row.get("department"), original, flag(row.get("anonymous"))));
        result.put(String.valueOf(row.get("id")), data);
      }
    }
    return result;
  }
  /** 导出使用的表单字段元数据。 */
  public List<FormFieldVO> formFields(String formKey) {
    return fields.listFormFields(formKey);
  }
  /** 列表与导出共用的查询条件。 */
  private Query query(
      Long userId,
      String scope,
      String keyword,
      String decision,
      String progress,
      String formKey,
      String handlerId,
      String submitStart,
      String submitEnd) {
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE f.is_deleted=FALSE ");
    if ("managed".equals(scope)) access.requireDocumentManager(userId);
    DemandScope.apply(scope, userId, access.globalAdmin(userId), access.effectiveGrants(userId), where, args);
    if (keyword != null && !keyword.isEmpty()) {
      where.append(" AND (d.title LIKE ? OR d.request_no LIKE ? OR f.name LIKE ?)");
      for (int i = 0; i < 3; i++) args.add("%" + keyword + "%");
    }
    for (String[] filter :
        new String[][] {
          {"d.decision", decision},
          {"d.progress", progress},
          {"d.form_key", formKey},
          {"d.handler_id", handlerId}
        }) {
      if (filter[1] != null && !filter[1].isEmpty()) {
        where.append(" AND ").append(filter[0]).append("=?");
        args.add(filter[1]);
      }
    }
    if (submitStart != null && !submitStart.isEmpty()) {
      where.append(" AND d.create_time>=?");
      args.add(submitStart + " 00:00:00");
    }
    if (submitEnd != null && !submitEnd.isEmpty()) {
      where.append(" AND d.create_time<=?");
      args.add(submitEnd + " 23:59:59");
    }
    return new Query(where.toString(), args);
  }
  /** 查询条件与参数。 */
  private static final class Query {
    final String where;
    final List<Object> args;
    Query(String where, List<Object> args) {
      this.where = where;
      this.args = args;
    }
  }
  /** 为每行补充当前账户的处理能力，避免前端逐行判断。 */
  private void decorate(List<Map<String, Object>> rows, Long userId, String scope) {
    if (rows.isEmpty()) return;
    boolean global = access.globalAdmin(userId);
    DemandGrants grants = access.effectiveGrants(userId);
    for (Map<String, Object> row : rows) {
      String key = String.valueOf(row.get("formKey"));
      boolean collectOnly = flag(row.get("collectOnly"));
      boolean staff = global || grants.canHandle(key) || grants.canManage(key);
      row.put("collectOnly", collectOnly);
      if (collectOnly && ("mine".equals(scope) || !staff)) row.put("unread", false);
      row.put("canHandle", !collectOnly && grants.canHandle(key));
      row.put("canManage", global || grants.canManage(key));
      row.put("followupPending", !collectOnly && row.remove("followupAt") != null);
      Object archived = row.remove("publicationArchivedAt");
      Object deleted = row.remove("publicationDeletedAt");
      row.put(
          "publicationPublished",
          !collectOnly
              && flag(row.get("publicationPublished"))
              && archived == null
              && deleted == null);
      row.put("publicationArchived", !collectOnly && archived != null && deleted == null);
      row.put("publicationDeleted", !collectOnly && deleted != null);
    }
  }
  /** 按收集单汇总当前范围的需求数量，供页面先选收集单再下钻。 */
  public List<Map<String, Object>> summary(Long userId, String scope) {
    access.requireEnabled();
    if (userId == null) throw new BaseException("请先登录", 401);
    if ("managed".equals(scope)) access.requireDocumentManager(userId);
    boolean global = access.globalAdmin(userId);
    DemandGrants grants = access.effectiveGrants(userId);
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE f.is_deleted=FALSE ");
    DemandScope.apply(scope, userId, global, grants, where, args);
    List<Object> rowArgs = new ArrayList<>();
    rowArgs.add(userId);
    rowArgs.addAll(args);
    String pendingExpression;
    if ("pending".equals(scope) || "approval".equals(scope)) pendingExpression = "COUNT(*)";
    else if ("mine".equals(scope))
      pendingExpression =
          "SUM(CASE WHEN (c.collect_only IS NULL OR c.collect_only=FALSE) AND d.decision<>'REJECTED' AND d.progress<>'COMPLETED' THEN 1 ELSE 0 END)";
    else
      pendingExpression =
          "SUM(CASE WHEN c.collect_only=TRUE THEN CASE WHEN d.public_version>COALESCE(r.seen_version,1) THEN 1 ELSE 0 END ELSE CASE WHEN (d.decision<>'REJECTED' AND d.progress<>'COMPLETED') OR d.followup_at IS NOT NULL THEN 1 ELSE 0 END END)";
    String unreadExpression =
        "approval".equals(scope)
            ? "0"
            : "mine".equals(scope)
                ? "SUM(CASE WHEN d.public_version>COALESCE(r.seen_version,1) THEN 1 ELSE 0 END)"
                : "SUM(CASE WHEN c.collect_only=TRUE THEN CASE WHEN r.seen_version IS NULL OR r.seen_version<d.public_version THEN 1 ELSE 0 END ELSE CASE WHEN d.public_version>COALESCE(r.seen_version,1) THEN 1 ELSE 0 END END)";
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT d.form_key,f.name AS form_name,COUNT(*) AS total,"
                + unreadExpression
                + " AS unread,"
                + "MAX(CASE WHEN c.collect_only=TRUE THEN 1 ELSE 0 END) AS collect_only,"
                + pendingExpression
                + " AS pending,"
                + "MAX(d.update_time) AS last_update"
                + " FROM rm_demand d JOIN fm_user_form f ON f.form_key=d.form_key"
                + " LEFT JOIN rm_demand_form c ON c.form_key=d.form_key"
                + " LEFT JOIN rm_demand_read r ON r.object_type='DEMAND' AND r.object_id=d.id AND r.user_id=?"
                + where
                + " GROUP BY d.form_key,f.name ORDER BY last_update DESC,d.form_key",
            rowArgs.toArray());
    for (Map<String, Object> row : rows) {
      row.put("collectOnly", flag(row.get("collectOnly")));
      row.put("canManage", global || grants.canManage(String.valueOf(row.get("formKey"))));
    }
    return rows;
  }
  /** 读取需求，查询码、本人身份及总表权限严格隔离。 */
  public Map<String, Object> demand(String demandId, Long userId, String queryCode, boolean lock) {
    access.requireEnabled();
    Map<String, Object> d =
        store.one("SELECT * FROM rm_demand WHERE id=?" + (lock ? " FOR UPDATE" : ""), demandId);
    if (d == null) throw new BaseException("需求不存在或无权访问", 403);
    boolean staff = access.table(d.get("formKey").toString(), userId);
    boolean own = userId != null && userId.equals(number(d.get("submitterId")));
    boolean anonymous = flag(d.get("anonymous")) && matches((String) d.get("queryHash"), queryCode);
    boolean approver =
        userId != null && userId.equals(number(d.get("approvalLeaderId")));
    if (!staff && !own && !anonymous && !approver)
      throw new BaseException("需求不存在或无权访问", 403);
    return d;
  }
  /** 返回详情，原表单数据只读取一次正本，内部备注与草稿独立过滤。 */
  public Map<String, Object> detail(String demandId, Long userId, String queryCode) {
    Map<String, Object> d = new LinkedHashMap<>(demand(demandId, userId, queryCode, false));
    String formKey = d.get("formKey").toString();
    Map<String, Object> config = access.form(formKey);
    boolean collectOnly = config != null && flag(config.get("collectOnly"));
    d.put("collectOnly", collectOnly);
    boolean staff = access.table(formKey, userId);
    boolean manage = access.manage(formKey, userId);
    Long submitterId = number(d.get("submitterId"));
    boolean anonymous = flag(d.get("anonymous"));
    Map<String, Object> raw =
        store.one("SELECT original_data FROM fm_user_form_data WHERE id=?", d.get("dataId"));
    if (raw == null) throw new BaseException("原始需求记录已不存在");
    d.remove("queryHash");
    d.remove("submitterId");
    if (!staff) {
      d.remove("submittedBy");
      d.remove("handlerId");
      d.remove("dataId");
    }
    Map<String, Object> original = new LinkedHashMap<>(store.json(raw.get("originalData")));
    original.remove("demandDept");
    original.remove("demandDeptlabel");
    d.put("department", formFields.resolve(d.get("department"), original, anonymous));
    d.put("originalData", original);
    List<FormFieldVO> fieldList = new ArrayList<>(fields.listFormFields(formKey));
    fieldList.removeIf(formFields::isDepartmentField);
    d.put("formFields", fieldList);
    String approvalStatus = String.valueOf(d.getOrDefault("approvalStatus", "NONE"));
    d.put("approvalStatus", approvalStatus);
    Long approvalLeaderId = number(d.get("approvalLeaderId"));
    d.put("approvalLeaderName", approvalLeaderId == null ? null : access.name(approvalLeaderId));
    d.put("canApprove", approval.canApprove(d, userId));
    boolean approvalBlocked = "PENDING".equals(approvalStatus) || "REJECTED".equals(approvalStatus);
    d.put("canViewTable", staff);
    d.put("canManage", manage);
    d.put(
        "canHandle",
        !collectOnly
            && !approvalBlocked
            && (manage
                || (userId != null
                    && userId.equals(number(d.get("handlerId")))
                    && access.handler(formKey, userId))));
    d.put("canAssign", manage && !collectOnly && !approvalBlocked);
    d.put("canPublish", manage && !collectOnly && !approvalBlocked);
    String messageSql =
        "SELECT id,author_id,author_name,kind,content,create_time FROM rm_demand_message WHERE demand_id=? AND ";
    if (staff)
      d.put(
          "messages",
          store.rows(
              messageSql + "(kind<>'DRAFT' OR author_id=?) ORDER BY create_time,id",
              demandId,
              userId));
    else
      d.put(
          "messages",
          store.rows(
              messageSql + "kind IN ('REPLY','EVENT','FOLLOWUP') ORDER BY create_time,id",
              demandId));
    boolean collectionActive =
        store.count(
                "SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=FALSE", formKey)
            > 0;
    d.put(
        "canFollowUp",
        !collectOnly
            && !approvalBlocked
            && userId != null
            && !anonymous
            && userId.equals(submitterId)
            && collectionActive);
    d.put("followupPending", !collectOnly && d.get("followupAt") != null);
    Map<String, Object> publication =
        manage && !collectOnly
            ? store.one("SELECT * FROM rm_demand_publication WHERE demand_id=?", demandId)
            : null;
    if (publication != null) {
      boolean deleted = publication.get("deletedAt") != null;
      boolean archived = publication.get("archivedAt") != null;
      publication.put("archived", archived && !deleted);
      publication.put("deleted", deleted);
    }
    d.put("publication", publication);
    return d;
  }
  /** 单据管理员或全局管理员删除需求；原答卷保留，消息、已读和公示一并清理。 */
  @Transactional
  public void deleteDemand(String id, Long revision, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    String formKey = d.get("formKey").toString();
    access.requireManage(formKey, userId);
    checkRevision(d, revision);
    // 统一锁顺序：先需求，后公示。
    store.rows("SELECT id FROM rm_demand_publication WHERE demand_id=? FOR UPDATE", id);
    store.update(
        "DELETE FROM rm_demand_read WHERE object_type='PUBLICATION' AND object_id IN (SELECT id FROM rm_demand_publication WHERE demand_id=?)",
        id);
    store.update("DELETE FROM rm_demand_publication WHERE demand_id=?", id);
    store.update("DELETE FROM rm_demand_message WHERE demand_id=?", id);
    store.update("DELETE FROM rm_demand_read WHERE object_type='DEMAND' AND object_id=?", id);
    notices.deleteForDemand(id);
    store.update("DELETE FROM rm_demand WHERE id=?", id);
    log.info("删除需求 id={} formKey={} operator={}", id, formKey, userId);
  }
  /** 单据管理员或全局管理员分派给已授权经办人，支持并发版本校验。 */
  @Transactional
  public void assign(String id, Long handlerId, long revision, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    requireWorkflow(d.get("formKey").toString());
    approval.requireApproved(d);
    checkRevision(d, revision);
    if (handlerId != null && !access.handler(d.get("formKey").toString(), handlerId))
      throw new BaseException("请选择该收集单已授权的经办人");
    Long previous = number(d.get("handlerId"));
    store.update(
        "UPDATE rm_demand SET handler_id=?,revision=revision+1,public_version=public_version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
        handlerId,
        id);
    message(id, userId, "AUDIT", handlerId == null ? "取消经办分派" : "分派经办人：" + access.name(handlerId));
    if (handlerId != null && !handlerId.equals(previous)) notices.assign(d, handlerId, userId);
  }
  /** 经办人或单据管理员发布采纳结果及实施进度。 */
  @Transactional
  public void outcome(String id, Map<String, Object> body, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireHandle(d, userId);
    requireWorkflow(d.get("formKey").toString());
    approval.requireApproved(d);
    checkRevision(d, number(body.get("revision")));
    String decision = String.valueOf(body.get("decision"));
    String progress = String.valueOf(body.get("progress"));
    if (!Arrays.asList("ACCEPTED", "DEFERRED", "REJECTED").contains(decision))
      throw new BaseException("请选择采纳结果（采纳/暂缓/不采纳）");
    String reason = body.get("reason") == null ? "" : body.get("reason").toString().trim();
    String completion =
        body.get("completion") == null ? "" : body.get("completion").toString().trim();
    if (reason.length() > 10000 || completion.length() > 10000)
      throw new BaseException("说明不能超过10000字");
    if (reason.isEmpty()) throw new BaseException("请填写采纳结果说明");
    if ("ACCEPTED".equals(decision)) {
      if (!Arrays.asList("PLANNED", "IN_PROGRESS", "COMPLETED").contains(progress))
        throw new BaseException("请选择实施进度");
      if ("COMPLETED".equals(progress) && completion.isEmpty())
        throw new BaseException("完成时必须填写完成说明");
    } else {
      progress = "NONE";
      completion = "";
    }
    store.update(
        "UPDATE rm_demand SET decision=?,progress=?,reason=?,completion=?,revision=revision+1,public_version=public_version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
        decision,
        progress,
        reason,
        completion,
        id);
    message(
        id,
        userId,
        "EVENT",
        "更新处理结果："
            + decisionLabel(decision)
            + ("ACCEPTED".equals(decision) ? "，" + progressLabel(progress) : ""));
    notices.result(
        d,
        decisionLabel(decision) + ("ACCEPTED".equals(decision) ? "，" + progressLabel(progress) : ""));
  }
  /** 追加回复、内部备注或本人草稿，经办人或单据管理员均可操作。 */
  @Transactional
  public void reply(String id, String kind, String content, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireHandle(d, userId);
    requireWorkflow(d.get("formKey").toString());
    approval.requireApproved(d);
    if (!Arrays.asList("REPLY", "NOTE", "DRAFT").contains(kind)) throw new BaseException("回复类型无效");
    content = text(content, 10000);
    if ("DRAFT".equals(kind))
      store.update(
          "DELETE FROM rm_demand_message WHERE demand_id=? AND author_id=? AND kind='DRAFT'",
          id,
          userId);
    else if ("REPLY".equals(kind)) {
      store.update(
          "DELETE FROM rm_demand_message WHERE demand_id=? AND author_id=? AND kind='DRAFT'",
          id,
          userId);
      store.update(
          "UPDATE rm_demand SET public_version=public_version+1,followup_at=NULL,update_time=CURRENT_TIMESTAMP WHERE id=?",
          id);
    }
    message(id, userId, kind, content);
    if ("REPLY".equals(kind)) notices.reply(d, content);
  }
  /** 填报者追问本人需求，重新进入经办人与单据管理员的待办，不增加填报者未读。 */
  @Transactional
  public void followUp(String id, String content, Long userId) {
    access.requireEnabled();
    Map<String, Object> d =
        store.one(
            "SELECT id,form_key,submitter_id,anonymous,handler_id,title FROM rm_demand WHERE id=? FOR UPDATE",
            id);
    if (d == null) throw new BaseException("需求不存在或无权访问", 403);
    if (userId == null
        || !userId.equals(number(d.get("submitterId")))
        || flag(d.get("anonymous")))
      throw new BaseException("仅填报人本人可追问", 403);
    if (store.count(
            "SELECT COUNT(*) FROM fm_user_form WHERE form_key=? AND is_deleted=FALSE",
            d.get("formKey"))
        == 0) throw new BaseException("该收集单已删除", 403);
    requireWorkflow(d.get("formKey").toString());
    approval.requireApproved(d);
    content = text(content, 10000);
    store.update(
        "UPDATE rm_demand SET followup_at=CURRENT_TIMESTAMP,update_time=CURRENT_TIMESTAMP WHERE id=?",
        id);
    message(id, userId, "FOLLOWUP", content);
    notices.followUp(d, content);
  }
  /** 按客户端实际读到的版本标记已读，防止并发更新被错误清除。 */
  @Transactional
  public void read(String type, String id, long version, Long userId) {
    if (userId == null) throw new BaseException("请先登录", 401);
    long current;
    if ("DEMAND".equals(type)) {
      Map<String, Object> d = demand(id, userId, null, false);
      current = number(d.get("publicVersion"));
    } else if ("PUBLICATION".equals(type)) {
      current = number(publication(id, userId).get("version"));
    } else throw new BaseException("已读类型无效");
    long seen = Math.max(0, Math.min(version, current));
    if (store.update(
            "UPDATE rm_demand_read SET seen_version=GREATEST(seen_version,?) WHERE user_id=? AND object_type=? AND object_id=?",
            seen,
            userId,
            type,
            id)
        == 0) {
      try {
        store.update(
            "INSERT INTO rm_demand_read(user_id,object_type,object_id,seen_version) VALUES(?,?,?,?)",
            userId,
            type,
            id,
            seen);
      } catch (DuplicateKeyException e) {
        store.update(
            "UPDATE rm_demand_read SET seen_version=GREATEST(seen_version,?) WHERE user_id=? AND object_type=? AND object_id=?",
            seen,
            userId,
            type,
            id);
      }
    }
    notices.readForObject(userId, type, id);
  }
  /** 公示列表仅查询已发布的人工选定内容，不拼接原始表单正文。 */
  public List<Map<String, Object>> publications(Long userId, boolean includeArchived) {
    access.requireEnabled();
    if (userId == null) throw new BaseException("请先登录", 401);
    boolean global = access.globalAdmin(userId);
    DemandGrants grants = access.effectiveGrants(userId);
    StringBuilder sql =
        new StringBuilder(
            "SELECT p.id,p.title,p.summary,p.decision,p.progress,p.version,p.update_time,p.archived_at,d.form_key,d.request_no,d.submitter_name,d.create_time,u.name AS form_name,h.name AS handler_name,CASE WHEN p.version>COALESCE(r.seen_version,0) THEN TRUE ELSE FALSE END AS unread FROM rm_demand_publication p JOIN rm_demand d ON d.id=p.demand_id JOIN rm_demand_form f ON f.form_key=d.form_key JOIN fm_user_form u ON u.form_key=d.form_key AND u.is_deleted=FALSE LEFT JOIN ac_user h ON h.id=d.handler_id LEFT JOIN rm_demand_read r ON r.object_type='PUBLICATION' AND r.object_id=p.id AND r.user_id=? WHERE p.published=TRUE AND p.deleted_at IS NULL");
    List<Object> args = new ArrayList<>();
    args.add(userId);
    if (!includeArchived) {
      sql.append(" AND p.archived_at IS NULL");
    } else if (!global) {
      sql.append(" AND (p.archived_at IS NULL");
      Set<String> managed = grants.manageForms();
      if (!managed.isEmpty()) {
        sql.append(" OR d.form_key IN (");
        boolean first = true;
        for (String key : managed) {
          if (!first) sql.append(',');
          sql.append('?');
          args.add(key);
          first = false;
        }
        sql.append(')');
      }
      sql.append(')');
    }
    sql.append(" AND (f.visibility='LOGIN'");
    if (!global && !grants.allForms().isEmpty()) {
      sql.append(" OR d.form_key IN (");
      boolean first = true;
      for (String key : grants.allForms()) {
        if (!first) sql.append(',');
        sql.append('?');
        args.add(key);
        first = false;
      }
      sql.append(')');
    }
    sql.append(" OR ?=TRUE) ORDER BY p.update_time DESC");
    args.add(global);
    List<Map<String, Object>> rows = store.rows(sql.toString(), args.toArray());
    for (Map<String, Object> row : rows) {
      String key = String.valueOf(row.get("formKey"));
      row.put("canManage", global || grants.canManage(key));
      row.put("archived", row.remove("archivedAt") != null);
    }
    return rows;
  }
  /** 返回公示快照，仅包含人工审核后的字段。 */
  public Map<String, Object> publication(String id, Long userId) {
    if (userId == null) throw new BaseException("请先登录", 401);
    Map<String, Object> p =
        store.one(
            "SELECT p.id,p.title,p.summary,p.content,p.decision,p.progress,p.version,p.update_time,p.archived_at,d.form_key,u.name AS form_name FROM rm_demand_publication p JOIN rm_demand d ON d.id=p.demand_id JOIN fm_user_form u ON u.form_key=d.form_key AND u.is_deleted=FALSE WHERE p.id=? AND p.published=TRUE AND p.deleted_at IS NULL",
            id);
    if (p == null) throw new BaseException("公示不存在或已删除", 403);
    String formKey = p.get("formKey").toString();
    boolean manage = access.manage(formKey, userId);
    boolean archived = p.remove("archivedAt") != null;
    if (archived && !manage) throw new BaseException("公示已归档", 403);
    Map<String, Object> form = access.form(formKey);
    if (form == null || (!access.visible(form, userId) && !access.table(formKey, userId)))
      throw new BaseException("无公示阅读权限", 403);
    p.remove("formKey");
    p.put("archived", archived);
    p.put("canManage", manage);
    return p;
  }
  /** 单据管理员显式发布或撤回，更新版本后重新触发未读提示。 */
  @Transactional
  public void publish(String id, Map<String, Object> body, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    approval.requireApproved(d);
    Map<String, Object> old =
        store.one("SELECT * FROM rm_demand_publication WHERE demand_id=? FOR UPDATE", id);
    long expected = number(body.getOrDefault("version", 0));
    if (old != null && expected != number(old.get("version")))
      throw new BaseException("公示已变化，请刷新后重试", 409);
    boolean publish = flag(body.get("published"));
    if (publish) requireWorkflow(d.get("formKey").toString());
    if (!publish) {
      if (old != null) {
        store.update(
            "UPDATE rm_demand_publication SET published=FALSE,version=version+1,update_time=CURRENT_TIMESTAMP WHERE demand_id=?",
            id);
        notices.deleteForPublication(String.valueOf(old.get("id")));
      }
      message(id, userId, "AUDIT", "撤回公示");
      return;
    }
    requireDecision(d);
    String title = text(body.get("title"), 200),
        summary = text(body.get("summary"), 2000),
        content = text(body.get("content"), 20000);
    if (old == null)
      store.update(
          "INSERT INTO rm_demand_publication(id,demand_id,title,summary,content,published,version,decision,progress) VALUES(?,?,?,?,?,TRUE,1,?,?)",
          id(),
          id,
          title,
          summary,
          content,
          d.get("decision"),
          d.get("progress"));
    else
      store.update(
          "UPDATE rm_demand_publication SET title=?,summary=?,content=?,published=TRUE,archived_at=NULL,archived_by=NULL,deleted_at=NULL,deleted_by=NULL,version=version+1,decision=?,progress=?,update_time=CURRENT_TIMESTAMP WHERE demand_id=?",
          title,
          summary,
          content,
          d.get("decision"),
          d.get("progress"),
          id);
    store.update("UPDATE rm_demand SET publication_skipped=FALSE WHERE id=?", id);
    message(id, userId, "AUDIT", "审核并发布公示");
    publishNotice(id, d, userId);
  }
  /** 按需求标题、采纳说明和最近正式回复生成公示；已发布时幂等跳过。 */
  @Transactional
  public Map<String, Object> autoPublish(String id, Long revision, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    requireWorkflow(d.get("formKey").toString());
    approval.requireApproved(d);
    checkRevision(d, revision);
    Map<String, Object> old =
        store.one("SELECT * FROM rm_demand_publication WHERE demand_id=? FOR UPDATE", id);
    Map<String, Object> result = new LinkedHashMap<>();
    if (old != null && flag(old.get("published")) && old.get("deletedAt") == null) {
      result.put("published", true);
      result.put("unchanged", true);
      return result;
    }
    requireDecision(d);
    String title = clip(str(d.get("title")), 200);
    if (title.isEmpty()) title = "需求公示";
    String reason = str(d.get("reason")), completion = str(d.get("completion"));
    Map<String, Object> latest =
        store.one(
            "SELECT content FROM rm_demand_message WHERE demand_id=? AND kind='REPLY' ORDER BY create_time DESC,id DESC LIMIT 1",
            id);
    String content = clip(latest == null ? "" : str(latest.get("content")), 20000);
    String summary = clip(reason.isEmpty() ? completion : reason, 2000);
    if (content.isEmpty()) content = summary;
    if (summary.isEmpty()) summary = content;
    if (summary.isEmpty() || content.isEmpty())
      throw new BaseException("缺少采纳说明，无法自动生成公示");
    if (old == null)
      store.update(
          "INSERT INTO rm_demand_publication(id,demand_id,title,summary,content,published,version,decision,progress) VALUES(?,?,?,?,?,TRUE,1,?,?)",
          id(),
          id,
          title,
          summary,
          content,
          d.get("decision"),
          d.get("progress"));
    else
      store.update(
          "UPDATE rm_demand_publication SET title=?,summary=?,content=?,published=TRUE,archived_at=NULL,archived_by=NULL,deleted_at=NULL,deleted_by=NULL,version=version+1,decision=?,progress=?,update_time=CURRENT_TIMESTAMP WHERE demand_id=?",
          title,
          summary,
          content,
          d.get("decision"),
          d.get("progress"),
          id);
    store.update(
        "UPDATE rm_demand SET publication_skipped=FALSE,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
        id);
    message(id, userId, "AUDIT", "自动生成并发布公示");
    publishNotice(id, d, userId);
    result.put("published", true);
    result.put("unchanged", false);
    return result;
  }
  /** 记录不公示并移出待办；已公示需先撤回，不增加填报者未读版本。 */
  @Transactional
  public Map<String, Object> skipPublication(String id, Long revision, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    requireWorkflow(d.get("formKey").toString());
    approval.requireApproved(d);
    Map<String, Object> publication =
        store.one(
            "SELECT published,deleted_at FROM rm_demand_publication WHERE demand_id=? FOR UPDATE",
            id);
    if (publication != null
        && flag(publication.get("published"))
        && publication.get("deletedAt") == null)
      throw new BaseException("已公示需求不能跳过，请先撤回公示", 409);
    Map<String, Object> result = new LinkedHashMap<>();
    if (flag(d.get("publicationSkipped"))) {
      result.put("changed", false);
      result.put("skipped", true);
      return result;
    }
    checkRevision(d, revision);
    store.update(
        "UPDATE rm_demand SET publication_skipped=TRUE,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
        id);
    message(id, userId, "AUDIT", "选择不公示");
    result.put("changed", true);
    result.put("skipped", true);
    return result;
  }

  /** 单据管理员归档或恢复公示；归档后员工端不再展示，可恢复。 */
  @Transactional
  public void archivePublication(String publicationId, Map<String, Object> body, Long userId) {
    Map<String, Object> peek =
        store.one("SELECT demand_id FROM rm_demand_publication WHERE id=?", publicationId);
    if (peek == null) throw new BaseException("公示不存在", 404);
    String demandId = peek.get("demandId").toString();
    Map<String, Object> d = demand(demandId, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    Map<String, Object> old =
        store.one(
            "SELECT demand_id,version,published,deleted_at FROM rm_demand_publication WHERE id=? FOR UPDATE",
            publicationId);
    if (old == null) throw new BaseException("公示不存在", 404);
    if (old.get("deletedAt") != null) throw new BaseException("公示已删除", 409);
    if (!flag(old.get("published"))) throw new BaseException("公示未发布", 409);
    long expected = number(body.getOrDefault("version", 0));
    if (expected != number(old.get("version")))
      throw new BaseException("公示已变化，请刷新后重试", 409);
    boolean archived = flag(body.get("archived"));
    if (archived)
      store.update(
          "UPDATE rm_demand_publication SET archived_at=CURRENT_TIMESTAMP,archived_by=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
          userId,
          publicationId);
    else
      store.update(
          "UPDATE rm_demand_publication SET archived_at=NULL,archived_by=NULL,version=version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
          publicationId);
    message(demandId, userId, "AUDIT", archived ? "归档公示" : "恢复公示");
    if (archived) notices.deleteForPublication(publicationId);
  }

  /** 单据管理员删除公示；保留记录与审计，需求可重新发布或跳过。 */
  @Transactional
  public void deletePublication(String publicationId, Map<String, Object> body, Long userId) {
    Map<String, Object> peek =
        store.one("SELECT demand_id FROM rm_demand_publication WHERE id=?", publicationId);
    if (peek == null) throw new BaseException("公示不存在", 404);
    String demandId = peek.get("demandId").toString();
    Map<String, Object> d = demand(demandId, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    Map<String, Object> old =
        store.one(
            "SELECT demand_id,version,published,deleted_at FROM rm_demand_publication WHERE id=? FOR UPDATE",
            publicationId);
    if (old == null) throw new BaseException("公示不存在", 404);
    if (old.get("deletedAt") != null) return;
    if (!flag(old.get("published"))) throw new BaseException("公示未发布", 409);
    long expected = number(body.getOrDefault("version", 0));
    if (expected != number(old.get("version")))
      throw new BaseException("公示已变化，请刷新后重试", 409);
    store.update(
        "UPDATE rm_demand_publication SET deleted_at=CURRENT_TIMESTAMP,deleted_by=?,version=version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
        userId,
        publicationId);
    message(demandId, userId, "AUDIT", "删除公示");
    notices.deleteForPublication(publicationId);
  }

  /** 验证附件属于该原始需求，不接受任意 URL 下载。 */
  public String attachment(String id, String url, Long userId, String queryCode) {
    Map<String, Object> d = demand(id, userId, queryCode, false);
    Map<String, Object> raw =
        store.one("SELECT original_data FROM fm_user_form_data WHERE id=?", d.get("dataId"));
    if (raw == null || !containsUrl(store.json(raw.get("originalData")), url))
      throw new BaseException("无附件访问权限", 403);
    return url;
  }
  /** 递归精确匹配表单附件 URL，不能用子串判断。 */
  private boolean containsUrl(Object value, String url) {
    if (value instanceof Map) {
      Map<?, ?> m = (Map<?, ?>) value;
      if (url.equals(m.get("url"))) return true;
      for (Object child : m.values()) if (containsUrl(child, url)) return true;
    } else if (value instanceof Collection) {
      for (Object child : (Collection<?>) value) if (containsUrl(child, url)) return true;
    }
    return false;
  }
  /** 只收集收集单不允许进入回复、采纳或公示流程。 */
  private void requireWorkflow(String formKey) {
    Map<String, Object> config = access.form(formKey);
    if (config != null && flag(config.get("collectOnly")))
      throw new BaseException("该收集单仅收集信息，无需回复或公示", 409);
  }
  /** 公示前必须有明确的采纳结果，正式回复不是前置条件。 */
  private void requireDecision(Map<String, Object> demand) {
    Object decision = demand.get("decision");
    if (decision == null || "PENDING".equals(String.valueOf(decision)))
      throw new BaseException("公示前必须先发布明确的采纳结果（采纳/暂缓/不采纳）", 409);
  }
  /** 追加有来源的操作或回复。 */
  /** 发布公示后按可见范围发送站内消息；排除操作人。 */
  private void publishNotice(String demandId, Map<String, Object> demand, Long userId) {
    Map<String, Object> publication =
        store.one(
            "SELECT id,demand_id,title,summary FROM rm_demand_publication WHERE demand_id=?",
            demandId);
    if (publication == null) return;
    publication.put("formKey", demand.get("formKey"));
    notices.publication(publication, userId);
  }
  /** 写入需求时间线消息。 */
  private void message(String id, Long userId, String kind, String content) {
    store.update(
        "INSERT INTO rm_demand_message(id,demand_id,author_id,author_name,kind,content) VALUES(?,?,?,?,?,?)",
        id(),
        id,
        userId,
        access.name(userId),
        kind,
        content);
  }
  /** 保护并发修改不覆盖前一人的结果。 */
  private void checkRevision(Map<String, Object> d, Long revision) {
    if (revision == null || !revision.equals(number(d.get("revision"))))
      throw new BaseException("需求已更新，请刷新后重试", 409);
  }
  /** 读取可空文字并去除首尾空白。 */
  private String str(Object value) {
    return value == null ? "" : value.toString().trim();
  }
  /** 限制导出或公示字段长度，不生成超长内容。 */
  private String clip(String value, int max) {
    return value.length() <= max ? value : value.substring(0, max);
  }
  /** 业务结果显示名称。 */
  private String decisionLabel(String s) {
    return "ACCEPTED".equals(s)
        ? "采纳"
        : "DEFERRED".equals(s) ? "暂缓" : "REJECTED".equals(s) ? "不采纳" : "待评估";
  }
  /** 实施进度显示名称。 */
  private String progressLabel(String s) {
    return "COMPLETED".equals(s) ? "已完成" : "IN_PROGRESS".equals(s) ? "实施中" : "待实施";
  }
}
