package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.api.annotation.NotLogin;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.storage.cloud.OssStorageFactory;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

/** 需求模块接口；普通用户只获得最小返回字段。 */
@RestController
@RequestMapping("/demand-api")
@RequiredArgsConstructor
public class DemandController {
  private final DemandAccess access;
  private final DemandForms forms;
  private final DemandWorkflow workflow;
  /** 公开能力开关及登录后的菜单与角标。 */
  @NotLogin
  @GetMapping("/capabilities")
  public Result<?> capabilities(HttpServletRequest r) {
    return Result.success(access.capabilities(user(r)));
  }
  /** 首页可见收集单。 */
  @NotLogin
  @GetMapping("/forms")
  public Result<?> forms(HttpServletRequest r) {
    return Result.success(forms.list(user(r), false));
  }
  /** 填报方式配置，不返回权限名单。 */
  @NotLogin
  @GetMapping("/forms/{key}")
  public Result<?> info(@PathVariable String key, HttpServletRequest r) {
    return Result.success(forms.info(key, user(r)));
  }
  /** 后台全部原表单及需求配置。 */
  @GetMapping("/admin/forms")
  public Result<?> adminForms(HttpServletRequest r) {
    return Result.success(forms.list(user(r), true));
  }
  /** 配置收集单发布范围。 */
  @PostMapping("/admin/forms/{key}")
  public Result<?> configure(
      @PathVariable String key, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    forms.configure(key, body, user(r));
    return Result.success();
  }
  /** 查询具名成员授权。 */
  @GetMapping("/admin/forms/{key}/members")
  public Result<?> members(@PathVariable String key, HttpServletRequest r) {
    return Result.success(forms.members(key, user(r)));
  }
  /** 设置指定人员填报与经办权限。 */
  @PostMapping("/admin/forms/{key}/members/{memberId}")
  public Result<?> member(
      @PathVariable String key,
      @PathVariable Long memberId,
      @RequestBody Map<String, Object> body,
      HttpServletRequest r) {
    forms.member(key, memberId, body, user(r));
    return Result.success();
  }
  /** 搜索授权候选用户。 */
  @GetMapping("/admin/users")
  public Result<?> users(@RequestParam(defaultValue = "") String keyword, HttpServletRequest r) {
    return Result.success(forms.users(keyword, user(r)));
  }
  /** 查看额外管理员。 */
  @GetMapping("/admin/roles")
  public Result<?> roles(HttpServletRequest r) {
    return Result.success(forms.admins(user(r)));
  }
  /** 授予或撤销管理员能力。 */
  @PostMapping("/admin/roles/{memberId}")
  public Result<?> role(
      @PathVariable Long memberId, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    forms.admin(memberId, flag(body.get("grant")), user(r));
    return Result.success();
  }
  /** 分页需求列表。 */
  @GetMapping("/requests")
  public Result<?> requests(
      @RequestParam(defaultValue = "mine") String scope,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String decision,
      @RequestParam(defaultValue = "") String progress,
      @RequestParam(defaultValue = "") String formKey,
      @RequestParam(defaultValue = "") String handlerId,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest r) {
    return Result.success(
        workflow.list(user(r), scope, keyword, decision, progress, formKey, handlerId, page, size));
  }
  /** 本人或经办人读取详情；匿名查询使用独立 POST 接口。 */
  @GetMapping("/requests/{id}")
  public Result<?> detail(@PathVariable String id, HttpServletRequest r) {
    return Result.success(workflow.detail(id, user(r), null));
  }
  /** 匿名码仅位于请求体，不放入地址栏及查询日志。 */
  @NotLogin
  @PostMapping("/lookup")
  public Result<?> lookup(@RequestBody Map<String, Object> body) {
    access.requireEnabled();
    String no = text(body.get("requestNo"), 64), code = text(body.get("queryCode"), 100);
    // 匿名编号先定位记录，但错误信息不区分编号与凭证。
    String id = lookupId(no);
    return Result.success(workflow.detail(id, null, code));
  }

  private final DemandStore store;
  /** 通过需求编号定位匿名查询，不向调用者暴露其他标识。 */
  private String lookupId(String no) {
    Map<String, Object> row =
        store.one("SELECT id FROM rm_demand WHERE request_no=? AND anonymous=TRUE", no);
    if (row == null) throw new BaseException("需求不存在或无权访问", 403);
    return row.get("id").toString();
  }
  /** 分派或改派。 */
  @PostMapping("/requests/{id}/assign")
  public Result<?> assign(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.assign(id, number(body.get("handlerId")), number(body.get("revision")), user(r));
    return Result.success();
  }
  /** 经办人填写采纳结果与实施进度。 */
  @PostMapping("/requests/{id}/outcome")
  public Result<?> outcome(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.outcome(id, body, user(r));
    return Result.success();
  }
  /** 追加正式回复、内部备注或草稿。 */
  @PostMapping("/requests/{id}/messages")
  public Result<?> reply(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.reply(id, String.valueOf(body.get("kind")), text(body.get("content"), 10000), user(r));
    return Result.success();
  }
  /** 保存实际阅读到的版本。 */
  @PostMapping("/read")
  public Result<?> read(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.read(
        String.valueOf(body.get("type")),
        String.valueOf(body.get("id")),
        number(body.get("version")),
        user(r));
    return Result.success();
  }
  /** 管理员审核发布或撤回公示。 */
  @PostMapping("/requests/{id}/publication")
  public Result<?> publish(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.publish(id, body, user(r));
    return Result.success();
  }
  /** 内部已审核公示列表。 */
  @GetMapping("/publications")
  public Result<?> publications(HttpServletRequest r) {
    return Result.success(workflow.publications(user(r)));
  }
  /** 内部公示详情。 */
  @GetMapping("/publications/{id}")
  public Result<?> publication(@PathVariable String id, HttpServletRequest r) {
    return Result.success(workflow.publication(id, user(r)));
  }
  /** 按需求授权下载附件，禁止任意代理 URL 和直接公开访问。 */
  @NotLogin
  @PostMapping("/requests/{id}/attachment")
  public ResponseEntity<byte[]> attachment(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r)
      throws java.io.IOException {
    String url =
        workflow.attachment(
            id,
            text(body.get("url"), 2048),
            user(r),
            body.get("queryCode") == null ? null : body.get("queryCode").toString());
    if (!OssStorageFactory.isAllowedStorageUrl(url)) throw new BaseException("附件不属于本系统存储", 403);
    byte[] bytes = OssStorageFactory.downloadBytes(url);
    return ResponseEntity.ok()
        .header(
            "Content-Disposition",
            ContentDisposition.attachment()
                .filename("需求附件", StandardCharsets.UTF_8)
                .build()
                .toString())
        .header("X-Content-Type-Options", "nosniff")
        .header("Cache-Control", "no-store")
        .contentType(MediaType.APPLICATION_OCTET_STREAM)
        .body(bytes);
  }
  /** 使用认证拦截器写入的身份，不采信客户端用户参数。 */
  private Long user(HttpServletRequest r) {
    return number(r.getAttribute("userId"));
  }
}
