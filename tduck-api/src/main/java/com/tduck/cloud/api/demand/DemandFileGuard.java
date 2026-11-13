package com.tduck.cloud.api.demand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.servlet.*;
import javax.servlet.http.*;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.DigestUtils;
import org.springframework.web.filter.OncePerRequestFilter;

/** 受管需求的原始附件禁止通过静态地址绕过本人及经办权限。 */
@Component
@Order(-110)
@RequiredArgsConstructor
public class DemandFileGuard extends OncePerRequestFilter {
  private final DemandAccess access;
  private final DemandStore store;

  @Override
  protected void doFilterInternal(
      HttpServletRequest r, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String path =
        org.springframework.util.StringUtils.cleanPath(r.getServletPath().replace('\\', '/'))
            .replaceAll("/{2,}", "/");
    if (access.enabled() && path.startsWith("/u/")) {
      String[] parts = path.split("/");
      if (parts.length > 2) {
        for (Map<String, Object> form : store.rows("SELECT form_key FROM rm_demand_form")) {
          String digest =
              DigestUtils.md5DigestAsHex(
                  form.get("formKey").toString().getBytes(StandardCharsets.UTF_8));
          if (digest.equalsIgnoreCase(parts[2])) {
            response.setStatus(403);
            response.setHeader("Cache-Control", "no-store");
            return;
          }
        }
      }
    }
    chain.doFilter(r, response);
  }
}
