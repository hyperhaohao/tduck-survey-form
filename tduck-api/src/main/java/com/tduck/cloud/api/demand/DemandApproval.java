package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 部门主管审批：直属主管与逐级审批、同级或签、审批步骤与处理门禁。 */
@Service
@RequiredArgsConstructor
public class DemandApproval {
  public static final String NONE = "NONE";
  public static final String DIRECT = "DIRECT";
  public static final String HIERARCHY = "HIERARCHY";
  public static final String WAITING = "WAITING";
  public static final String PENDING = "PENDING";
  public static final String APPROVED = "APPROVED";
  public static final String REJECTED = "REJECTED";
  public static final String SKIPPED = "SKIPPED";

  private final DemandStore store;
  private final DemandAccess access;
  private final DemandNotices notices;

  /** 提交后按收集单审批模式启动审批；返回首个审批人或 1，未启动返回 null。 */
  @Transactional
  public Long start(Map<String, Object> demand) {
    String demandId = str(demand.get("id"));
    String formKey = str(demand.get("formKey"));
    Long submitter = number(demand.get("submitterId"));
    if (demandId.isEmpty() || formKey.isEmpty()) return null;
    Map<String, Object> form = access.form(formKey);
    if (form == null) return null;
    String mode = mode(form);
    if (NONE.equals(mode)) return null;
    if (submitter == null || flag(demand.get("anonymous"))) return null;
    List<Level> chain = chain(submitter, mode);
    if (chain.isEmpty()) {
      event(demandId, submitter, "未配置部门主管，需求直接进入处理");
      return null;
    }
    Long firstApprover = null;
    int stepNo = 0;
    for (Level level : chain) {
      stepNo++;
      String stepId = id();
      store.update(
          "INSERT INTO rm_demand_approval_step(id,demand_id,step_no,dept_key,dept_name,status) VALUES(?,?,?,?,?,?)",
          stepId,
          demandId,
          stepNo,
          level.deptKey,
          level.deptName,
          stepNo == 1 ? PENDING : WAITING);
      for (Approver approver : level.approvers) {
        store.update(
            "INSERT INTO rm_demand_approval_approver(step_id,user_id,user_name) VALUES(?,?,?)",
            stepId,
            approver.userId,
            approver.userName);
        if (firstApprover == null) firstApprover = approver.userId;
      }
    }
    store.update(
        "UPDATE rm_demand SET approval_mode=?,approval_status=?,approval_step=1,approval_leader_id=?,approval_comment=NULL,approval_at=NULL WHERE id=?",
        mode,
        PENDING,
        firstApprover,
        demandId);
    Level first = chain.get(0);
    event(
        demandId,
        submitter,
        "提交待审批：第1级 " + first.deptName + "（" + names(first.approvers) + "）");
    notices.approvalTask(demand, 1, first.deptName, approverMaps(first.approvers));
    return firstApprover == null ? 1L : firstApprover;
  }

  /** 当前级主管通过或驳回；同级或签，先到先得。 */
  @Transactional
  public void decide(String demandId, Map<String, Object> body, Long userId) {
    access.requireEnabled();
    Map<String, Object> demand =
        store.one("SELECT * FROM rm_demand WHERE id=? FOR UPDATE", demandId);
    if (demand == null) throw new BaseException("需求不存在或无权访问", 403);
    if (!PENDING.equals(str(demand.get("approvalStatus"))))
      throw new BaseException("需求不在审批中", 409);
    long currentStep = number(demand.get("approvalStep"));
    Map<String, Object> step =
        store.one(
            "SELECT * FROM rm_demand_approval_step WHERE demand_id=? AND step_no=? FOR UPDATE",
            demandId,
            currentStep);
    if (step == null || !PENDING.equals(str(step.get("status"))))
      throw new BaseException("审批步骤已变化，请刷新后重试", 409);
    String stepId = str(step.get("id"));
    if (userId == null || !isApprover(stepId, userId))
      throw new BaseException("仅当前审批人可操作", 403);
    if (number(body.get("revision")) == null
        || !number(body.get("revision")).equals(number(demand.get("revision"))))
      throw new BaseException("需求已变化，请刷新后重试", 409);
    String decision = str(body.get("decision")).trim().toUpperCase(Locale.ROOT);
    String comment = body.get("comment") == null ? "" : body.get("comment").toString().trim();
    if (comment.length() > 2000) throw new BaseException("审批意见不能超过2000字");
    if ("APPROVE".equals(decision)) {
      store.update(
          "UPDATE rm_demand_approval_step SET status=?,comment_text=?,acted_by=?,acted_at=CURRENT_TIMESTAMP WHERE id=?",
          APPROVED,
          comment,
          userId,
          stepId);
      notices.readApprovalStep(demandId, (int) currentStep);
      Map<String, Object> next =
          store.one(
              "SELECT id,step_no,dept_name FROM rm_demand_approval_step WHERE demand_id=? AND step_no=? ORDER BY step_no LIMIT 1",
              demandId,
              currentStep + 1);
      if (next != null) {
        long nextStep = number(next.get("stepNo"));
        store.update(
            "UPDATE rm_demand_approval_step SET status=? WHERE id=?", PENDING, next.get("id"));
        store.update(
            "UPDATE rm_demand SET approval_step=?,approval_leader_id=?,approval_comment=?,approval_at=CURRENT_TIMESTAMP,revision=revision+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
            nextStep,
            userId,
            comment,
            demandId);
        event(
            demandId,
            userId,
            "第"
                + currentStep
                + "级通过（"
                + str(step.get("deptName"))
                + "）"
                + (comment.isEmpty() ? "" : "：" + comment)
                + "；流转至第"
                + nextStep
                + "级 "
                + str(next.get("deptName")));
        notices.approvalTask(
            demand,
            (int) nextStep,
            str(next.get("deptName")),
            approverMaps(approvers(str(next.get("id")))));
      } else {
        store.update(
            "UPDATE rm_demand SET approval_status=?,approval_step=?,approval_leader_id=?,approval_comment=?,approval_at=CURRENT_TIMESTAMP,revision=revision+1,public_version=public_version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
            APPROVED,
            currentStep,
            userId,
            comment,
            demandId);
        event(
            demandId,
            userId,
            "第"
                + currentStep
                + "级通过（"
                + str(step.get("deptName"))
                + "），审批完成"
                + (comment.isEmpty() ? "" : "：" + comment));
        notices.approvalResult(demand, true, comment);
        notices.newDemand(demand);
      }
    } else if ("REJECT".equals(decision)) {
      if (comment.isEmpty()) throw new BaseException("驳回时请填写审批意见");
      store.update(
          "UPDATE rm_demand_approval_step SET status=?,comment_text=?,acted_by=?,acted_at=CURRENT_TIMESTAMP WHERE id=?",
          REJECTED,
          comment,
          userId,
          stepId);
      store.update(
          "UPDATE rm_demand_approval_step SET status=? WHERE demand_id=? AND status=?",
          SKIPPED,
          demandId,
          WAITING);
      store.update(
          "UPDATE rm_demand SET approval_status=?,approval_step=?,approval_leader_id=?,approval_comment=?,approval_at=CURRENT_TIMESTAMP,revision=revision+1,public_version=public_version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
          REJECTED,
          currentStep,
          userId,
          comment,
          demandId);
      notices.readApprovalStep(demandId, (int) currentStep);
      event(
          demandId,
          userId,
          "第" + currentStep + "级驳回（" + str(step.get("deptName")) + "）：" + comment);
      notices.approvalResult(demand, false, comment);
    } else throw new BaseException("审批动作无效");
  }

  /** 处理动作前置门禁：待审批或已驳回时不允许处理。 */
  public void requireApproved(Map<String, Object> demand) {
    String status = str(demand.get("approvalStatus"));
    if (PENDING.equals(status)) throw new BaseException("需求待部门主管审批", 409);
    if (REJECTED.equals(status)) throw new BaseException("需求审批未通过", 409);
  }

  /** 当前用户是否当前步骤的待审批人。 */
  public boolean canApprove(Map<String, Object> demand, Long userId) {
    if (userId == null
        || !PENDING.equals(str(demand.get("approvalStatus")))
        || demand.get("id") == null) return false;
    Map<String, Object> step =
        store.one(
            "SELECT id,status FROM rm_demand_approval_step WHERE demand_id=? AND step_no=?",
            demand.get("id"),
            number(demand.get("approvalStep")));
    return step != null
        && PENDING.equals(str(step.get("status")))
        && isApprover(str(step.get("id")), userId);
  }

  /** 列表批量补充当前级、部门与候选人，避免逐行查询。 */
  public Map<String, Map<String, Object>> summaries(List<String> demandIds) {
    Map<String, Map<String, Object>> result = new LinkedHashMap<>();
    if (demandIds == null || demandIds.isEmpty()) return result;
    StringBuilder placeholders = new StringBuilder();
    for (int i = 0; i < demandIds.size(); i++) {
      if (i > 0) placeholders.append(',');
      placeholders.append('?');
    }
    Map<String, Integer> totals = new LinkedHashMap<>();
    for (Map<String, Object> step :
        store.rows(
            "SELECT demand_id,step_no,dept_name,status FROM rm_demand_approval_step "
                + "WHERE demand_id IN ("
                + placeholders
                + ") ORDER BY demand_id,step_no",
            demandIds.toArray())) {
      String demandId = str(step.get("demandId"));
      totals.put(demandId, totals.getOrDefault(demandId, 0) + 1);
      Map<String, Object> summary = result.computeIfAbsent(demandId, k -> new LinkedHashMap<>());
      if (PENDING.equals(str(step.get("status")))) {
        summary.put("approvalStep", step.get("stepNo"));
        summary.put("approvalDeptName", step.get("deptName"));
      }
    }
    Map<String, List<String>> names = new LinkedHashMap<>();
    for (Map<String, Object> approver :
        store.rows(
            "SELECT s.demand_id,a.user_name FROM rm_demand_approval_approver a "
                + "JOIN rm_demand_approval_step s ON s.id=a.step_id "
                + "WHERE s.demand_id IN ("
                + placeholders
                + ") AND s.status='PENDING' ORDER BY s.demand_id,a.user_name,a.user_id",
            demandIds.toArray()))
      names
          .computeIfAbsent(str(approver.get("demandId")), k -> new ArrayList<>())
          .add(str(approver.get("userName")));
    for (Map.Entry<String, Map<String, Object>> entry : result.entrySet()) {
      entry.getValue().put("approvalStepTotal", totals.getOrDefault(entry.getKey(), 0));
      entry
          .getValue()
          .put("approvalApprovers", names.getOrDefault(entry.getKey(), Collections.emptyList()));
    }
    return result;
  }

  /** 需求审批步骤与候选人，供详情展示。 */
  public List<Map<String, Object>> steps(String demandId) {
    List<Map<String, Object>> steps =
        store.rows(
            "SELECT s.id,s.demand_id,s.step_no,s.dept_key,s.dept_name,s.status,s.comment_text,s.acted_by,"
                + "s.acted_at,u.name AS acted_by_name FROM rm_demand_approval_step s "
                + "LEFT JOIN ac_user u ON u.id=s.acted_by WHERE s.demand_id=? ORDER BY s.step_no",
            demandId);
    Map<String, List<Map<String, Object>>> approvers = new LinkedHashMap<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT a.step_id,a.user_id,a.user_name FROM rm_demand_approval_approver a "
                + "JOIN rm_demand_approval_step s ON s.id=a.step_id WHERE s.demand_id=? "
                + "ORDER BY a.user_name,a.user_id",
            demandId))
      approvers
          .computeIfAbsent(str(row.get("stepId")), k -> new ArrayList<>())
          .add(body("userId", row.get("userId"), "name", row.get("userName")));
    for (Map<String, Object> step : steps) {
      step.put("approvers", approvers.getOrDefault(str(step.get("id")), Collections.emptyList()));
      step.put("comment", step.remove("commentText"));
    }
    return steps;
  }

  private String mode(Map<String, Object> form) {
    String mode = str(form.get("approvalMode")).trim().toUpperCase(Locale.ROOT);
    if (DIRECT.equals(mode) || HIERARCHY.equals(mode)) return mode;
    return flag(form.get("requiresLeaderApproval")) ? DIRECT : NONE;
  }

  /** 从填报人部门构建审批链；优先级：部门排序后第一个能产生步骤的部门。 */
  private List<Level> chain(Long submitter, String mode) {
    Map<String, Map<String, Object>> departments = new LinkedHashMap<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT dept_key,name,parent_key,path,sort_no FROM rm_demand_dept WHERE active=TRUE"))
      departments.put(str(row.get("deptKey")), row);
    for (Map<String, Object> primary :
        store.rows(
            "SELECT d.dept_key FROM rm_demand_user_dept ud "
                + "JOIN rm_demand_dept d ON d.dept_key=ud.dept_key AND d.active=TRUE "
                + "WHERE ud.user_id=? ORDER BY d.sort_no,d.name",
            submitter)) {
      List<Level> levels = new ArrayList<>();
      String key = str(primary.get("deptKey"));
      Set<String> seen = new HashSet<>();
      while (key != null && seen.add(key)) {
        Map<String, Object> department = departments.get(key);
        if (department == null) break;
        List<Approver> leaders = leaders(key, submitter);
        if (!leaders.isEmpty())
          levels.add(new Level(key, deptLabel(department, departments), leaders));
        if (DIRECT.equals(mode)) break;
        key = strOrNull(department.get("parentKey"));
      }
      if (!levels.isEmpty()) return levels;
    }
    return Collections.emptyList();
  }

  private List<Approver> leaders(String deptKey, Long submitter) {    List<Approver> result = new ArrayList<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT l.user_id,u.name FROM rm_demand_dept_leader l "
                + "JOIN ac_user u ON u.id=l.user_id AND u.deleted=FALSE "
                + "WHERE l.dept_key=? AND l.user_id<>? ORDER BY l.sort_no,u.name,l.user_id",
            deptKey,
            submitter))
      result.add(new Approver(number(row.get("userId")), str(row.get("name"))));
    return result;
  }

  private List<Approver> approvers(String stepId) {
    List<Approver> result = new ArrayList<>();
    for (Map<String, Object> row :
        store.rows(
            "SELECT user_id,user_name FROM rm_demand_approval_approver WHERE step_id=? ORDER BY user_name,user_id",
            stepId))
      result.add(new Approver(number(row.get("userId")), str(row.get("userName"))));
    return result;
  }

  private boolean isApprover(String stepId, Long userId) {
    return store.count(
            "SELECT COUNT(*) FROM rm_demand_approval_approver WHERE step_id=? AND user_id=?",
            stepId,
            userId)
        > 0;
  }

  /** 审批记录写入需求时间线，填报者可见。 */
  private void event(String demandId, Long authorId, String content) {
    store.update(
        "INSERT INTO rm_demand_message(id,demand_id,author_id,author_name,kind,content) VALUES(?,?,?,?,?,?)",
        id(),
        demandId,
        authorId,
        access.name(authorId),
        "EVENT",
        content);
  }

  private String names(List<Approver> approvers) {
    List<String> names = new ArrayList<>();
    for (Approver approver : approvers) names.add(approver.userName);
    return String.join("、", names);
  }

  private List<Map<String, Object>> approverMaps(List<Approver> approvers) {
    List<Map<String, Object>> rows = new ArrayList<>();
    for (Approver approver : approvers)
      rows.add(body("userId", approver.userId, "name", approver.userName));
    return rows;
  }

  /** 从根到当前部门的名称路径，写入审批步骤快照。 */
  private String deptLabel(
      Map<String, Object> department, Map<String, Map<String, Object>> departments) {
    List<String> names = new ArrayList<>();
    Set<String> seen = new HashSet<>();
    Map<String, Object> cursor = department;
    while (cursor != null && seen.add(str(cursor.get("deptKey")))) {
      names.add(str(cursor.get("name")));
      cursor = departments.get(strOrNull(cursor.get("parentKey")));
    }
    Collections.reverse(names);
    return String.join(" / ", names);
  }

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }

  private static String strOrNull(Object value) {
    return value == null ? null : value.toString();
  }

  private static Map<String, Object> body(Object... pairs) {
    Map<String, Object> map = new LinkedHashMap<>();
    for (int i = 0; i + 1 < pairs.length; i += 2) map.put(String.valueOf(pairs[i]), pairs[i + 1]);
    return map;
  }

  private static final class Level {
    private final String deptKey;
    private final String deptName;
    private final List<Approver> approvers;

    private Level(String deptKey, String deptName, List<Approver> approvers) {
      this.deptKey = deptKey;
      this.deptName = deptName;
      this.approvers = approvers;
    }
  }

  private static final class Approver {
    private final Long userId;
    private final String userName;

    private Approver(Long userId, String userName) {
      this.userId = userId;
      this.userName = userName;
    }
  }
}
