package com.tduck.cloud.api.demand;

import com.tduck.cloud.common.util.FormManagementExtension;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 将需求模块管理员权限衔接到原表单管理入口。 */
@Component
@RequiredArgsConstructor
public class DemandManagementExtension implements FormManagementExtension {
  private final DemandAccess access;

  @Override
  public boolean canManage(Long userId, String formKey) {
    return access.enabled() && access.consoleAdmin(userId) && access.manage(formKey, userId);
  }
}
