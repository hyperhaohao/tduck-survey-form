package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.Result;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 钉钉推送后台：状态、设置、通讯录映射、测试与日志；仅全局管理员。 */
@RestController
@RequestMapping("/demand-api/admin/dingtalk")
@RequiredArgsConstructor
public class DemandDingTalkController {
  private final DemandAccess access;
  private final DemandPushSettings settings;
  private final DemandPush push;
  private final DemandDingTalkDirectory directory;
  private final DemandDingTalkClient client;

  private Long user(HttpServletRequest request) {
    return number(request.getAttribute("userId"));
  }

  @GetMapping("/status")
  public Result<?> status(HttpServletRequest request) {
    access.requireGlobalAdmin(user(request));
    return Result.success(push.status());
  }

  @PutMapping("/settings")
  public Result<?> save(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    Long userId = user(request);
    access.requireGlobalAdmin(userId);
    settings.save(body, userId);
    return Result.success(push.status());
  }

  @PostMapping("/test-connection")
  public Result<?> testConnection(HttpServletRequest request) {
    access.requireGlobalAdmin(user(request));
    long started = System.currentTimeMillis();
    String workToken = client.workToken();
    String todoToken = client.todoToken();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("workToken", workToken != null && !workToken.isEmpty());
    result.put("todoToken", todoToken != null && !todoToken.isEmpty());
    result.put("latencyMs", System.currentTimeMillis() - started);
    return Result.success(result);
  }

  @PostMapping("/sync")
  public Result<?> sync(HttpServletRequest request) {
    access.requireGlobalAdmin(user(request));
    return Result.success(directory.sync());
  }

  @GetMapping("/users")
  public Result<?> users(
      @RequestParam(required = false) String state,
      @RequestParam(required = false) String keyword,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest request) {
    access.requireGlobalAdmin(user(request));
    return Result.success(directory.list(state, keyword, page, size));
  }

  @PostMapping("/users/{dingtalkUserId}/bind")
  public Result<?> bind(
      @PathVariable String dingtalkUserId,
      @RequestBody Map<String, Object> body,
      HttpServletRequest request) {
    Long operator = user(request);
    access.requireGlobalAdmin(operator);
    directory.bind(dingtalkUserId, number(body.get("userId")), operator);
    return Result.success(true);
  }

  @PostMapping("/users/{dingtalkUserId}/unbind")
  public Result<?> unbind(@PathVariable String dingtalkUserId, HttpServletRequest request) {
    Long operator = user(request);
    access.requireGlobalAdmin(operator);
    directory.unbind(dingtalkUserId, operator);
    return Result.success(true);
  }

  @PostMapping("/users/{dingtalkUserId}/confirm")
  public Result<?> confirm(@PathVariable String dingtalkUserId, HttpServletRequest request) {
    Long operator = user(request);
    access.requireGlobalAdmin(operator);
    directory.confirm(dingtalkUserId, operator);
    return Result.success(true);
  }

  @PostMapping("/confirm-matched")
  public Result<?> confirmMatched(HttpServletRequest request) {
    Long operator = user(request);
    access.requireGlobalAdmin(operator);
    return Result.success(directory.confirmMatched(operator));
  }

  @GetMapping("/local-users")
  public Result<?> localUsers(
      @RequestParam(required = false) String keyword, HttpServletRequest request) {
    access.requireGlobalAdmin(user(request));
    return Result.success(directory.localUsers(keyword));
  }

  @PostMapping("/test")
  public Result<?> test(@RequestBody(required = false) Map<String, Object> body, HttpServletRequest request) {
    Long operator = user(request);
    access.requireGlobalAdmin(operator);
    return Result.success(push.testSend(operator));
  }

  @GetMapping("/pushes")
  public Result<?> pushes(
      @RequestParam(required = false) String status,
      @RequestParam(required = false) String channel,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest request) {
    access.requireGlobalAdmin(user(request));
    return Result.success(push.pushes(status, channel, page, size));
  }

  @PostMapping("/pushes/{id}/retry")
  public Result<?> retry(@PathVariable String id, HttpServletRequest request) {
    access.requireGlobalAdmin(user(request));
    push.retry(id);
    return Result.success(true);
  }

  @PostMapping("/cleanup")
  public Result<?> cleanup(HttpServletRequest request) {
    access.requireGlobalAdmin(user(request));
    push.cleanup();
    return Result.success(true);
  }
}
