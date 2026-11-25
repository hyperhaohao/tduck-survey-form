package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** 站内消息：事件扇出、列表、已读与清理；外部渠道后续在此扩展。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandNotices {
  public static final String PUBLICATION = "PUBLICATION";
  public static final String NEW_DEMAND = "NEW_DEMAND";
  public static final String REPLY = "REPLY";
  public static final String RESULT = "RESULT";
  public static final String ASSIGN = "ASSIGN";
  public static final String FOLLOWUP = "FOLLOWUP";
  public static final String REQUIRED = "REQUIRED";
  public static final String APPROVAL = "APPROVAL";
  public static final String APPROVAL_RESULT = "APPROVAL_RESULT";

  private final DemandStore store;
  private final DemandAccess access;
  private final DemandProperties properties;

  /** 未读消息数，供侧栏与铃铛角标使用。 */
  public long unread(Long userId) {
    if (userId == null) return 0;
    return store.count(
        "SELECT COUNT(*) FROM rm_demand_notice WHERE user_id=? AND read_at IS NULL", userId);
  }

  /** 当前用户的消息列表；包含已读，支持类型与未读筛选。 */
  public Map<String, Object> list(
      Long userId, String kind, boolean unreadOnly, int page, int size) {
    if (userId == null) throw new BaseException("请先登录", 401);
    StringBuilder where = new StringBuilder(" WHERE user_id=?");
    List<Object> args = new ArrayList<>();
    args.add(userId);
    if (kind != null && !kind.trim().isEmpty()) {
      where.append(" AND kind=?");
      args.add(kind.trim().toUpperCase(Locale.ROOT));
    }
    if (unreadOnly) where.append(" AND read_at IS NULL");
    long total =
        store.count("SELECT COUNT(*)" + " FROM rm_demand_notice" + where, args.toArray());
    int limit = Math.min(100, Math.max(1, size));
    int current = Math.max(1, page);
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add((current - 1) * limit);
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT id,kind,form_key,demand_id,publication_id,round_no,step_no,title,content,read_at,create_time"
                + " FROM rm_demand_notice"
                + where
                + " ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",
            pageArgs.toArray());
    for (Map<String, Object> row : rows) row.put("unread", row.get("readAt") == null);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rows", rows);
    result.put("total", total);
    result.put("page", current);
    result.put("size", limit);
    result.put("unread", unread(userId));
    return result;
  }

  /** 标记单条消息已读；不存在或不属于本人时拒绝。 */
  public void read(String id, Long userId) {
    if (userId == null) throw new BaseException("请先登录", 401);
    if (store.update(
            "UPDATE rm_demand_notice SET read_at=CURRENT_TIMESTAMP WHERE id=? AND user_id=? AND read_at IS NULL",
            id,
            userId)
        > 0) return;
    if (store.count("SELECT COUNT(*) FROM rm_demand_notice WHERE id=? AND user_id=?", id, userId)
        == 0) throw new BaseException("消息不存在或无权访问", 403);
  }

  /** 全部标记已读，返回更新条数。 */
  public int readAll(Long userId) {
    if (userId == null) throw new BaseException("请先登录", 401);
    return store.update(
        "UPDATE rm_demand_notice SET read_at=CURRENT_TIMESTAMP WHERE user_id=? AND read_at IS NULL",
        userId);
  }

  /** 打开需求/公示时同步清理对应消息未读，保持两套角标一致。 */
  public void readForObject(Long userId, String type, String objectId) {
    if (userId == null || objectId == null) return;
    if ("DEMAND".equals(type))
      store.update(
          "UPDATE rm_demand_notice SET read_at=CURRENT_TIMESTAMP WHERE user_id=? AND demand_id=? AND read_at IS NULL",
          userId,
          objectId);
    else if ("PUBLICATION".equals(type))
      store.update(
          "UPDATE rm_demand_notice SET read_at=CURRENT_TIMESTAMP WHERE user_id=? AND publication_id=? AND read_at IS NULL",
          userId,
          objectId);
  }

  /** 发布公示：按收集单可见范围扇出，排除操作人；重新发布时复用同一条消息。 */
  public void publication(Map<String, Object> publication, Long operator) {
    try {
      Object formKeyValue = publication.get("formKey");
      if (formKeyValue == null) return;
      String formKey = String.valueOf(formKeyValue);
      Map<String, Object> form = access.form(formKey);
      if (form == null) return;
      Set<Long> recipients = audience(form);
      recipients.remove(operator);
      String title = clip("新公示：" + str(publication.get("title")), 200);
      String content = clip(str(publication.get("summary")), 1000);
      String demandId = str(publication.get("demandId"));
      String publicationId = str(publication.get("id"));
      List<Object[]> inserts = new ArrayList<>();
      for (Long userId : recipients) {
        if (store.update(
                "UPDATE rm_demand_notice SET title=?,content=?,demand_id=?,read_at=NULL,create_time=CURRENT_TIMESTAMP"
                    + " WHERE user_id=? AND kind=? AND publication_id=?",
                title,
                content,
                emptyToNull(demandId),
                userId,
                PUBLICATION,
                publicationId)
            > 0) continue;
        inserts.add(
            new Object[] {
              id(),
              userId,
              PUBLICATION,
              formKey,
              emptyToNull(demandId),
              publicationId,
              null,
              title,
              content
            });
      }
      store.batch(
          "INSERT INTO rm_demand_notice(id,user_id,kind,form_key,demand_id,publication_id,round_no,title,content)"
              + " VALUES(?,?,?,?,?,?,?,?,?)",
          inserts);
    } catch (Exception error) {
      log.warn("发布公示消息失败：{}", publication.get("id"), error);
    }
  }

  /** 新需求提交：通知该收集单的单据管理员，排除提交人本人。 */
  public void newDemand(Map<String, Object> demand) {
    try {
      String formKey = str(demand.get("formKey"));
      if (formKey.isEmpty()) return;
      Long submitter = number(demand.get("submitterId"));
      String title = "有新的需求待处理";
      String content = clip(str(demand.get("title")), 1000);
      for (Long userId : managers(formKey)) {
        if (userId.equals(submitter)) continue;
        if (store.count(
                "SELECT COUNT(*) FROM rm_demand_notice WHERE user_id=? AND kind=? AND demand_id=?",
                userId,
                NEW_DEMAND,
                str(demand.get("id")))
            > 0) continue;
        insert(
            userId,
            NEW_DEMAND,
            formKey,
            str(demand.get("id")),
            null,
            null,
            title,
            content);
      }
    } catch (Exception error) {
      log.warn("新需求消息失败：{}", demand.get("id"), error);
    }
  }

  /** 正式回复：通知非匿名填报者本人。 */
  public void reply(Map<String, Object> demand, String content) {
    notifySubmitter(demand, REPLY, "您的需求有新回复", content);
  }

  /** 采纳结果/进度更新：通知非匿名填报者本人。 */
  public void result(Map<String, Object> demand, String text) {
    notifySubmitter(demand, RESULT, "处理结果更新", text + " · " + str(demand.get("title")));
  }

  /** 分派：通知新经办人。 */
  public void assign(Map<String, Object> demand, Long handlerId, Long operator) {
    try {
      if (handlerId == null || handlerId.equals(operator)) return;
      if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", handlerId) == 0)
        return;
      insert(
          handlerId,
          ASSIGN,
          str(demand.get("formKey")),
          str(demand.get("id")),
          null,
          null,
          "有新的需求分派给您",
          clip(str(demand.get("title")), 1000));
    } catch (Exception error) {
      log.warn("分派消息失败：{}", demand.get("id"), error);
    }
  }

  /** 填报者追问：通知经办人与全部单据管理员，排除追问人。 */
  public void followUp(Map<String, Object> demand, String content) {
    try {
      String formKey = str(demand.get("formKey"));
      Long submitter = number(demand.get("submitterId"));
      Set<Long> recipients = new LinkedHashSet<>();
      Long handler = number(demand.get("handlerId"));
      if (handler != null) recipients.add(handler);
      recipients.addAll(managers(formKey));
      recipients.remove(submitter);
      String title = "填报者追问";
      String text = clip(content, 1000);
      for (Long userId : recipients)
        if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", userId) > 0)
          insert(userId, FOLLOWUP, formKey, str(demand.get("id")), null, null, title, text);
    } catch (Exception error) {
      log.warn("追问消息失败：{}", demand.get("id"), error);
    }
  }

  /** 提交待部门主管审批：通知当前级全部候选人，按级去重。 */
  public void approvalTask(
      Map<String, Object> demand, int stepNo, String deptName, List<Map<String, Object>> approvers) {
    try {
      String demandId = str(demand.get("id"));
      String title = "有新的需求待您审批";
      String content =
          clip(
              str(demand.get("title"))
                  + (deptName == null || deptName.isEmpty()
                      ? ""
                      : " · 第" + stepNo + "级 " + deptName),
              1000);
      for (Map<String, Object> approver : approvers) {
        Long userId = number(approver.get("userId"));
        if (userId == null) continue;
        if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", userId) == 0)
          continue;
        if (store.count(
                "SELECT COUNT(*) FROM rm_demand_notice WHERE user_id=? AND kind=? AND demand_id=? AND step_no=?",
                userId,
                APPROVAL,
                demandId,
                stepNo)
            > 0) continue;
        insertWithStep(
            userId,
            APPROVAL,
            str(demand.get("formKey")),
            demandId,
            null,
            null,
            stepNo,
            title,
            content);
      }
    } catch (Exception error) {
      log.warn("审批任务消息失败：{}", demand.get("id"), error);
    }
  }

  /** 旧单级兼容：无步骤号。 */
  public void approvalTask(Map<String, Object> demand, Long leaderId) {
    try {
      if (leaderId == null) return;
      String demandId = str(demand.get("id"));
      if (store.count(
              "SELECT COUNT(*) FROM rm_demand_notice WHERE user_id=? AND kind=? AND demand_id=?",
              leaderId,
              APPROVAL,
              demandId)
          > 0) return;
      insert(
          leaderId,
          APPROVAL,
          str(demand.get("formKey")),
          demandId,
          null,
          null,
          "有新的需求待您审批",
          clip(str(demand.get("title")), 1000));
    } catch (Exception error) {
      log.warn("审批任务消息失败：{}", demand.get("id"), error);
    }
  }

  /** 当前级处理完成后，把该级全部待审批消息置为已读（含操作人）。 */
  public void readApprovalStep(String demandId, int stepNo) {
    if (demandId == null) return;
    store.update(
        "UPDATE rm_demand_notice SET read_at=CURRENT_TIMESTAMP WHERE kind=? AND demand_id=? AND step_no=? AND read_at IS NULL",
        APPROVAL,
        demandId,
        stepNo);
  }

  /** 审批结果：通知填报人。 */
  public void approvalResult(Map<String, Object> demand, boolean approved, String comment) {
    try {
      Long submitter = number(demand.get("submitterId"));
      if (submitter == null || flag(demand.get("anonymous"))) return;
      insert(
          submitter,
          APPROVAL_RESULT,
          str(demand.get("formKey")),
          str(demand.get("id")),
          null,
          null,
          approved ? "部门主管已通过审批" : "部门主管驳回审批",
          clip(comment == null || comment.isEmpty() ? str(demand.get("title")) : comment, 1000));
    } catch (Exception error) {
      log.warn("审批结果消息失败：{}", demand.get("id"), error);
    }
  }

  /** 待填报提醒：同一用户、同一收集单、同一轮、同一天只保留一条。 */
  public void requiredReminder(
      Map<String, Object> form, Long userId, String formName, Timestamp endTime) {
    try {
      if (userId == null) return;
      String formKey = str(form.get("formKey"));
      Long roundValue = number(form.get("roundNo"));
      int roundNo = roundValue == null ? 0 : roundValue.intValue();
      Timestamp dayStart =
          Timestamp.from(LocalDate.now(zone()).atStartOfDay(zone()).toInstant());
      if (store.count(
              "SELECT COUNT(*) FROM rm_demand_notice WHERE user_id=? AND kind=? AND form_key=? AND round_no=? AND create_time>=?",
              userId,
              REQUIRED,
              formKey,
              roundNo,
              dayStart)
          > 0) return;
      String deadline =
          endTime == null
              ? ""
              : endTime.toInstant().atZone(zone()).toLocalDateTime().toString().replace('T', ' ');
      insert(
          userId,
          REQUIRED,
          formKey,
          null,
          null,
          roundNo,
          "待填报提醒",
          clip(
              "「"
                  + formName
                  + "」本轮需求征集将于 "
                  + deadline
                  + " 截止，您尚未提交，请及时填报。",
              1000));
    } catch (Exception error) {
      log.warn("待填报提醒失败：{}", form.get("formKey"), error);
    }
  }

  public void deleteForDemand(String demandId) {
    if (demandId != null)
      store.update("DELETE FROM rm_demand_notice WHERE demand_id=?", demandId);
  }

  public void deleteForPublication(String publicationId) {
    if (publicationId != null)
      store.update("DELETE FROM rm_demand_notice WHERE publication_id=?", publicationId);
  }

  public void deleteForForm(String formKey) {
    if (formKey != null) store.update("DELETE FROM rm_demand_notice WHERE form_key=?", formKey);
  }

  private void notifySubmitter(
      Map<String, Object> demand, String kind, String title, String content) {
    try {
      Long submitter = number(demand.get("submitterId"));
      if (submitter == null || flag(demand.get("anonymous"))) return;
      if (store.count("SELECT COUNT(*) FROM ac_user WHERE id=? AND deleted=FALSE", submitter) == 0)
        return;
      insert(
          submitter,
          kind,
          str(demand.get("formKey")),
          str(demand.get("id")),
          null,
          null,
          title,
          clip(content, 1000));
    } catch (Exception error) {
      log.warn("需求消息失败：{}", demand.get("id"), error);
    }
  }

  private void insert(
      Long userId,
      String kind,
      String formKey,
      String demandId,
      String publicationId,
      Integer roundNo,
      String title,
      String content) {
    if (userId == null) return;
    store.update(
        "INSERT INTO rm_demand_notice(id,user_id,kind,form_key,demand_id,publication_id,round_no,title,content)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        id(),
        userId,
        kind,
        emptyToNull(formKey),
        emptyToNull(demandId),
        emptyToNull(publicationId),
        roundNo,
        clip(title, 200),
        clip(content, 1000));
  }

  private void insertWithStep(
      Long userId,
      String kind,
      String formKey,
      String demandId,
      String publicationId,
      Integer roundNo,
      Integer stepNo,
      String title,
      String content) {
    if (userId == null) return;
    store.update(
        "INSERT INTO rm_demand_notice(id,user_id,kind,form_key,demand_id,publication_id,round_no,step_no,title,content)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id(),
        userId,
        kind,
        emptyToNull(formKey),
        emptyToNull(demandId),
        emptyToNull(publicationId),
        roundNo,
        stepNo,
        clip(title, 200),
        clip(content, 1000));
  }

  /** 可见该收集单公示的用户：LOGIN 全员，SELECTED 取填报/经办/管理授权并集。 */
  private Set<Long> audience(Map<String, Object> form) {
    Set<Long> ids = new LinkedHashSet<>();
    String formKey = str(form.get("formKey"));
    if ("LOGIN".equals(str(form.get("visibility")))) {
      for (Map<String, Object> row :
          store.rows("SELECT id FROM ac_user WHERE deleted=FALSE")) add(ids, row.get("id"));
      return ids;
    }
    for (Map<String, Object> row :
        store.rows(
            "SELECT a.user_id FROM rm_demand_acl a JOIN ac_user u ON u.id=a.user_id AND u.deleted=FALSE"
                + " WHERE a.form_key=? AND (a.can_fill=TRUE OR a.can_handle=TRUE OR a.can_manage=TRUE)"
                + " UNION "
                + "SELECT DISTINCT ud.user_id FROM rm_demand_acl_dept ad "
                + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
                + "JOIN rm_demand_dept pd ON pd.active=TRUE "
                + "JOIN rm_demand_user_dept ud ON ud.dept_key=pd.dept_key "
                + "JOIN ac_user u ON u.id=ud.user_id AND u.deleted=FALSE "
                + "WHERE ad.form_key=? AND (ad.can_fill=TRUE OR ad.can_handle=TRUE OR ad.can_manage=TRUE) "
                + "AND (pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1)",
            formKey,
            formKey))
      add(ids, row.get("userId"));
    return ids;
  }

  /** 该收集单的全部单据管理员（直授 + 部门授权含下级，视为在职）。 */
  private Set<Long> managers(String formKey) {
    Set<Long> ids = new LinkedHashSet<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT a.user_id FROM rm_demand_acl a JOIN ac_user u ON u.id=a.user_id AND u.deleted=FALSE"
                + " WHERE a.form_key=? AND a.can_manage=TRUE"
                + " UNION "
                + "SELECT DISTINCT ud.user_id FROM rm_demand_acl_dept ad "
                + "JOIN rm_demand_dept gd ON gd.dept_key=ad.dept_key AND gd.active=TRUE "
                + "JOIN rm_demand_dept pd ON pd.active=TRUE "
                + "JOIN rm_demand_user_dept ud ON ud.dept_key=pd.dept_key "
                + "JOIN ac_user u ON u.id=ud.user_id AND u.deleted=FALSE "
                + "WHERE ad.form_key=? AND ad.can_manage=TRUE "
                + "AND (pd.path=gd.path OR LOCATE(CONCAT(gd.path,'/'),pd.path)=1)",
            formKey,
            formKey))
      add(ids, row.get("userId"));
    return ids;
  }

  private ZoneId zone() {
    try {
      String value = properties.getZone();
      return ZoneId.of(value == null || value.isEmpty() ? "Asia/Shanghai" : value);
    } catch (Exception error) {
      return ZoneId.of("Asia/Shanghai");
    }
  }

  private void add(Set<Long> ids, Object value) {
    Long id = number(value);
    if (id != null) ids.add(id);
  }

  private static String emptyToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value;
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }

  private static String clip(String value, int max) {
    String text = value == null ? "" : value.trim();
    return text.length() <= max ? text : text.substring(0, max);
  }
}
