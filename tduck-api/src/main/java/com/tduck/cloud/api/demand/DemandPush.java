package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZonedDateTime;
import java.util.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 钉钉推送 outbox：工作通知 + 待办；业务线程只写 outbox，定时派发。 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DemandPush {
  private static final String WORK = DemandPushSettings.WORK;
  private static final String TODO = DemandPushSettings.TODO;
  private static final String CREATE = "CREATE";
  private static final String COMPLETE = "COMPLETE";
  private static final String DELETE = "DELETE";
  private static final String PENDING = "PENDING";
  private static final String SENDING = "SENDING";
  private static final String SENT = "SENT";
  private static final String FAILED = "FAILED";
  private static final String DEAD = "DEAD";
  private static final String SKIPPED = "SKIPPED";
  private static final String CANCELLED = "CANCELLED";
  private static final String DONE = "DONE";

  private final DemandStore store;
  private final DemandPushSettings settings;
  private final DemandDingTalkClient client;
  private final DemandDingTalkDirectory directory;
  private final DemandDingTalkProperties properties;

  /** 业务提交后写 outbox；业务回滚不会触发。 */
  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void onNotice(DemandNoticeCreatedEvent event) {
    enqueue(event);
  }

  /** 可被测试直接调用；写 outbox，不做 HTTP。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void enqueue(DemandNoticeCreatedEvent event) {
    try {
      if (!settings.enabled() || event == null || event.getUserId() == null) return;
      String kind = event.getKind();
      if (settings.channel(kind, WORK)) enqueueWork(event);
      if (DemandNotices.APPROVAL.equals(kind) || DemandNotices.REQUIRED.equals(kind))
        if (settings.channel(kind, TODO)) enqueueTodo(event);
    } catch (Exception error) {
      log.warn("钉钉推送入队失败 noticeId={}", event == null ? null : event.getNoticeId(), error);
    }
  }

  private void enqueueWork(DemandNoticeCreatedEvent event) {
    String title = "【瑞玛问卷】" + event.getTitle();
    String link = link(event);
    String content = clip(event.getContent(), 800);
    Map<String, Object> mapping = directory.confirmed(event.getUserId());
    String dingUserId = mapping == null ? null : str(mapping.get("dingtalkUserId"));
    String status = dingUserId == null ? SKIPPED : PENDING;
    String error = dingUserId == null ? "NO_MAPPING" : null;
    String dedupe =
        hash("WORK\n" + event.getNoticeId() + "\n" + event.getKind() + "\n" + title + "\n" + content);
    String batch = hash("WORK\n" + event.getKind() + "\n" + title + "\n" + content + "\n" + link);
    insert(
        event,
        null,
        WORK,
        CREATE,
        dingUserId,
        null,
        null,
        null,
        title,
        content,
        link,
        dedupe,
        batch,
        status,
        error,
        nextAttempt(event.getKind()));
  }

  private void enqueueTodo(DemandNoticeCreatedEvent event) {
    if (!DemandNotices.APPROVAL.equals(event.getKind())
        && !DemandNotices.REQUIRED.equals(event.getKind())) return;
    Map<String, Object> mapping = directory.confirmed(event.getUserId());
    String unionId = mapping == null ? null : str(mapping.get("unionId"));
    String creator = directory.creatorUnionId();
    String status = PENDING;
    String error = null;
    if (unionId == null || unionId.isEmpty()) {
      status = SKIPPED;
      error = "NO_MAPPING_OR_UNION";
    } else if (creator == null || creator.isEmpty()) {
      status = SKIPPED;
      error = "NO_CREATOR";
    }
    String sourceId =
        DemandNotices.APPROVAL.equals(event.getKind())
            ? "APPROVAL:" + event.getDemandId() + ":" + event.getStepNo() + ":" + event.getUserId()
            : "REQUIRED:" + event.getFormKey() + ":" + event.getRoundNo() + ":" + event.getUserId();
    String title = "【瑞玛问卷】" + event.getTitle();
    String content = clip(event.getContent(), 800);
    String link = link(event);
    String dedupe = hash("TODO\n" + sourceId);
    Timestamp due = event.getDueAt();
    if (due == null && event.getFormKey() != null) due = formDue(event.getFormKey());
    insert(
        event,
        unionId,
        TODO,
        CREATE,
        mapping == null ? null : str(mapping.get("dingtalkUserId")),
        unionId,
        sourceId,
        null,
        title,
        content,
        link,
        dedupe,
        null,
        status,
        error,
        nextAttempt(event.getKind()));
    if (PENDING.equals(status) && due != null)
      store.update(
          "UPDATE rm_demand_push SET due_at=?,next_retry_at=LEAST(next_retry_at,?) WHERE dedupe_key=?",
          due,
          due,
          dedupe);
  }

  private void insert(
      DemandNoticeCreatedEvent event,
      String unionId,
      String channel,
      String action,
      String dingUserId,
      String executorUnionId,
      String sourceId,
      String parentId,
      String title,
      String content,
      String link,
      String dedupe,
      String batch,
      String status,
      String error,
      Timestamp nextAttempt) {
    Object retry = nextAttempt;
    try {
      store.update(
          "INSERT INTO rm_demand_push(id,notice_id,user_id,dingtalk_user_id,union_id,channel,action,kind,form_key,demand_id,publication_id,round_no,step_no,parent_id,source_id,title,content,link_url,dedupe_key,batch_key,status,next_retry_at,last_error) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id(),
          event.getNoticeId(),
          event.getUserId(),
          emptyToNull(dingUserId),
          emptyToNull(unionId),
          channel,
          action,
          event.getKind(),
          emptyToNull(event.getFormKey()),
          emptyToNull(event.getDemandId()),
          emptyToNull(event.getPublicationId()),
          event.getRoundNo(),
          event.getStepNo(),
          parentId,
          emptyToNull(sourceId),
          clip(title, 200),
          clip(content, 1000),
          link,
          dedupe,
          batch,
          status,
          retry,
          error);
    } catch (DuplicateKeyException ignored) {
      /* 同一事件重复入队，保留第一条 */
    } catch (DataAccessException errorCaught) {
      log.warn("钉钉 outbox 写入失败 kind={} user={}", event.getKind(), event.getUserId());
    }
  }

  /** 审批步骤完成：完成该级全部待办（或取消尚未创建的待办）。 */
  @Transactional
  public void completeApprovalStep(String demandId, int stepNo) {
    if (demandId == null) return;
    completeBy(
        "SELECT * FROM rm_demand_push WHERE channel='TODO' AND action='CREATE' AND demand_id=? AND step_no=? AND status IN ('PENDING','SENT','FAILED','SENDING')",
        new Object[] {demandId, stepNo});
  }

  /** 必填提交：完成该用户本轮的待填报待办。 */
  @Transactional
  public void completeRequired(Long userId, String formKey, Integer roundNo) {
    if (userId == null || formKey == null || roundNo == null) return;
    completeBy(
        "SELECT * FROM rm_demand_push WHERE channel='TODO' AND action='CREATE' AND kind=? AND user_id=? AND form_key=? AND round_no=? AND status IN ('PENDING','SENT','FAILED','SENDING')",
        new Object[] {DemandNotices.REQUIRED, userId, formKey, roundNo});
  }

  private void completeBy(String sql, Object[] args) {
    for (Map<String, Object> parent : store.rows(sql, args)) {
      String status = str(parent.get("status"));
      if (PENDING.equals(status)
          || (FAILED.equals(status) && str(parent.get("todoTaskId")).isEmpty())) {
        store.update(
            "UPDATE rm_demand_push SET status=?,last_error='STEP_DONE_BEFORE_CREATE',update_time=CURRENT_TIMESTAMP WHERE id=? AND status IN (?,?)",
            CANCELLED,
            parent.get("id"),
            PENDING,
            FAILED);
        continue;
      }
      insertAction(parent, COMPLETE);
    }
  }

  /** 删除需求/收集单：取消未创建的待办，删除已创建的待办。 */
  @Transactional
  public void cancelDemand(String demandId) {
    if (demandId == null) return;
    cancelBy("SELECT * FROM rm_demand_push WHERE channel='TODO' AND action='CREATE' AND demand_id=? AND status IN ('PENDING','SENT','FAILED','SENDING')", new Object[] {demandId});
  }

  @Transactional
  public void cancelForm(String formKey) {
    if (formKey == null) return;
    cancelBy("SELECT * FROM rm_demand_push WHERE channel='TODO' AND action='CREATE' AND form_key=? AND status IN ('PENDING','SENT','FAILED','SENDING')", new Object[] {formKey});
  }

  private void cancelBy(String sql, Object[] args) {
    for (Map<String, Object> parent : store.rows(sql, args)) {
      String status = str(parent.get("status"));
      if (PENDING.equals(status)
          || (FAILED.equals(status) && str(parent.get("todoTaskId")).isEmpty())) {
        store.update(
            "UPDATE rm_demand_push SET status=?,last_error='OBJECT_DELETED',update_time=CURRENT_TIMESTAMP WHERE id=? AND status IN (?,?)",
            CANCELLED,
            parent.get("id"),
            PENDING,
            FAILED);
      } else insertAction(parent, DELETE);
    }
  }

  private void insertAction(Map<String, Object> parent, String action) {
    try {
      store.update(
          "INSERT INTO rm_demand_push(id,notice_id,user_id,dingtalk_user_id,union_id,channel,action,kind,form_key,demand_id,publication_id,round_no,step_no,parent_id,source_id,todo_task_id,title,content,link_url,dedupe_key,status,next_retry_at) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
          id(),
          parent.get("noticeId"),
          parent.get("userId"),
          parent.get("dingtalkUserId"),
          parent.get("unionId"),
          TODO,
          action,
          parent.get("kind"),
          parent.get("formKey"),
          parent.get("demandId"),
          parent.get("publicationId"),
          parent.get("roundNo"),
          parent.get("stepNo"),
          parent.get("id"),
          parent.get("sourceId"),
          parent.get("todoTaskId"),
          parent.get("title"),
          parent.get("content"),
          parent.get("linkUrl"),
          hash("TODO_" + action + "\n" + parent.get("id")),
          PENDING,
          new Timestamp(System.currentTimeMillis()));
    } catch (DuplicateKeyException ignored) {
      /* 已安排完成/删除 */
    }
  }

  @Scheduled(
      fixedDelayString = "${ruima.demand.dingtalk.dispatch-delay-ms:30000}",
      initialDelayString = "15000")
  public void dispatchTick() {
    try {
      dispatch();
    } catch (Exception error) {
      log.warn("钉钉派发任务异常", error);
    }
  }

  public int dispatch() {
    if (!settings.enabled()) return 0;
    recoverExpired();
    List<Map<String, Object>> due =
        store.rows(
            "SELECT * FROM rm_demand_push WHERE status IN ('PENDING','FAILED') AND (next_retry_at IS NULL OR next_retry_at<=CURRENT_TIMESTAMP) ORDER BY create_time,id LIMIT ?",
            Math.max(1, properties.getDispatchBatchSize()));
    if (due.isEmpty()) return 0;
    Map<String, List<Map<String, Object>>> workGroups = new LinkedHashMap<>();
    List<Map<String, Object>> todoRows = new ArrayList<>();
    for (Map<String, Object> row : due) {
      if (WORK.equals(str(row.get("channel"))) && CREATE.equals(str(row.get("action")))) {
        String batch = str(row.get("batchKey"));
        workGroups.computeIfAbsent(batch.isEmpty() ? str(row.get("id")) : batch, k -> new ArrayList<>()).add(row);
      } else todoRows.add(row);
    }
    int sent = 0;
    for (List<Map<String, Object>> group : workGroups.values()) sent += processWorkGroup(group);
    for (Map<String, Object> row : todoRows) sent += processSingle(row) ? 1 : 0;
    return sent;
  }

  private int processWorkGroup(List<Map<String, Object>> group) {
    List<Map<String, Object>> claimed = new ArrayList<>();
    for (Map<String, Object> row : group) {
      if (!claim(row)) continue;
      String noticeId = str(row.get("noticeId"));
      if (!noticeId.isEmpty()) {
        Map<String, Object> notice =
            store.one("SELECT read_at FROM rm_demand_notice WHERE id=?", noticeId);
        if (notice != null && notice.get("readAt") != null) {
          finish(row, SKIPPED, null, "NOTICE_READ");
          continue;
        }
      }
      claimed.add(row);
    }
    if (claimed.isEmpty()) return 0;
    List<Map<String, Object>> sendable = new ArrayList<>();
    Map<String, String> currentMappings = new LinkedHashMap<>();
    List<String> userIds = new ArrayList<>();
    for (Map<String, Object> row : claimed) {
      Long userId = number(row.get("userId"));
      Map<String, Object> mapping = userId == null ? null : directory.confirmed(userId);
      String dingUserId =
          mapping == null ? "" : str(mapping.get("dingtalkUserId"));
      if (dingUserId.isEmpty()) {
        finish(row, SKIPPED, null, "NO_MAPPING");
        continue;
      }
      sendable.add(row);
      currentMappings.put(str(row.get("id")), dingUserId);
      store.update(
          "UPDATE rm_demand_push SET dingtalk_user_id=?,union_id=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
          dingUserId,
          emptyToNull(str(mapping.get("unionId"))),
          row.get("id"));
      if (!userIds.contains(dingUserId)) userIds.add(dingUserId);
    }
    if (sendable.isEmpty()) return 0;
    Map<String, Object> first = sendable.get(0);
    String title = str(first.get("title"));
    String content = str(first.get("content"));
    String link = str(first.get("linkUrl"));
    Map<String, Object> message = workMessage(title, content, link);
    int delivered = 0;
    for (int start = 0; start < userIds.size(); start += 100) {
      List<String> chunk = userIds.subList(start, Math.min(userIds.size(), start + 100));
      try {
        String taskId = client.sendWork(String.join(",", chunk), message);
        for (Map<String, Object> row : sendable)
          if (chunk.contains(currentMappings.get(str(row.get("id")))))
            finish(row, SENT, taskId, null);
        delivered += chunk.size();
      } catch (DemandDingTalkException error) {
        for (Map<String, Object> row : sendable)
          if (chunk.contains(currentMappings.get(str(row.get("id"))))) fail(row, error);
      }
    }
    return delivered;
  }

  private boolean processSingle(Map<String, Object> row) {
    if (!claim(row)) return false;
    String channel = str(row.get("channel"));
    String action = str(row.get("action"));
    try {
      if (TODO.equals(channel) && CREATE.equals(action)) {
        Long userId = number(row.get("userId"));
        Map<String, Object> mapping = userId == null ? null : directory.confirmed(userId);
        String unionId = mapping == null ? "" : str(mapping.get("unionId"));
        if (mapping == null || unionId.isEmpty()) {
          finish(row, SKIPPED, null, mapping == null ? "NO_MAPPING" : "NO_UNION");
          return false;
        }
        String creator = directory.creatorUnionId();
        if (creator == null || creator.isEmpty()) {
          finish(row, SKIPPED, null, "NO_CREATOR");
          return false;
        }
        store.update(
            "UPDATE rm_demand_push SET dingtalk_user_id=?,union_id=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
            str(mapping.get("dingtalkUserId")),
            unionId,
            row.get("id"));
        String taskId =
            client.createTodo(
                creator,
                unionId,
                str(row.get("title")),
                str(row.get("content")),
                row.get("dueAt") == null ? null : ((Timestamp) row.get("dueAt")).getTime(),
                str(row.get("linkUrl")),
                str(row.get("sourceId")));
        finish(row, SENT, null, null);
        store.update(
            "UPDATE rm_demand_push SET todo_task_id=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
            taskId,
            row.get("id"));
        return true;
      }
      if (TODO.equals(channel) && COMPLETE.equals(action)) {
        Map<String, Object> parent = parent(row);
        if (parent == null) {
          finish(row, SKIPPED, null, "PARENT_MISSING");
          return false;
        }
        String taskId = str(parent.get("todoTaskId"));
        if (taskId.isEmpty()) {
          String parentStatus = str(parent.get("status"));
          if (FAILED.equals(parentStatus)
              || DEAD.equals(parentStatus)
              || CANCELLED.equals(parentStatus)
              || SENT.equals(parentStatus)) {
            finish(row, SKIPPED, null, "TODO_NOT_CREATED");
            return false;
          }
          retryLater(row, "TODO_NOT_CREATED_YET");
          return false;
        }
        String creator = directory.creatorUnionId();
        if (creator == null || creator.isEmpty()) {
          finish(row, SKIPPED, null, "NO_CREATOR");
          return false;
        }
        client.completeTodo(creator, taskId);
        finish(row, SENT, null, null);
        store.update(
            "UPDATE rm_demand_push SET status=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND status=?",
            DONE,
            parent.get("id"),
            SENT);
        return true;
      }
      if (TODO.equals(channel) && DELETE.equals(action)) {
        Map<String, Object> parent = parent(row);
        if (parent == null) {
          finish(row, SKIPPED, null, "PARENT_MISSING");
          return false;
        }
        String taskId = str(parent.get("todoTaskId"));
        if (taskId.isEmpty()) {
          String parentStatus = str(parent.get("status"));
          if (PENDING.equals(parentStatus) || SENDING.equals(parentStatus)) {
            retryLater(row, "TODO_NOT_CREATED_YET");
            return false;
          }
          finish(row, SKIPPED, null, "TODO_NOT_CREATED");
          return false;
        }
        String creator = directory.creatorUnionId();
        if (creator == null || creator.isEmpty()) {
          finish(row, SKIPPED, null, "NO_CREATOR");
          return false;
        }
        client.deleteTodo(creator, taskId);
        finish(row, SENT, null, null);
        store.update(
            "UPDATE rm_demand_push SET status=?,update_time=CURRENT_TIMESTAMP WHERE id=?",
            CANCELLED,
            parent.get("id"));
        return true;
      }
      finish(row, SKIPPED, null, "UNKNOWN_ACTION");
      return false;
    } catch (DemandDingTalkException error) {
      fail(row, error);
      return false;
    } catch (Exception error) {
      fail(row, new DemandDingTalkException(-1, error.getClass().getSimpleName(), true));
      return false;
    }
  }

  private Map<String, Object> parent(Map<String, Object> row) {
    String parentId = str(row.get("parentId"));
    if (parentId.isEmpty()) return null;
    return store.one("SELECT * FROM rm_demand_push WHERE id=?", parentId);
  }

  private boolean claim(Map<String, Object> row) {
    String id = str(row.get("id"));
    String lock = UUID.randomUUID().toString();
    Timestamp lease =
        new Timestamp(System.currentTimeMillis() + Math.max(30, properties.getLeaseSeconds()) * 1000L);
    int updated =
        store.update(
            "UPDATE rm_demand_push SET status=?,attempts=attempts+1,lease_until=?,locked_by=?,update_time=CURRENT_TIMESTAMP WHERE id=? AND status IN ('PENDING','FAILED') AND (next_retry_at IS NULL OR next_retry_at<=CURRENT_TIMESTAMP)",
            SENDING,
            lease,
            lock,
            id);
    if (updated == 0) return false;
    row.put("lockedBy", lock);
    return true;
  }

  private void finish(Map<String, Object> row, String status, String providerTaskId, String error) {
    store.update(
        "UPDATE rm_demand_push SET status=?,provider_task_id=?,last_error=?,lease_until=NULL,locked_by=NULL,sent_at=CASE WHEN ?='SENT' THEN CURRENT_TIMESTAMP ELSE sent_at END,update_time=CURRENT_TIMESTAMP WHERE id=? AND locked_by=?",
        status,
        emptyToNull(providerTaskId),
        error,
        status,
        row.get("id"),
        row.get("lockedBy"));
  }

  private void fail(Map<String, Object> row, DemandDingTalkException error) {
    int attempts = intValue(row.get("attempts"));
    boolean permanent =
        !error.isTransientError()
            || error.getCode() == 143105
            || error.getCode() == 143106
            || error.getCode() == 60011
            || error.getCode() == 400035
            || error.getCode() == 885010;
    if (permanent) {
      finish(row, SKIPPED, null, clip("code=" + error.getCode() + ":" + error.getMessage(), 480));
      return;
    }
    if (attempts >= Math.max(1, properties.getMaxAttempts())) {
      finish(row, DEAD, null, clip("code=" + error.getCode() + ":" + error.getMessage(), 480));
      return;
    }
    long delay = backoffSeconds(attempts) * 1000L;
    Timestamp next = new Timestamp(System.currentTimeMillis() + delay);
    store.update(
        "UPDATE rm_demand_push SET status=?,next_retry_at=?,last_error=?,lease_until=NULL,locked_by=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND locked_by=?",
        FAILED,
        next,
        clip("code=" + error.getCode() + ":" + error.getMessage(), 480),
        row.get("id"),
        row.get("lockedBy"));
  }

  private void retryLater(Map<String, Object> row, String error) {
    Timestamp next = new Timestamp(System.currentTimeMillis() + 60_000L);
    store.update(
        "UPDATE rm_demand_push SET status=?,next_retry_at=?,last_error=?,lease_until=NULL,locked_by=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND locked_by=?",
        FAILED,
        next,
        error,
        row.get("id"),
        row.get("lockedBy"));
  }

  private void recoverExpired() {
    Timestamp now = new Timestamp(System.currentTimeMillis());
    List<Map<String, Object>> expired =
        store.rows("SELECT * FROM rm_demand_push WHERE status=? AND lease_until IS NOT NULL AND lease_until<?", SENDING, now);
    for (Map<String, Object> row : expired) {
      int attempts = intValue(row.get("attempts"));
      if (attempts >= Math.max(1, properties.getMaxAttempts()))
        store.update(
            "UPDATE rm_demand_push SET status=?,lease_until=NULL,locked_by=NULL,last_error='SENDING lease expired',update_time=CURRENT_TIMESTAMP WHERE id=?",
            DEAD,
            row.get("id"));
      else
        store.update(
            "UPDATE rm_demand_push SET status=?,next_retry_at=?,lease_until=NULL,locked_by=NULL,last_error='SENDING lease expired',update_time=CURRENT_TIMESTAMP WHERE id=?",
            FAILED,
            new Timestamp(System.currentTimeMillis() + 60_000L),
            row.get("id"));
    }
  }

  private Map<String, Object> workMessage(String title, String content, String link) {
    String body = clip(content, 500).replace("\r\n", "\n");
    if (body.contains("\n")) body = "- " + body.replace("\n", "\n- ");
    String text = "### " + title + "\n\n" + body;
    if (!link.isEmpty()) text += "\n\n[查看详情](" + link + ")";
    text = clipBytes(text, 1800);
    Map<String, Object> actionCard = new LinkedHashMap<>();
    actionCard.put("title", clip(title, 100));
    actionCard.put("markdown", text);
    actionCard.put("single_title", "查看详情");
    actionCard.put("single_url", link);
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("msgtype", "action_card");
    message.put("action_card", actionCard);
    return message;
  }

  private String link(DemandNoticeCreatedEvent event) {
    String base = settings.linkBase();
    if (base.isEmpty()) return "";
    String notice = "&notice=" + event.getNoticeId();
    if (DemandNotices.APPROVAL.equals(event.getKind()))
      return base + "/demand/approvals?id=" + event.getDemandId() + notice;
    if (DemandNotices.APPROVAL_RESULT.equals(event.getKind())
        || DemandNotices.REPLY.equals(event.getKind())
        || DemandNotices.RESULT.equals(event.getKind()))
      return base + "/demand/mine?id=" + event.getDemandId() + notice;
    if (DemandNotices.REQUIRED.equals(event.getKind()))
      return base + "/demand/home?notice=" + event.getNoticeId();
    if (DemandNotices.PUBLICATION.equals(event.getKind()))
      return base + "/demand/publications?id=" + event.getPublicationId() + notice;
    if (event.getDemandId() != null)
      return base + "/demand/pending?id=" + event.getDemandId() + notice;
    return base + "/demand/home";
  }

  private Timestamp nextAttempt(String kind) {
    LocalDateTime now = LocalDateTime.now(settings.zone());
    LocalDateTime allowed = settings.nextAllowed(now, kind);
    return Timestamp.from(allowed.atZone(settings.zone()).toInstant());
  }

  private Timestamp formDue(String formKey) {
    Map<String, Object> form =
        store.one(
            "SELECT round_mode,round_end_time,end_time FROM rm_demand_form WHERE form_key=?",
            formKey);
    if (form == null) return null;
    boolean periodic = DemandRounds.PERIODIC.equals(str(form.get("roundMode")));
    // 周期轮次取本轮截止；不启用轮次（SINGLE）取整单截止，并兼容历史单轮只写 round_end_time 的数据。
    Object value = periodic ? form.get("roundEndTime") : form.get("endTime");
    if (value == null) value = periodic ? form.get("endTime") : form.get("roundEndTime");
    if (value instanceof Timestamp) return (Timestamp) value;
    if (value instanceof java.util.Date) return new Timestamp(((java.util.Date) value).getTime());
    if (value instanceof java.time.LocalDateTime)
      return Timestamp.valueOf((java.time.LocalDateTime) value);
    return null;
  }

  public Map<String, Object> status() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("masterEnabled", properties.isEnabled());
    result.put("requestedEnabled", settings.flag("enabled", false));
    result.put("enabled", settings.enabled());
    result.put("configured", !blank(properties.getAppKey()) && !blank(properties.getAppSecret())
        && properties.getAgentId() > 0);
    result.put("todoEnabled", properties.isTodoEnabled());
    result.put("creatorConfigured", directory.creatorUnionId() != null);
    result.put("linkBase", settings.linkBase());
    result.put("events", events());
    result.put("quiet", quiet());
    result.put("mapping", directory.counts());
    result.put("pushes", pushCounts());
    return result;
  }

  public Map<String, Object> events() {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String kind : new String[] {
      DemandNotices.APPROVAL, DemandNotices.REQUIRED, DemandNotices.APPROVAL_RESULT,
      DemandNotices.REPLY, DemandNotices.RESULT, DemandNotices.ASSIGN,
      DemandNotices.FOLLOWUP, DemandNotices.NEW_DEMAND, DemandNotices.PUBLICATION
    }) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("work", settings.channel(kind, WORK));
      item.put("todo", settings.channel(kind, TODO));
      result.put(kind, item);
    }
    return result;
  }

  public Map<String, Object> quiet() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("start", settings.get("quiet.start", "20:00"));
    result.put("end", settings.get("quiet.end", "08:00"));
    result.put("bypassApproval", settings.flag("quiet.bypass.APPROVAL", true));
    return result;
  }

  public Map<String, Object> pushCounts() {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map<String, Object> row :
        store.rows("SELECT status,COUNT(*) AS total FROM rm_demand_push GROUP BY status"))
      result.put(str(row.get("status")), number(row.get("total")));
    result.put("total", store.count("SELECT COUNT(*) FROM rm_demand_push"));
    return result;
  }

  public Map<String, Object> pushes(String status, String channel, int page, int size) {
    StringBuilder where = new StringBuilder(" WHERE 1=1");
    List<Object> args = new ArrayList<>();
    if (status != null && !status.trim().isEmpty()) {
      where.append(" AND status=?");
      args.add(status.trim().toUpperCase(Locale.ROOT));
    }
    if (channel != null && !channel.trim().isEmpty()) {
      where.append(" AND channel=?");
      args.add(channel.trim().toUpperCase(Locale.ROOT));
    }
    long total =
        store.count("SELECT COUNT(*) FROM rm_demand_push" + where, args.toArray());
    int limit = Math.min(100, Math.max(1, size));
    List<Object> pageArgs = new ArrayList<>(args);
    pageArgs.add(limit);
    pageArgs.add((Math.max(1, page) - 1) * limit);
    List<Map<String, Object>> rows =
        store.rows(
            "SELECT id,notice_id,user_id,channel,action,kind,form_key,demand_id,round_no,step_no,parent_id,source_id,todo_task_id,provider_task_id,title,status,attempts,next_retry_at,last_error,sent_at,create_time FROM rm_demand_push"
                + where
                + " ORDER BY create_time DESC,id DESC LIMIT ? OFFSET ?",
            pageArgs.toArray());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("rows", rows);
    result.put("total", total);
    result.put("counts", pushCounts());
    return result;
  }

  @Transactional
  public void retry(String id) {
    int updated =
        store.update(
            "UPDATE rm_demand_push SET status=?,attempts=0,next_retry_at=CURRENT_TIMESTAMP,last_error=NULL,lease_until=NULL,locked_by=NULL,update_time=CURRENT_TIMESTAMP WHERE id=? AND status IN ('FAILED','DEAD','SKIPPED')",
            PENDING,
            id);
    if (updated == 0) throw new BaseException("该推送不可重试或已处理", 409);
  }

  @Transactional
  public void cleanup() {
    store.update(
        "DELETE FROM rm_demand_push WHERE status=? AND sent_at<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 30 DAY)",
        SENT);
    store.update(
        "DELETE FROM rm_demand_push WHERE status IN ('SKIPPED','DEAD') AND update_time<DATE_SUB(CURRENT_TIMESTAMP,INTERVAL 90 DAY)");
  }

  /** 测试发送：只发当前管理员本人，直接调用钉钉，不写 outbox；待办失败返回部分成功。 */
  public Map<String, Object> testSend(Long userId) {
    if (!settings.enabled()) throw new BaseException("钉钉推送未启用或配置不完整", 409);
    Map<String, Object> mapping = directory.confirmed(userId);
    if (mapping == null) throw new BaseException("当前账户未确认钉钉映射", 409);
    String dingUserId = str(mapping.get("dingtalkUserId"));
    String unionId = str(mapping.get("unionId"));
    String title = "【瑞玛问卷】钉钉推送测试";
    String link = settings.linkBase();
    String taskId = client.sendWork(dingUserId, workMessage(title, "这是一条测试消息，请忽略。", link));
    String todoTaskId = null;
    String todoStatus = "SKIPPED";
    String todoError = null;
    String creator = directory.creatorUnionId();
    if (!properties.isTodoEnabled()) {
      todoError = "TODO_DISABLED";
    } else if (creator == null || creator.isEmpty()) {
      todoError = "NO_CREATOR";
    } else if (unionId.isEmpty()) {
      todoError = "NO_UNION";
    } else {
      try {
        todoTaskId =
            client.createTodo(
                creator,
                unionId,
                title,
                "这是一条测试待办，请在钉钉待办中完成。",
                System.currentTimeMillis() + 3600_000L,
                link,
                "TEST:" + UUID.randomUUID());
        todoStatus = "SENT";
      } catch (DemandDingTalkException error) {
        todoStatus = "FAILED";
        todoError = clip("code=" + error.getCode() + ":" + error.getMessage(), 480);
      } catch (Exception error) {
        todoStatus = "FAILED";
        todoError = clip(error.getClass().getSimpleName() + ":" + error.getMessage(), 480);
      }
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("taskId", taskId);
    result.put("todoTaskId", todoTaskId);
    result.put("todoStatus", todoStatus);
    result.put("todoError", todoError);
    return result;
  }

  private static long backoffSeconds(int attempts) {
    if (attempts <= 1) return 60;
    if (attempts == 2) return 300;
    if (attempts == 3) return 1800;
    if (attempts == 4) return 7200;
    return 21600;
  }

  private static String clipBytes(String value, int maxBytes) {
    if (value == null) return "";
    if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) return value;
    int end = value.length();
    while (end > 0 && value.substring(0, end).getBytes(StandardCharsets.UTF_8).length > maxBytes)
      end--;
    return value.substring(0, end);
  }

  private static String clip(String value, int max) {
    String text = value == null ? "" : value.trim();
    return text.length() <= max ? text : text.substring(0, max);
  }

  private static int intValue(Object value) {
    Long number = number(value);
    return number == null ? 0 : number.intValue();
  }

  private static String emptyToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }

  private static boolean blank(String value) {
    return value == null || value.trim().isEmpty();
  }
}
