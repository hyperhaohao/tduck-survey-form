package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;

import com.tduck.cloud.api.annotation.NotLogin;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.storage.cloud.OssStorageFactory;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
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
  private final DemandExport export;
  private final DemandDepartments departmentService;
  private final DemandNotices notices;
  private final DemandRequired required;
  private final DemandApproval approval;
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
  /** 单据管理员读取可分派的经办人。 */
  @GetMapping("/forms/{key}/handlers")
  public Result<?> handlers(@PathVariable String key, HttpServletRequest r) {
    return Result.success(forms.handlers(key, user(r)));
  }
  /** 后台全部原表单及需求配置。 */
  @GetMapping("/admin/forms")
  public Result<?> adminForms(HttpServletRequest r) {
    return Result.success(forms.list(user(r), true));
  }
  /** 全局管理员保存首页收集单全局顺序。 */
  @PostMapping("/admin/forms/sort")
  public Result<?> sortForms(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    forms.sort(body, user(r));
    return Result.success();
  }
  /** 配置收集单发布范围。 */
  @PostMapping("/admin/forms/{key}")
  public Result<?> configure(
      @PathVariable String key, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    forms.configure(key, body, user(r));
    return Result.success();
  }
  /** 必填人配置、来源与本轮统计。 */
  @GetMapping("/admin/forms/{key}/required-members")
  public Result<?> requiredMembers(@PathVariable String key, HttpServletRequest r) {
    return Result.success(required.settings(key, user(r)));
  }
  /** 保存必填人来源。 */
  @PostMapping("/admin/forms/{key}/required-members")
  public Result<?> saveRequiredMembers(
      @PathVariable String key, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    required.save(key, body, user(r));
    return Result.success();
  }
  /** 手工开启下一轮。 */
  @PostMapping("/admin/forms/{key}/round/next")
  public Result<?> nextRound(
      @PathVariable String key,
      @RequestBody(required = false) Map<String, Object> body,
      HttpServletRequest r) {
    forms.nextRound(key, body == null ? new LinkedHashMap<>() : body, user(r));
    return Result.success();
  }
  /** 作废或恢复收集单；作废后停止新填报，需求历史保留。 */
  @PostMapping("/admin/forms/{key}/void")
  public Result<?> setVoided(
      @PathVariable String key, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    forms.setVoided(key, flag(body.get("voided")), user(r));
    return Result.success();
  }
  /** 全局回收站：已逻辑删除的收集单。 */
  @GetMapping("/admin/forms/deleted")
  public Result<?> deletedForms(HttpServletRequest r) {
    return Result.success(forms.deletedForms(user(r)));
  }
  /** 逻辑删除收集单到全局回收站。 */
  @PostMapping("/admin/forms/{key}/delete")
  public Result<?> deleteCollection(@PathVariable String key, HttpServletRequest r) {
    return Result.success(forms.deleteCollection(key, user(r)));
  }
  /** 从全局回收站恢复收集单。 */
  @PostMapping("/admin/forms/{key}/restore")
  public Result<?> restoreCollection(@PathVariable String key, HttpServletRequest r) {
    forms.restoreCollection(key, user(r));
    return Result.success();
  }
  /** 彻底删除无需求、无答卷的收集单。 */
  @PostMapping("/admin/forms/{key}/purge")
  public Result<?> purgeCollection(@PathVariable String key, HttpServletRequest r) {
    return Result.success(forms.purgeCollection(key, user(r)));
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
  /** 移除指定人员授权。 */
  @DeleteMapping("/admin/forms/{key}/members/{memberId}")
  public Result<?> removeMember(
      @PathVariable String key, @PathVariable Long memberId, HttpServletRequest r) {
    forms.removeMember(key, memberId, user(r));
    return Result.success();
  }
  /** 可授权部门列表。 */
  @GetMapping("/admin/departments")
  public Result<?> departments(HttpServletRequest r) {
    return Result.success(departmentService.list(user(r)));
  }
  /** 部门成员名册。 */
  @GetMapping("/admin/departments/members")
  public Result<?> departmentRoster(HttpServletRequest r) {
    return Result.success(departmentService.roster(user(r)));
  }
  /** 新建部门。 */
  @PostMapping("/admin/departments")
  public Result<?> createDepartment(@RequestBody Map<String, Object> body, HttpServletRequest r) {
    return Result.success(departmentService.create(body, user(r)));
  }
  /** 修改部门名称、上级或排序。 */
  @PutMapping("/admin/departments/{deptKey}")
  public Result<?> updateDepartment(
      @PathVariable String deptKey, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    departmentService.update(deptKey, body, user(r));
    return Result.success();
  }
  /** 删除没有下级、成员和授权的部门。 */
  @DeleteMapping("/admin/departments/{deptKey}")
  public Result<?> deleteDepartment(@PathVariable String deptKey, HttpServletRequest r) {
    departmentService.delete(deptKey, user(r));
    return Result.success();
  }
  /** 把成员加入部门。 */
  @PostMapping("/admin/departments/{deptKey}/members/{memberId}")
  public Result<?> addDepartmentMember(
      @PathVariable String deptKey, @PathVariable Long memberId, HttpServletRequest r) {
    departmentService.addMember(deptKey, memberId, user(r));
    return Result.success();
  }
  /** 从部门移除成员。 */
  @DeleteMapping("/admin/departments/{deptKey}/members/{memberId}")
  public Result<?> removeDepartmentMember(
      @PathVariable String deptKey, @PathVariable Long memberId, HttpServletRequest r) {
    departmentService.removeMember(deptKey, memberId, user(r));
    return Result.success();
  }
  /** 整体设置成员的所属部门。 */
  @PutMapping("/admin/users/{memberId}/departments")
  public Result<?> setMemberDepartments(
      @PathVariable Long memberId, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    departmentService.setUserDepartments(memberId, body, user(r));
    return Result.success();
  }
  /** 收集单部门授权。 */
  @GetMapping("/admin/forms/{key}/department-members")
  public Result<?> departmentMembers(@PathVariable String key, HttpServletRequest r) {
    return Result.success(departmentService.members(key, user(r)));
  }
  /** 保存部门授权。 */
  @PostMapping("/admin/forms/{key}/department-members/{deptKey}")
  public Result<?> departmentMember(
      @PathVariable String key,
      @PathVariable String deptKey,
      @RequestBody Map<String, Object> body,
      HttpServletRequest r) {
    departmentService.save(key, deptKey, body, user(r));
    return Result.success();
  }
  /** 移除部门授权。 */
  @DeleteMapping("/admin/forms/{key}/department-members/{deptKey}")
  public Result<?> removeDepartmentMember(
      @PathVariable String key, @PathVariable String deptKey, HttpServletRequest r) {
    departmentService.remove(key, deptKey, user(r));
    return Result.success();
  }
  /** 全局管理员导入部门树与成员关系。 */
  @PostMapping("/admin/departments/import")
  public Result<?> importDepartments(
      @RequestBody Map<String, Object> body, HttpServletRequest r) {
    return Result.success(departmentService.importTree(body, user(r)));
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
  /** 平台权限矩阵：能力定义、全局专属说明与已授权用户。 */
  @GetMapping("/admin/permissions")
  public Result<?> permissions(HttpServletRequest r) {
    return Result.success(forms.permissionMatrix(user(r)));
  }
  /** 整体保存用户平台能力；空数组表示撤销。 */
  @PostMapping("/admin/permissions/{memberId}")
  public Result<?> savePermissions(
      @PathVariable Long memberId, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    forms.savePermissions(memberId, body, user(r));
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
      @RequestParam(defaultValue = "") String submitStart,
      @RequestParam(defaultValue = "") String submitEnd,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest r) {
    return Result.success(
        workflow.list(
            user(r),
            scope,
            keyword,
            decision,
            progress,
            formKey,
            handlerId,
            submitStart,
            submitEnd,
            page,
            size));
  }
  /** 按收集单汇总当前范围的需求数量，供页面分组展示。 */
  @GetMapping("/requests/summary")
  public Result<?> summary(
      @RequestParam(defaultValue = "mine") String scope, HttpServletRequest r) {
    return Result.success(workflow.summary(user(r), scope));
  }
  /** 导出当前筛选条件下的需求，权限与列表一致。 */
  @GetMapping("/export/requests")
  public ResponseEntity<byte[]> exportRequests(
      @RequestParam(defaultValue = "mine") String scope,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String decision,
      @RequestParam(defaultValue = "") String progress,
      @RequestParam(defaultValue = "") String formKey,
      @RequestParam(defaultValue = "") String handlerId,
      @RequestParam(defaultValue = "") String submitStart,
      @RequestParam(defaultValue = "") String submitEnd,
      HttpServletRequest r) {
    DemandExport.ExportFile file =
        export.requests(
            user(r),
            scope,
            keyword,
            decision,
            progress,
            formKey,
            handlerId,
            submitStart,
            submitEnd);
    String name =
        "pending".equals(scope)
            ? "待我处理"
            : ("table".equals(scope) || "managed".equals(scope)) ? "需求总表" : "我的需求";
    return xlsx(file, name);
  }
  /** 导出当前筛选条件下可见的公示。 */
  @GetMapping("/export/publications")
  public ResponseEntity<byte[]> exportPublications(
      @RequestParam(defaultValue = "") String formKey,
      @RequestParam(defaultValue = "") String keyword,
      @RequestParam(defaultValue = "") String submitStart,
      @RequestParam(defaultValue = "") String submitEnd,
      HttpServletRequest r) {
    return xlsx(
        export.publications(user(r), formKey, keyword, submitStart, submitEnd), "需求公示");
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
  /** 部门主管通过或驳回审批。 */
  @PostMapping("/requests/{id}/approval")
  public Result<?> decideApproval(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    approval.decide(id, body, user(r));
    return Result.success();
  }
  /** 分派或改派。 */
  @PostMapping("/requests/{id}/assign")
  public Result<?> assign(      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
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
  /** 单据管理员或全局管理员删除需求，原答卷保留。 */
  @PostMapping("/requests/{id}/delete")
  public Result<?> deleteDemand(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.deleteDemand(id, number(body.get("revision")), user(r));
    return Result.success();
  }
  /** 追加正式回复、内部备注或草稿。 */
  @PostMapping("/requests/{id}/messages")
  public Result<?> reply(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.reply(id, String.valueOf(body.get("kind")), text(body.get("content"), 10000), user(r));
    return Result.success();
  }
  /** 填报者对本人需求发起追问。 */
  @PostMapping("/requests/{id}/followup")
  public Result<?> followUp(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.followUp(id, text(body.get("content"), 10000), user(r));
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
  /** 站内消息列表：包含已读，支持类型与未读筛选。 */
  @GetMapping("/notices")
  public Result<?> listNotices(
      @RequestParam(defaultValue = "") String kind,
      @RequestParam(defaultValue = "false") boolean unreadOnly,
      @RequestParam(defaultValue = "1") int page,
      @RequestParam(defaultValue = "20") int size,
      HttpServletRequest r) {
    return Result.success(notices.list(user(r), kind, unreadOnly, page, size));
  }
  /** 标记本人单条消息已读。 */
  @PostMapping("/notices/{id}/read")
  public Result<?> readNotice(@PathVariable String id, HttpServletRequest r) {
    notices.read(id, user(r));
    return Result.success();
  }
  /** 本人全部消息标记已读。 */
  @PostMapping("/notices/read-all")
  public Result<?> readAllNotices(HttpServletRequest r) {
    return Result.success(notices.readAll(user(r)));
  }
  /** 单据管理员审核发布或撤回公示。 */
  @PostMapping("/requests/{id}/publication")
  public Result<?> publish(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.publish(id, body, user(r));
    return Result.success();
  }
  /** 自动生成内容并发布公示，已发布时幂等返回。 */
  @PostMapping("/requests/{id}/publication/auto")
  public Result<?> autoPublish(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    return Result.success(
        workflow.autoPublish(id, number(body.get("revision")), user(r)));
  }
  /** 记录不公示并移出待办。 */
  @PostMapping("/requests/{id}/publication/skip")
  public Result<?> skipPublication(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    return Result.success(
        workflow.skipPublication(id, number(body.get("revision")), user(r)));
  }
  /** 单据管理员归档、恢复或删除公示。 */
  @PostMapping("/publications/{id}/archive")
  public Result<?> archivePublication(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.archivePublication(id, body, user(r));
    return Result.success();
  }
  @PostMapping("/publications/{id}/delete")
  public Result<?> deletePublication(
      @PathVariable String id, @RequestBody Map<String, Object> body, HttpServletRequest r) {
    workflow.deletePublication(id, body, user(r));
    return Result.success();
  }
  /** 内部已审核公示列表；单据管理员可要求包含已归档项。 */
  @GetMapping("/publications")
  public Result<?> publications(
      @RequestParam(required = false, defaultValue = "false") boolean includeArchived,
      HttpServletRequest r) {
    return Result.success(workflow.publications(user(r), includeArchived));
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
  /** 统一下载响应头，截断时由前端提示实际导出范围。 */
  private ResponseEntity<byte[]> xlsx(DemandExport.ExportFile file, String name) {
    String stamp = new SimpleDateFormat("yyyyMMdd-HHmm").format(new Date());
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(
        MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    headers.setContentDisposition(
        ContentDisposition.attachment()
            .filename(name + "-" + stamp + ".xlsx", StandardCharsets.UTF_8)
            .build());
    headers.setCacheControl("no-store");
    headers.set("X-Content-Type-Options", "nosniff");
    if (file.truncated) headers.set("X-Export-Truncated", "true");
    return new ResponseEntity<>(file.bytes, headers, HttpStatus.OK);
  }
}
