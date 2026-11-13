package com.tduck.cloud.api.demand;

import com.tduck.cloud.common.exception.BaseException;
import java.util.concurrent.ConcurrentHashMap;
import javax.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/** 匿名凭证查询限速，失败不区分编号与凭证是否存在。 */
@Component
public class DemandRateLimit implements HandlerInterceptor {
  private final ConcurrentHashMap<String, long[]> windows = new ConcurrentHashMap<>();

  @Override
  public boolean preHandle(HttpServletRequest r, HttpServletResponse response, Object handler) {
    String path = r.getServletPath();
    if (!path.equals("/demand-api/lookup") && !path.endsWith("/attachment")) return true;
    long now = System.currentTimeMillis();
    String key = r.getRemoteAddr();
    if (windows.size() > 10000) windows.entrySet().removeIf(e -> now - e.getValue()[0] > 60000);
    long[] window =
        windows.compute(
            key,
            (k, v) ->
                v == null || now - v[0] > 60000
                    ? new long[] {now, 1}
                    : new long[] {v[0], v[1] + 1});
    if (window[1] > 30) throw new BaseException("查询过于频繁，请稍后重试", 429);
    return true;
  }
}
