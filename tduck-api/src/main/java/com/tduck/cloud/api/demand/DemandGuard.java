package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.api.annotation.NotLogin;
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
    if (!access.enabled() || !(handler instanceof HandlerMethod)) return true;
    String path = r.getServletPath();
    Long userId = number(r.getAttribute("userId"));
    HandlerMethod method = (HandlerMethod) handler;
    if (path.startsWith("/demand-api/") || path.startsWith("/user/form")) access.requireActive(userId);
    boolean publicMethod =
        method.hasMethodAnnotation(PermitAll.class) || method.hasMethodAnnotation(NotLogin.class);
    if (path.startsWith("/user/form") && !publicMethod && !path.equals("/user/form/data/create"))
      access.requireAdmin(userId);
    if (path.startsWith("/user/form/details/")
        || path.startsWith("/user/form/public/settings/")
        || path.startsWith("/form/file/upload/")) {
      String key = path.substring(path.lastIndexOf('/') + 1);
      if (access.form(key) != null && !access.table(key, userId))
        access.requireFill(key, userId, userId == null);
    }
    return true;
  }
}
