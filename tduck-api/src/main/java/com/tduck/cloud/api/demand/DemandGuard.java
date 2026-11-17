package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.api.annotation.NotLogin;
import com.tduck.cloud.common.exception.BaseException;
import java.util.Arrays;
import javax.annotation.security.PermitAll;
import javax.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** 同时保护原后台入口和原公开填报元数据，不能只靠隐藏菜单。 */
@Component
@RequiredArgsConstructor
public class DemandGuard implements HandlerInterceptor {
  private final DemandAccess access;

  @Override
  public boolean preHandle(HttpServletRequest r, HttpServletResponse response, Object handler) {
    if (!(handler instanceof HandlerMethod)) return true;
    String path = r.getServletPath();
    Long userId = number(r.getAttribute("userId"));
    if (path.startsWith("/ai/")) {
      access.requireActive(userId);
      if (!access.consoleAdmin(userId)) throw new BaseException("仅后台管理员可使用 AI 功能", 403);
    }
    if (!access.enabled()) return true;
    HandlerMethod method = (HandlerMethod) handler;
    if (path.startsWith("/demand-api/") || path.startsWith("/user/form")) access.requireActive(userId);
    boolean publicMethod =
        method.hasMethodAnnotation(PermitAll.class) || method.hasMethodAnnotation(NotLogin.class);
    if (path.startsWith("/user/form") && !publicMethod && !path.equals("/user/form/data/create")) {
      if (!access.globalAdmin(userId)
          && !(access.consoleAdmin(userId) && consoleFormAllowed(path)))
        throw new BaseException("仅全局管理员可执行此操作", 403);
    }
    if (path.startsWith("/user/form/details/")
        || path.startsWith("/user/form/public/settings/")
        || path.startsWith("/form/file/upload/")) {
      String key = path.substring(path.lastIndexOf('/') + 1);
      if (access.form(key) != null && !access.table(key, userId))
        access.requireFill(key, userId, userId == null);
    }
    return true;
  }

  /** 后台管理员可用的建单与原表单设计入口（含表单级通知设置）；未列出的仍仅全局管理员。 */
  private boolean consoleFormAllowed(String path) {
    if (path.equals("/user/form/create")
        || path.equals("/user/form/use-template/create")
        || path.equals("/user/form/page")
        || path.equals("/user/form/list")
        || path.equals("/user/form/folderTree")
        || path.equals("/user/form/update")
        || path.equals("/user/form/publish")
        || path.equals("/user/form/stop")
        || path.equals("/user/form/delete")
        || path.equals("/user/form/logic/delete")) return true;
    if (path.startsWith("/user/form/item/")
        || path.startsWith("/user/form/theme/")
        || path.startsWith("/user/form/setting/")
        || path.startsWith("/user/form/logic")
        || path.startsWith("/user/form/fields/")
        || path.startsWith("/user/form/fixed/fields/")
        || path.startsWith("/user/form/wx/")) return true;
    if (!path.startsWith("/user/form/")) return false;
    String key = path.substring("/user/form/".length());
    if (key.isEmpty() || key.contains("/")) return false;
    return !Arrays.asList(
            "create",
            "use-template",
            "page",
            "list",
            "folderTree",
            "update",
            "publish",
            "stop",
            "delete",
            "logic",
            "data",
            "report",
            "recycle",
            "wx",
            "link",
            "setting-status")
        .contains(key);
  }
}
