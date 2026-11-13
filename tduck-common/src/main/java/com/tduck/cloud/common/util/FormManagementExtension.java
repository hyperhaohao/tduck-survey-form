package com.tduck.cloud.common.util;

/** 可选业务管理扩展，未启用时沿用原表单所有者权限。 */
public interface FormManagementExtension {
    /** 判断扩展业务是否授予此用户管理表单的权限。 */
    boolean canManage(Long userId,String formKey);
}
