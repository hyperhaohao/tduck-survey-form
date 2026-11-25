package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 部门主管审批：提交待审批、通过与驳回、处理门禁。 */
@Service
@RequiredArgsConstructor
public class DemandApproval {
  public static final String NONE = "NONE";
  public static final String PENDING = "PENDING";
  public static final String APPROVED = "APPROVED";
  public static final String REJECTED = "REJECTED";

  private final DemandStore store;
  private final DemandAccess access;
  private final DemandNotices notices;

  /** 提交后启动审批；返回审批人 id，未启用或无可用主管时返回 null。 */
  @Transactional
  public Long start(Map<String, Object> demand) {
    String demandId = str(demand.get("id"));
    String formKey = str(demand.get("formKey"));
    Long submitter = number(demand.get("submitterId"));
    if (demandId.isEmpty() || formKey.isEmpty()) return null;
    Map<String, Object> form = access.form(formKey);
    if (form == null || !flag(form.get("requiresLeaderApproval"))) return null;
    if (submitter == null || flag(demand.get("anonymous"))) return null;
    Long leader = leader(submitter);
    if (leader == null) {
      event(demandId, submitter, "未配置部门主管，需求直接进入处理");
      return null;
    }
    store.update(
        "UPDATE rm_demand SET approval_status=?,approval_leader_id=?,approval_comment=NULL,approval_at=NULL WHERE id=?",
        PENDING,
        leader,
        demandId);
    event(demandId, submitter, "提交待部门主管审批：" + access.name(leader));
    notices.approvalTask(demand, leader);
    return leader;
  }

  /** 部门主管通过或驳回。 */
  @Transactional
  public void decide(String demandId, Map<String, Object> body, Long userId) {
    access.requireEnabled();
    Map<String, Object> demand =
        store.one("SELECT * FROM rm_demand WHERE id=? FOR UPDATE", demandId);
    if (demand == null) throw new BaseException("需求不存在或无权访问", 403);
    if (userId == null
        || !userId.equals(number(demand.get("approvalLeaderId")))
        || !PENDING.equals(str(demand.get("approvalStatus"))))
      throw new BaseException("仅当前部门主管可审批", 403);
    if (number(body.get("revision")) == null
        || !number(body.get("revision")).equals(number(demand.get("revision"))))
      throw new BaseException("需求已变化，请刷新后重试", 409);
    String decision = str(body.get("decision")).trim().toUpperCase(Locale.ROOT);
    String comment = body.get("comment") == null ? "" : body.get("comment").toString().trim();
    if (comment.length() > 2000) throw new BaseException("审批意见不能超过2000字");
    if ("APPROVE".equals(decision)) {
      store.update(
          "UPDATE rm_demand SET approval_status=?,approval_comment=?,approval_at=CURRENT_TIMESTAMP,revision=revision+1,public_version=public_version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
          APPROVED,
          comment,
          demandId);
      event(demandId, userId, comment.isEmpty() ? "部门主管通过审批" : "部门主管通过审批：" + comment);
      notices.approvalResult(demand, true, comment);
      notices.newDemand(demand);
    } else if ("REJECT".equals(decision)) {
      if (comment.isEmpty()) throw new BaseException("驳回时请填写审批意见");
      store.update(
          "UPDATE rm_demand SET approval_status=?,approval_comment=?,approval_at=CURRENT_TIMESTAMP,revision=revision+1,public_version=public_version+1,update_time=CURRENT_TIMESTAMP WHERE id=?",
          REJECTED,
          comment,
          demandId);
      event(demandId, userId, "部门主管驳回审批：" + comment);
      notices.approvalResult(demand, false, comment);
    } else throw new BaseException("审批动作无效");
  }

  /** 处理动作前置门禁：待审批或已驳回时不允许处理。 */
  public void requireApproved(Map<String, Object> demand) {
    String status = str(demand.get("approvalStatus"));
    if (PENDING.equals(status)) throw new BaseException("需求待部门主管审批", 409);
    if (REJECTED.equals(status)) throw new BaseException("需求审批未通过", 409);
  }

  /** 当前用户是否该需求的待审批人。 */
  public boolean canApprove(Map<String, Object> demand, Long userId) {
    return userId != null
        && PENDING.equals(str(demand.get("approvalStatus")))
        && userId.equals(number(demand.get("approvalLeaderId")));
  }

  /** 解析填报人第一个有效部门主管；无主管或仅本人时返回 null。 */
  public Long leader(Long submitterId) {
    if (submitterId == null) return null;
    Map<String, Object> row =
        store.one(
            "SELECT d.leader_id FROM rm_demand_user_dept ud "
                + "JOIN rm_demand_dept d ON d.dept_key=ud.dept_key AND d.active=TRUE "
                + "JOIN ac_user u ON u.id=d.leader_id AND u.deleted=FALSE "
                + "WHERE ud.user_id=? AND d.leader_id IS NOT NULL AND d.leader_id<>? "
                + "ORDER BY d.sort_no,d.name LIMIT 1",
            submitterId,
            submitterId);
    return row == null ? null : number(row.get("leaderId"));
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

  private static String str(Object value) {
    return value == null ? "" : value.toString();
  }
}
