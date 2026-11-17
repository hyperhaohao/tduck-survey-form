package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.form.service.UserFormItemService;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 需求列表、分派、回复、采纳与实施进度服务。 */
@Service
@RequiredArgsConstructor
public class DemandWorkflow {
  private final DemandStore store;
  private final DemandAccess access;
  private final UserFormItemService fields;
  private static final String FROM =
      " FROM rm_demand d JOIN fm_user_form f ON f.form_key=d.form_key";
  private static final String ROW_SELECT =
      "SELECT d.id,d.form_key,d.request_no,d.title,d.submitter_name,d.submitted_by,d.handler_id,d.decision,d.progress,d.reason,d.completion,d.create_time,d.update_time,d.public_version,d.publication_skipped,d.revision,f.name AS form_name,h.name AS handler_name,pub.published AS publication_published,pub.version AS publication_version,(SELECT MAX(m.create_time) FROM rm_demand_message m WHERE m.demand_id=d.id AND m.kind='REPLY') AS reply_time,CASE WHEN d.public_version>COALESCE(r.seen_version,1) THEN TRUE ELSE FALSE END AS unread"
          + FROM
          + " LEFT JOIN ac_user h ON h.id=d.handler_id LEFT JOIN rm_demand_publication pub ON pub.demand_id=d.id LEFT JOIN rm_demand_read r ON r.object_type='DEMAND' AND r.object_id=d.id AND r.user_id=?";
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
    decorate(rows, userId);
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
    decorate(rows, userId);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rows", rows);
    result.put("truncated", truncated);
    return result;
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
    if ("managed".equals(scope)) access.requireConsoleAdmin(userId);
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
  private void decorate(List<Map<String, Object>> rows, Long userId) {
    if (rows.isEmpty()) return;
    boolean global = access.globalAdmin(userId);
    DemandGrants grants = access.effectiveGrants(userId);
    for (Map<String, Object> row : rows) {
      String key = String.valueOf(row.get("formKey"));
      row.put("canHandle", grants.canHandle(key));
      row.put("canManage", global || grants.canManage(key));
    }
  }
  /** 按收集单汇总当前范围的需求数量，供页面先选收集单再下钻。 */
  public List<Map<String, Object>> summary(Long userId, String scope) {
    access.requireEnabled();
    if (userId == null) throw new BaseException("请先登录", 401);
    if ("managed".equals(scope)) access.requireConsoleAdmin(userId);
    boolean global = access.globalAdmin(userId);
    DemandGrants grants = access.effectiveGrants(userId);
    List<Object> args = new ArrayList<>();
    StringBuilder where = new StringBuilder(" WHERE f.is_deleted=FALSE ");
    DemandScope.apply(scope, userId, global, grants, where, args);
    List<Object> rowArgs = new ArrayList<>();
    rowArgs.add(userId);
    rowArgs.addAll(args);
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT d.form_key,f.name AS form_name,COUNT(*) AS total,"
                + "SUM(CASE WHEN d.public_version>COALESCE(r.seen_version,1) THEN 1 ELSE 0 END) AS unread,"
                + "SUM(CASE WHEN d.decision<>'REJECTED' AND d.progress<>'COMPLETED' THEN 1 ELSE 0 END) AS pending,"
                + "MAX(d.update_time) AS last_update"
                + " FROM rm_demand d JOIN fm_user_form f ON f.form_key=d.form_key"
                + " LEFT JOIN rm_demand_read r ON r.object_type='DEMAND' AND r.object_id=d.id AND r.user_id=?"
                + where
                + " GROUP BY d.form_key,f.name ORDER BY last_update DESC,d.form_key",
            rowArgs.toArray());
    for (Map<String, Object> row : rows)
      row.put("canManage", global || grants.canManage(String.valueOf(row.get("formKey"))));
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
    if (!staff && !own && !anonymous) throw new BaseException("需求不存在或无权访问", 403);
    return d;
  }
  /** 返回详情，原表单数据只读取一次正本，内部备注与草稿独立过滤。 */
  public Map<String, Object> detail(String demandId, Long userId, String queryCode) {
    Map<String, Object> d = new LinkedHashMap<>(demand(demandId, userId, queryCode, false));
    String formKey = d.get("formKey").toString();
    boolean staff = access.table(formKey, userId);
    boolean manage = access.manage(formKey, userId);
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
    d.put("originalData", store.json(raw.get("originalData")));
    d.put("formFields", fields.listFormFields(formKey));
    d.put("canViewTable", staff);
    d.put("canManage", manage);
    d.put(
        "canHandle",
        manage
            || (userId != null
                && userId.equals(number(d.get("handlerId")))
                && access.handler(formKey, userId)));
    d.put("canAssign", manage);
    d.put("canPublish", manage);
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
          store.rows(messageSql + "kind IN ('REPLY','EVENT') ORDER BY create_time,id", demandId));
    d.put(
        "publication",
        manage
            ? store.one("SELECT * FROM rm_demand_publication WHERE demand_id=?", demandId)
            : null);
    return d;
  }
  /** 单据管理员或全局管理员分派给已授权经办人，支持并发版本校验。 */
  @Transactional
  public void assign(String id, Long handlerId, long revision, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    checkRevision(d, revision);
    if (handlerId != null && !access.handler(d.get("formKey").toString(), handlerId))
      throw new BaseException("请选择该收集单已授权的经办人");
    store.update(
        "UPDATE rm_demand SET handler_id=?,revision=revision+1,public_version=public_version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
        handlerId,
        id);
    message(id, userId, "AUDIT", handlerId == null ? "取消经办分派" : "分派经办人：" + access.name(handlerId));
  }
  /** 经办人或单据管理员发布采纳结果及实施进度。 */
  @Transactional
  public void outcome(String id, Map<String, Object> body, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireHandle(d, userId);
    checkRevision(d, number(body.get("revision")));
    String decision = String.valueOf(body.get("decision"));
    String progress = String.valueOf(body.get("progress"));
    if (!Arrays.asList("PENDING", "ACCEPTED", "DEFERRED", "REJECTED").contains(decision))
      throw new BaseException("采纳结果无效");
    String reason = body.get("reason") == null ? "" : body.get("reason").toString().trim();
    String completion =
        body.get("completion") == null ? "" : body.get("completion").toString().trim();
    if (reason.length() > 10000 || completion.length() > 10000)
      throw new BaseException("说明不能超过10000字");
    if (!"PENDING".equals(decision) && reason.isEmpty()) throw new BaseException("请填写采纳结果说明");
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
  }
  /** 追加回复、内部备注或本人草稿，经办人或单据管理员均可操作。 */
  @Transactional
  public void reply(String id, String kind, String content, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireHandle(d, userId);
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
          "UPDATE rm_demand SET public_version=public_version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
          id);
    }
    message(id, userId, kind, content);
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
  }
  /** 公示列表仅查询已发布的人工选定内容，不拼接原始表单正文。 */
  public List<Map<String, Object>> publications(Long userId) {
    access.requireEnabled();
    if (userId == null) throw new BaseException("请先登录", 401);
    boolean global = access.globalAdmin(userId);
    DemandGrants grants = access.effectiveGrants(userId);
    StringBuilder sql =
        new StringBuilder(
            "SELECT p.id,p.title,p.summary,p.decision,p.progress,p.version,p.update_time,d.form_key,d.request_no,d.submitter_name,d.create_time,u.name AS form_name,h.name AS handler_name,CASE WHEN p.version>COALESCE(r.seen_version,0) THEN TRUE ELSE FALSE END AS unread FROM rm_demand_publication p JOIN rm_demand d ON d.id=p.demand_id JOIN rm_demand_form f ON f.form_key=d.form_key JOIN fm_user_form u ON u.form_key=d.form_key LEFT JOIN ac_user h ON h.id=d.handler_id LEFT JOIN rm_demand_read r ON r.object_type='PUBLICATION' AND r.object_id=p.id AND r.user_id=? WHERE p.published=TRUE AND (f.visibility='LOGIN'");
    List<Object> args = new ArrayList<>();
    args.add(userId);
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
    return store.rows(sql.toString(), args.toArray());
  }
  /** 返回公示快照，仅包含人工审核后的字段。 */
  public Map<String, Object> publication(String id, Long userId) {
    if (userId == null) throw new BaseException("请先登录", 401);
    Map<String, Object> p =
        store.one(
            "SELECT p.id,p.title,p.summary,p.content,p.decision,p.progress,p.version,p.update_time,d.form_key,u.name AS form_name FROM rm_demand_publication p JOIN rm_demand d ON d.id=p.demand_id JOIN fm_user_form u ON u.form_key=d.form_key WHERE p.id=? AND p.published=TRUE",
            id);
    if (p == null) throw new BaseException("公示不存在或已撤回", 403);
    Map<String, Object> form = access.form(p.get("formKey").toString());
    if (form == null
        || (!access.visible(form, userId) && !access.table(p.get("formKey").toString(), userId)))
      throw new BaseException("无公示阅读权限", 403);
    p.remove("formKey");
    return p;
  }
  /** 单据管理员显式发布或撤回，更新版本后重新触发未读提示。 */
  @Transactional
  public void publish(String id, Map<String, Object> body, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    Map<String, Object> old =
        store.one("SELECT * FROM rm_demand_publication WHERE demand_id=? FOR UPDATE", id);
    long expected = number(body.getOrDefault("version", 0));
    if (old != null && expected != number(old.get("version")))
      throw new BaseException("公示已变化，请刷新后重试", 409);
    boolean publish = flag(body.get("published"));
    if (!publish) {
      if (old != null)
        store.update(
            "UPDATE rm_demand_publication SET published=FALSE,version=version+1,update_time=CURRENT_TIMESTAMP WHERE demand_id=?",
            id);
      message(id, userId, "AUDIT", "撤回公示");
      return;
    }
    requireReply(id);
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
          "UPDATE rm_demand_publication SET title=?,summary=?,content=?,published=TRUE,version=version+1,decision=?,progress=?,update_time=CURRENT_TIMESTAMP WHERE demand_id=?",
          title,
          summary,
          content,
          d.get("decision"),
          d.get("progress"),
          id);
    store.update("UPDATE rm_demand SET publication_skipped=FALSE WHERE id=?", id);
    message(id, userId, "AUDIT", "审核并发布公示");
  }
  /** 按需求标题、采纳说明和最近正式回复生成公示；已发布时幂等跳过。 */
  @Transactional
  public Map<String, Object> autoPublish(String id, Long revision, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    checkRevision(d, revision);
    Map<String, Object> old =
        store.one("SELECT * FROM rm_demand_publication WHERE demand_id=? FOR UPDATE", id);
    Map<String, Object> result = new LinkedHashMap<>();
    if (old != null && flag(old.get("published"))) {
      result.put("published", true);
      result.put("unchanged", true);
      return result;
    }
    requireReply(id);
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
      throw new BaseException("缺少采纳说明或正式回复，无法自动生成公示");
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
          "UPDATE rm_demand_publication SET title=?,summary=?,content=?,published=TRUE,version=version+1,decision=?,progress=?,update_time=CURRENT_TIMESTAMP WHERE demand_id=?",
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
    result.put("published", true);
    result.put("unchanged", false);
    return result;
  }
  /** 记录不公示并移出待办；已公示需先撤回，不增加填报者未读版本。 */
  @Transactional
  public Map<String, Object> skipPublication(String id, Long revision, Long userId) {
    Map<String, Object> d = demand(id, userId, null, true);
    access.requireManage(d.get("formKey").toString(), userId);
    Map<String, Object> publication =
        store.one("SELECT published FROM rm_demand_publication WHERE demand_id=? FOR UPDATE", id);
    if (publication != null && flag(publication.get("published")))
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
  /** 公示前必须有面向填报者的正式回复，内部备注和草稿不能替代。 */
  private void requireReply(String id) {
    if (store.count(
            "SELECT COUNT(*) FROM rm_demand_message WHERE demand_id=? AND kind='REPLY'", id)
        == 0) throw new BaseException("公示前必须先发布正式回复", 409);
  }
  /** 追加有来源的操作或回复。 */
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
