package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.api.annotation.NotLogin;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.Result;
import java.util.*;
import javax.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseCookie;
import org.springframework.web.bind.annotation.*;

/** 面向员工的登录入口，不在界面暴露身份服务名称。 */
@RestController
@RequestMapping("/auth/oidc")
@RequiredArgsConstructor
public class OidcController {
  private final OidcIdentityService service;
  private final OidcProperties properties;
  /** 获取登录地址并设置短时 HttpOnly 浏览器绑定。 */
  @NotLogin
  @GetMapping("/start")
  public Result<?> start(
      @RequestParam(defaultValue = "/demand") String returnPath, HttpServletResponse response) {
    String browser = token();
    cookie(response, browser, 600);
    return Result.success(Collections.singletonMap("url", service.start(returnPath, browser)));
  }
  /** 授权码回调，失败信息不包含凭据或身份正文。 */
  @NotLogin
  @GetMapping("/callback")
  public void callback(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String code,
      @RequestParam(required = false) String error,
      HttpServletRequest request,
      HttpServletResponse response)
      throws java.io.IOException {
    response.setHeader("Cache-Control", "no-store");
    response.setHeader("Referrer-Policy", "no-referrer");
    try {
      if (error != null || state == null || code == null) throw new BaseException("登录未完成");
      Map<String, String> result = service.callback(state, code, browser(request));
      String fragment =
          "ticket="
              + java.net.URLEncoder.encode(result.get("ticket"), "UTF-8")
              + "&returnPath="
              + java.net.URLEncoder.encode(result.get("returnPath"), "UTF-8");
      response.sendRedirect(properties.getFrontendBase() + "/login/callback#" + fragment);
    } catch (BaseException e) {
      response.sendRedirect(properties.getFrontendBase() + "/login/entry?failed=1");
    }
  }
  /** 短时票据交换会话，成功后销毁浏览器绑定。 */
  @NotLogin
  @PostMapping("/exchange")
  public Result<?> exchange(
      @RequestBody Map<String, Object> body,
      HttpServletRequest request,
      HttpServletResponse response) {
    Object result =
        service.exchange(text(body.get("ticket"), 100), browser(request), request.getRemoteAddr());
    cookie(response, "", 0);
    response.setHeader("Cache-Control", "no-store");
    return Result.success(result);
  }
  /** 仅使用 HttpOnly Cookie 作为浏览器绑定。 */
  private String browser(HttpServletRequest r) {
    if (r.getCookies() != null)
      for (Cookie c : r.getCookies())
        if ("rm_login_browser".equals(c.getName())) return c.getValue();
    return null;
  }
  /** 依据正式回调协议配置 Secure；不在前端脚本存放绑定凭证。 */
  private void cookie(HttpServletResponse r, String value, int seconds) {
    r.addHeader(
        "Set-Cookie",
        ResponseCookie.from("rm_login_browser", value)
            .httpOnly(true)
            .secure(
                properties.getRedirectUri() != null
                    && properties.getRedirectUri().startsWith("https://"))
            .sameSite("Lax")
            .path("/")
            .maxAge(seconds)
            .build()
            .toString());
  }
}
