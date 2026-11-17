package com.tduck.cloud.form.util;


import cn.hutool.core.util.ObjectUtil;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.SecurityUtils;
import com.tduck.cloud.common.util.SpringContextUtils;
import com.tduck.cloud.form.entity.UserFormEntity;
import com.tduck.cloud.form.service.UserFormService;
import lombok.experimental.UtilityClass;


/**
 * 表单权限工具类
 *
 * @author tduck
 */
@UtilityClass
public class FormAuthUtils {


    /**
     * 是否拥有表单的权限
     */
    public void hasPermission(String formKey) {
        hasPermission(formKey, false);
    }

    /**
     * 是否拥有表单的权限；恢复回收站表单时允许读取已删除记录。
     */
    public void hasPermission(String formKey, boolean allowDeleted) {
        UserFormService userFormService = SpringContextUtils.getBean(UserFormService.class);
        UserFormEntity userFormEntity = userFormService.getByKey(formKey);
        if (!allowDeleted && ObjectUtil.isNotNull(userFormEntity)
                && Boolean.TRUE.equals(userFormEntity.getDeleted())) {
            throw new BaseException("表单已删除");
        }
        for (com.tduck.cloud.common.util.FormManagementExtension extension : SpringContextUtils.context().getBeansOfType(com.tduck.cloud.common.util.FormManagementExtension.class).values()) {
            if (extension.canManage(SecurityUtils.getUserId(), formKey)) return;
        }
        // 是否是超级管理员
        if (SecurityUtils.isAdmin(SecurityUtils.getUserId())) {
            return;
        }
        if(ObjectUtil.isNull(userFormEntity)){
            return;
        }
        // 是否是所有者
        if (!userFormEntity.getUserId().equals(SecurityUtils.getUserId())) {
            throw new BaseException("无表单权限");
        }
    }


}
