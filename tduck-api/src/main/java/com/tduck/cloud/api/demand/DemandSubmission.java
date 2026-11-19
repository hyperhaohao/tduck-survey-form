package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.form.entity.UserFormDataEntity;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** 原提交事务内完成需求关联，不复制原填报正文。 */
@Service
@RequiredArgsConstructor
public class DemandSubmission {
  private final DemandStore store;
  private final DemandAccess access;
  private final DemandFormFields formFields;
  /** 提交前验证权限并清除客户端伪造的身份和记录标识。 */
  public void before(UserFormDataEntity entity, Long userId, boolean backend) {
    if (access.form(entity.getFormKey()) == null) return;
    if (entity.getId() != null) throw new BaseException("新增需求不能指定记录标识");
    boolean anonymous = Boolean.TRUE.equals(entity.getDemandAnonymous());
    formFields.applyToSubmission(entity, userId, anonymous);
    if (backend) {
      access.requireGlobalAdmin(userId);
      entity.setCreateBy(String.valueOf(userId));
    } else {
      access.requireFill(entity.getFormKey(), userId, anonymous);
      entity.setCreateBy(anonymous ? null : String.valueOf(userId));
    }
    if (anonymous) {
      entity.setCreateBy(null);
      entity.setWxOpenId(null);
      entity.setWxUserInfo(null);
      entity.setSubmitUa(null);
    }
    entity.setUpdateBy(null);
  }
  /** 原表单保存成功后插入处理索引，匿名码只返回一次。 */
  public void after(
      UserFormDataEntity entity, Map<String, Object> result, Long userId, boolean backend) {
    if (access.form(entity.getFormKey()) == null) return;
    boolean anonymous = Boolean.TRUE.equals(entity.getDemandAnonymous());
    String queryCode = anonymous ? token() : null;
    String demandId = id();
    String requestNo =
        "REQ-"
            + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)
            + "-"
            + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
    String title = "需求反馈";
    if (entity.getOriginalData() != null)
      for (Object v : entity.getOriginalData().values()) {
        if (v instanceof String && !v.toString().trim().isEmpty()) {
          title = v.toString().trim();
          break;
        }
      }
    if (title.length() > 200) title = title.substring(0, 200);
    store.update(
        "INSERT INTO rm_demand(id,data_id,form_key,request_no,title,submitter_id,submitter_name,anonymous,submitted_by,query_hash) VALUES(?,?,?,?,?,?,?,?,?,?)",
        demandId,
        String.valueOf(entity.getId()),
        entity.getFormKey(),
        requestNo,
        title,
        anonymous ? null : userId,
        anonymous ? "匿名" : access.name(userId),
        anonymous,
        backend ? userId : null,
        queryCode == null ? null : hash(queryCode));
    Map<String, Object> receipt = new LinkedHashMap<>();
    receipt.put("id", demandId);
    receipt.put("requestNo", requestNo);
    receipt.put("anonymous", anonymous);
    receipt.put("queryCode", queryCode);
    result.put("demandReceipt", receipt);
  }
}
