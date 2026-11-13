package com.tduck.cloud.api.demand;

import static com.tduck.cloud.api.demand.DemandStore.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tduck.cloud.account.entity.UserEntity;
import com.tduck.cloud.account.service.UserService;
import com.tduck.cloud.api.web.controller.UserFormController;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.form.entity.UserFormDataEntity;
import com.tduck.cloud.form.service.UserFormItemService;
import java.util.*;
import java.util.concurrent.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.mock.web.*;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.method.HandlerMethod;

/** 隔离数据库验证权限、闭环、通知、快照与并发；不访问生产数据。 */
class DemandWorkflowTest {
  JdbcTemplate jdbc;
  DemandStore store;
  DemandAccess access;
  DemandForms forms;
  DemandWorkflow workflow;
  DemandSubmission submission;
  DemandProperties properties;
  UserService users;
  TransactionTemplate tx;

  @BeforeEach
  void setup() throws Exception {
    JdbcDataSource ds = new JdbcDataSource();
    ds.setURL(
        "jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
    jdbc = new JdbcTemplate(ds);
    store = new DemandStore(jdbc, new ObjectMapper());
    ScriptUtils.executeSqlScript(
        ds.getConnection(), new FileSystemResource("../doc/ruima-demand-schema.sql"));
    jdbc.execute(
        "CREATE TABLE ac_user(id BIGINT PRIMARY KEY,name VARCHAR(100),deleted BOOLEAN DEFAULT FALSE)");
    jdbc.execute(
        "CREATE TABLE fm_user_form(form_key VARCHAR(64) PRIMARY KEY,name VARCHAR(200),description VARCHAR(1000),status INT,is_deleted BOOLEAN DEFAULT FALSE,is_folder BOOLEAN DEFAULT FALSE,user_id BIGINT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE fm_user_form_data(id BIGINT PRIMARY KEY,form_key VARCHAR(64),original_data VARCHAR(10000))");
    for (long id : new long[] {1, 10, 20, 30, 40})
      jdbc.update("INSERT INTO ac_user(id,name) VALUES(?,?)", id, "人员" + id);
    jdbc.update(
        "INSERT INTO fm_user_form(form_key,name,description,status,user_id) VALUES('A','需求征集','测试',2,1)");
    jdbc.update(
        "INSERT INTO rm_demand_form(form_key,visibility,allow_anonymous,published) VALUES('A','LOGIN',TRUE,TRUE)");
    jdbc.update(
        "INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle) VALUES('A',30,FALSE,TRUE),('A',40,FALSE,TRUE)");
    users = mock(UserService.class);
    when(users.getById(any()))
        .thenAnswer(
            i -> {
              UserEntity u = new UserEntity();
              u.setId(Long.valueOf(i.getArgument(0).toString()));
              u.setName("人员" + u.getId());
              u.setDeleted(false);
              return u;
            });
    properties = new DemandProperties();
    properties.setEnabled(true);
    access = new DemandAccess(store, properties, users);
    forms = new DemandForms(store, access);
    UserFormItemService fields = mock(UserFormItemService.class);
    when(fields.listFormFields(anyString())).thenReturn(Collections.emptyList());
    workflow = new DemandWorkflow(store, access, fields);
    submission = new DemandSubmission(store, access);
    tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
  }

  private Map<String, Object> body(Object... pairs) {
    Map<String, Object> m = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) m.put(pairs[i].toString(), pairs[i + 1]);
    return m;
  }

  private String create(long dataId, Long userId, boolean anonymous) {
    UserFormDataEntity e = new UserFormDataEntity();
    e.setFormKey("A");
    e.setOriginalData(Collections.singletonMap("title", "改进建议" + dataId));
    e.setDemandAnonymous(anonymous);
    e.setCreateBy("伪造身份");
    submission.before(e, userId, false);
    e.setId(dataId);
    jdbc.update(
        "INSERT INTO fm_user_form_data(id,form_key,original_data) VALUES(?,'A',?)",
        dataId,
        "{\"title\":\"改进建议\",\"attachment\":[{\"url\":\"http://storage.test/u/A/file.pdf\",\"name\":\"附件\"}]}");
    Map<String, Object> r = new LinkedHashMap<>();
    submission.after(e, r, userId, false);
    return ((Map<?, ?>) r.get("demandReceipt")).get("id").toString();
  }

  private void assignTo(String id, long user) {
    workflow.assign(
        id,
        user,
        number(store.one("SELECT revision FROM rm_demand WHERE id=?", id).get("revision")),
        1L);
  }

  private void outcome(
      String id, String decision, String progress, String reason, String completion) {
    Map<String, Object> d = store.one("SELECT revision FROM rm_demand WHERE id=?", id);
    workflow.outcome(
        id,
        body(
            "revision",
            d.get("revision"),
            "decision",
            decision,
            "progress",
            progress,
            "reason",
            reason,
            "completion",
            completion),
        30L);
  }

  @Test
  void permissionUnionAndImmediateRevocation() {
    assertFalse(access.admin(30L));
    assertTrue(access.handler("A", 30L));
    assertTrue(access.visible(access.form("A"), 30L));
    forms.admin(30L, true, 1L);
    assertTrue(access.admin(30L));
    assertTrue(access.handler("A", 30L));
    forms.admin(30L, false, 1L);
    forms.member("A", 30L, body("canFill", true, "canHandle", false), 1L);
    assertFalse(access.handler("A", 30L));
    assertTrue(access.visible(access.form("A"), 30L));
    assertFalse(access.admin(30L));
    assertThrows(BaseException.class, () -> forms.admin(20L, true, 30L));
  }

  @Test
  void anonymousAndLoginPoliciesCannotBeBypassed() {
    assertThrows(BaseException.class, () -> access.requireFill("A", null, false));
    access.requireFill("A", null, true);
    jdbc.update(
        "UPDATE rm_demand_form SET visibility='SELECTED',allow_anonymous=TRUE WHERE form_key='A'");
    assertThrows(BaseException.class, () -> access.requireFill("A", null, true));
    assertThrows(BaseException.class, () -> access.requireFill("A", 10L, false));
    forms.member("A", 10L, body("canFill", true, "canHandle", false), 1L);
    access.requireFill("A", 10L, false);
    assertThrows(
        BaseException.class,
        () -> forms.configure("A", body("visibility", "SELECTED", "allowAnonymous", true), 1L));
    Map<String, Object> gate = forms.info("A", null);
    assertEquals(false, gate.get("allowAnonymous"));
    assertFalse(gate.containsKey("summary"));
  }

  @Test
  void submissionIdentityIsServerControlled() {
    UserFormDataEntity e = new UserFormDataEntity();
    e.setFormKey("A");
    e.setCreateBy("40");
    e.setWxOpenId("private");
    e.setWxUserInfo(Collections.singletonMap("name", "private"));
    e.setDemandAnonymous(true);
    submission.before(e, 10L, false);
    assertNull(e.getCreateBy());
    assertNull(e.getWxOpenId());
    assertNull(e.getWxUserInfo());
    e.setDemandAnonymous(false);
    submission.before(e, 10L, false);
    assertEquals("10", e.getCreateBy());
    e.setId(123L);
    assertThrows(BaseException.class, () -> submission.before(e, 10L, false));
    e.setId(null);
    assertThrows(BaseException.class, () -> submission.before(e, 30L, true));
  }

  @Test
  void requesterCannotReadOthersAndHandlersCannotReplyUnassigned() {
    String id = create(100, 10L, false);
    assertThrows(BaseException.class, () -> workflow.detail(id, 20L, null));
    assertEquals(id, workflow.detail(id, 10L, null).get("id"));
    assertEquals(0L, workflow.list(20L, "mine", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(1L, workflow.list(30L, "table", "", "", "", "", "", 1, 20).get("total"));
    assertThrows(BaseException.class, () -> workflow.reply(id, "REPLY", "未分派回复", 30L));
    assignTo(id, 30);
    assertThrows(BaseException.class, () -> workflow.reply(id, "REPLY", "越权回复", 40L));
    workflow.reply(id, "REPLY", "正式回复", 30L);
    assertThrows(
        BaseException.class,
        () -> workflow.outcome(id, body("revision", 1, "decision", "REJECTED"), 1L));
  }

  @Test
  void fullLifecyclePreservesRepliesAndFiltersPrivateNotes() {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    workflow.reply(id, "DRAFT", "草稿", 30L);
    workflow.reply(id, "NOTE", "内部讨论", 30L);
    Map<String, Object> own = workflow.detail(id, 10L, null);
    assertTrue(((List<?>) own.get("messages")).isEmpty());
    workflow.reply(id, "REPLY", "已收到需求", 30L);
    workflow.reply(id, "REPLY", "正在评估", 30L);
    outcome(id, "ACCEPTED", "PLANNED", "建议采纳", "");
    outcome(id, "ACCEPTED", "IN_PROGRESS", "已安排实施", "");
    assertThrows(BaseException.class, () -> outcome(id, "ACCEPTED", "COMPLETED", "已安排实施", ""));
    outcome(id, "ACCEPTED", "COMPLETED", "已安排实施", "完成部署和验证");
    own = workflow.detail(id, 10L, null);
    assertEquals("COMPLETED", own.get("progress"));
    assertFalse(own.containsKey("queryHash"));
    assertFalse(own.containsKey("dataId"));
    List<Map<String, Object>> messages = (List<Map<String, Object>>) own.get("messages");
    assertEquals(2, messages.stream().filter(m -> "REPLY".equals(m.get("kind"))).count());
    assertFalse(
        messages.stream()
            .anyMatch(m -> Arrays.asList("NOTE", "DRAFT", "AUDIT").contains(m.get("kind"))));
    assertEquals(0L, access.capabilities(30L).get("pending"));
  }

  @Test
  void anonymousCodeIsHashedAndDoesNotAttachToLoggedAccount() {
    String id = create(100, 10L, true);
    Map<String, Object> d = store.one("SELECT * FROM rm_demand WHERE id=?", id);
    assertNull(d.get("submitterId"));
    assertEquals("匿名", d.get("submitterName"));
    assertThrows(BaseException.class, () -> workflow.detail(id, 10L, null));
    assertThrows(BaseException.class, () -> workflow.detail(id, null, "wrong"));
    String code = token();
    jdbc.update("UPDATE rm_demand SET query_hash=? WHERE id=?", hash(code), id);
    assertNotEquals(
        code, store.one("SELECT query_hash FROM rm_demand WHERE id=?", id).get("queryHash"));
    assertEquals(id, workflow.detail(id, null, code).get("id"));
  }

  @Test
  void countersDeduplicateAndReadDoesNotClearNewerUpdate() {
    String id = create(100, 10L, false);
    assertEquals(0L, access.capabilities(10L).get("mineUnread"));
    assignTo(id, 30);
    assertEquals(1L, access.capabilities(30L).get("pending"));
    workflow.reply(id, "REPLY", "回复一", 30L);
    long readVersion =
        number(
            store.one("SELECT public_version FROM rm_demand WHERE id=?", id).get("publicVersion"));
    workflow.reply(id, "REPLY", "回复二", 30L);
    assertEquals(1L, access.capabilities(10L).get("mineUnread"));
    workflow.read("DEMAND", id, readVersion, 10L);
    assertEquals(1L, access.capabilities(10L).get("mineUnread"));
    workflow.read("DEMAND", id, 10000, 10L);
    assertEquals(0L, access.capabilities(10L).get("mineUnread"));
    workflow.reply(id, "NOTE", "备注不通知", 30L);
    assertEquals(0L, access.capabilities(10L).get("mineUnread"));
  }

  @Test
  void publicationIsReviewedSnapshotAndCanBeWithdrawn() {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    outcome(id, "ACCEPTED", "PLANNED", "准备实施", "");
    Map<String, Object> p =
        body(
            "published", true, "version", 0, "title", "公开标题", "summary", "脱敏摘要", "content", "正式说明");
    assertThrows(BaseException.class, () -> workflow.publish(id, p, 30L));
    workflow.publish(id, p, 1L);
    Map<String, Object> publicRow = workflow.publications(20L).get(0);
    String publicationId = publicRow.get("id").toString();
    assertEquals(1L, access.capabilities(20L).get("publicationUnread"));
    outcome(id, "ACCEPTED", "COMPLETED", "实施完成", "私有完成记录");
    Map<String, Object> published = workflow.publication(publicationId, 20L);
    assertEquals("PLANNED", published.get("progress"));
    assertFalse(published.containsKey("originalData"));
    assertFalse(published.containsKey("messages"));
    workflow.read("PUBLICATION", publicationId, 1, 20L);
    assertEquals(0L, access.capabilities(20L).get("publicationUnread"));
    workflow.publish(id, body("published", false, "version", 1), 1L);
    assertTrue(workflow.publications(20L).isEmpty());
    assertThrows(BaseException.class, () -> workflow.publication(publicationId, 20L));
  }

  @Test
  void selectedFormPublicationRespectsAudience() {
    String id = create(100, 10L, false);
    workflow.publish(
        id,
        body("published", true, "version", 0, "title", "标题", "summary", "摘要", "content", "正文"),
        1L);
    jdbc.update(
        "UPDATE rm_demand_form SET visibility='SELECTED',allow_anonymous=FALSE WHERE form_key='A'");
    assertTrue(workflow.publications(20L).isEmpty());
    assertEquals(0L, access.capabilities(20L).get("publicationUnread"));
    forms.member("A", 20L, body("canFill", true, "canHandle", false), 1L);
    assertEquals(1, workflow.publications(20L).size());
  }

  @Test
  void staleConcurrentOutcomeDoesNotOverwriteWinner() throws Exception {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    long revision =
        number(store.one("SELECT revision FROM rm_demand WHERE id=?", id).get("revision"));
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    Callable<Boolean> task =
        () -> {
          start.await();
          try {
            tx.execute(
                status -> {
                  workflow.outcome(
                      id,
                      body(
                          "revision",
                          revision,
                          "decision",
                          "ACCEPTED",
                          "progress",
                          "PLANNED",
                          "reason",
                          "采纳",
                          "completion",
                          ""),
                      30L);
                  return null;
                });
            return true;
          } catch (BaseException e) {
            return false;
          }
        };
    Future<Boolean> a = executor.submit(task), b = executor.submit(task);
    start.countDown();
    assertNotEquals(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
    executor.shutdownNow();
    assertEquals(
        revision + 1,
        number(store.one("SELECT revision FROM rm_demand WHERE id=?", id).get("revision")));
  }

  @Test
  void legacyCreateGuardAndModuleOffCompatibility() throws Exception {
    DemandGuard guard = new DemandGuard(access);
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setServletPath("/user/form/create");
    r.setAttribute("userId", 30L);
    HandlerMethod method =
        new HandlerMethod(
            mock(UserFormController.class),
            UserFormController.class.getMethod(
                "createForm", com.tduck.cloud.form.entity.UserFormEntity.class, Long.class));
    assertThrows(
        BaseException.class, () -> guard.preHandle(r, new MockHttpServletResponse(), method));
    properties.setEnabled(false);
    assertTrue(guard.preHandle(r, new MockHttpServletResponse(), method));
    UserFormDataEntity e = new UserFormDataEntity();
    e.setFormKey("ordinary");
    submission.before(e, null, false);
    Map<String, Object> result = new HashMap<>();
    submission.after(e, result, null, false);
    assertTrue(result.isEmpty());
  }

  @Test
  void attachmentRequiresExactMembershipAndStaticPathIsDenied() throws Exception {
    String id = create(100, 10L, false);
    String url = "http://storage.test/u/A/file.pdf";
    assertEquals(url, workflow.attachment(id, url, 10L, null));
    assertThrows(BaseException.class, () -> workflow.attachment(id, url + "/other", 10L, null));
    assertThrows(BaseException.class, () -> workflow.attachment(id, url, 20L, null));
    DemandFileGuard guard = new DemandFileGuard(access, store);
    MockHttpServletRequest r = new MockHttpServletRequest();
    r.setServletPath(
        "/u/"
            + org.springframework.util.DigestUtils.md5DigestAsHex("A".getBytes("UTF-8"))
            + "/file.pdf");
    MockHttpServletResponse response = new MockHttpServletResponse();
    MockFilterChain chain = new MockFilterChain();
    guard.doFilter(r, response, chain);
    assertEquals(403, response.getStatus());
    assertNull(chain.getRequest());
    MockHttpServletRequest repeated = new MockHttpServletRequest();
    repeated.setServletPath(r.getServletPath().replace("/u/", "/u//"));
    MockHttpServletResponse denied = new MockHttpServletResponse();
    guard.doFilter(repeated, denied, new MockFilterChain());
    assertEquals(403, denied.getStatus());
  }

  @Test
  void rejectedEndsAndDeferredRemainsInPending() {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    outcome(id, "DEFERRED", "NONE", "待条件成熟", "");
    assertEquals(1L, access.capabilities(30L).get("pending"));
    outcome(id, "REJECTED", "NONE", "超出需求范围", "");
    assertEquals(0L, access.capabilities(30L).get("pending"));
  }

  @Test
  void httpEndpointsApplyRealAuthorizationInterceptor() throws Exception {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    com.tduck.cloud.account.util.JwtUtils jwt = mock(com.tduck.cloud.account.util.JwtUtils.class);
    when(jwt.getHeader()).thenReturn("token");
    when(jwt.getClaimByToken(anyString()))
        .thenAnswer(
            i -> {
              String token = i.getArgument(0);
              if (!token.matches("qa[0-9]+")) return null;
              io.jsonwebtoken.Claims claims = mock(io.jsonwebtoken.Claims.class);
              when(claims.getSubject()).thenReturn(token.substring(2));
              when(claims.getExpiration()).thenReturn(new Date(System.currentTimeMillis() + 60000));
              return claims;
            });
    com.tduck.cloud.account.service.UserTokenService sessions =
        mock(com.tduck.cloud.account.service.UserTokenService.class);
    when(sessions.verifyToken(anyString(), anyLong())).thenReturn(true);
    org.springframework.test.web.servlet.MockMvc mvc =
        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new DemandController(access, forms, workflow, store))
            .setControllerAdvice(new com.tduck.cloud.api.exception.BaseExceptionHandler())
            .addInterceptors(
                new com.tduck.cloud.api.web.interceptor.AuthorizationInterceptor(jwt, sessions),
                new DemandGuard(access),
                new DemandRateLimit())
            .build();
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                "/demand-api/requests/" + id))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(401));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/demand-api/requests/" + id)
                .header("token", "qa20"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(403));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/demand-api/requests/" + id)
                .header("token", "qa10"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(200))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.canHandle")
                .value(false));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/demand-api/requests/" + id + "/messages")
                .header("token", "qa40")
                .contentType("application/json")
                .content("{\"kind\":\"REPLY\",\"content\":\"越权\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(403));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/demand-api/requests/" + id + "/messages")
                .header("token", "qa30")
                .contentType("application/json")
                .content("{\"kind\":\"REPLY\",\"content\":\"授权回复\"}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(200));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/demand-api/admin/forms")
                .header("token", "qa30"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(403));
  }
}
