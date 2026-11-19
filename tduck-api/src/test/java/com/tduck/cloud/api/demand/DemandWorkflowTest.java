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
import com.tduck.cloud.form.entity.UserFormItemEntity;
import com.tduck.cloud.form.service.UserFormItemService;
import com.tduck.cloud.form.vo.FormFieldVO;
import java.util.*;
import java.util.concurrent.*;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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
  DemandDepartments departments;
  DemandFormFields formFields;
  DemandWorkflow workflow;
  DemandExport export;
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
        "CREATE TABLE fm_user_form(form_key VARCHAR(64) PRIMARY KEY,name VARCHAR(200),description VARCHAR(1000),status INT,is_deleted BOOLEAN DEFAULT FALSE,is_folder BOOLEAN DEFAULT FALSE,user_id BIGINT,create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP,update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
    jdbc.execute(
        "CREATE TABLE fm_user_form_data(id BIGINT PRIMARY KEY,form_key VARCHAR(64),original_data VARCHAR(10000))");
    jdbc.execute(
        "CREATE TABLE fm_user_form_item(form_key VARCHAR(64),form_item_id VARCHAR(64),type VARCHAR(25),label VARCHAR(255),is_display_type BOOLEAN DEFAULT FALSE,is_hide_type BOOLEAN DEFAULT FALSE,is_special_type BOOLEAN DEFAULT FALSE,show_label BOOLEAN DEFAULT FALSE,default_value VARCHAR(1000),required BOOLEAN DEFAULT FALSE,placeholder VARCHAR(255),sort BIGINT DEFAULT 0,span INT DEFAULT 24,scheme VARCHAR(10000),reg_list VARCHAR(10000))");
    jdbc.execute("CREATE TABLE fm_user_form_setting(form_key VARCHAR(64))");
    jdbc.execute("CREATE TABLE fm_user_form_theme(form_key VARCHAR(64))");
    jdbc.execute("CREATE TABLE fm_user_form_logic(form_key VARCHAR(64))");
    jdbc.execute("CREATE TABLE fm_user_form_auth(form_key VARCHAR(64))");
    for (long id : new long[] {1, 10, 20, 30, 40, 50, 60})
      jdbc.update("INSERT INTO ac_user(id,name) VALUES(?,?)", id, "人员" + id);
    jdbc.update(
        "INSERT INTO fm_user_form(form_key,name,description,status,user_id) VALUES('A','需求征集','测试',2,1)");
    jdbc.update(
        "INSERT INTO rm_demand_form(form_key,visibility,allow_anonymous,published) VALUES('A','LOGIN',TRUE,TRUE)");
    jdbc.update(
        "INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle) VALUES('A',30,FALSE,TRUE),('A',40,FALSE,TRUE)");
    jdbc.update(
        "INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle,can_manage) VALUES('A',50,FALSE,FALSE,TRUE)");
    jdbc.update(
        "INSERT INTO rm_demand_dept(dept_key,name,parent_key,path,sort_no,active) VALUES"
            + "('R','总部',NULL,'/R',0,TRUE),('D1','技术部','R','/R/D1',1,TRUE),"
            + "('D2','研发组','D1','/R/D1/D2',2,TRUE),('D3','市场部','R','/R/D3',3,TRUE)");
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
    formFields = new DemandFormFields(store, access);
    forms = new DemandForms(store, access, formFields);
    departments = new DemandDepartments(store, access);
    UserFormItemService fields = mock(UserFormItemService.class);
    when(fields.listFormFields(anyString())).thenReturn(Collections.emptyList());
    workflow = new DemandWorkflow(store, access, fields, formFields);
    export = new DemandExport(workflow);
    submission = new DemandSubmission(store, access, formFields);
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

  private long revision(String id) {
    return number(store.one("SELECT revision FROM rm_demand WHERE id=?", id).get("revision"));
  }

  private XSSFWorkbook book(DemandExport.ExportFile file) throws Exception {
    return new XSSFWorkbook(new java.io.ByteArrayInputStream(file.bytes));
  }

  private String cell(Sheet sheet, int rowIndex, int column) {
    Row row = sheet.getRow(rowIndex);
    if (row == null) return "";
    Cell cell = row.getCell(column);
    return cell == null ? "" : cell.getStringCellValue();
  }

  private int rowCount(DemandExport.ExportFile file) throws Exception {
    try (XSSFWorkbook workbook = book(file)) {
      return workbook.getSheetAt(0).getLastRowNum();
    }
  }

  @Test
  void creatorDefaultsToManagerAndLastManagerIsProtected() {
    jdbc.update(
        "INSERT INTO fm_user_form(form_key,name,description,status,user_id) VALUES('B','设备改善','测试',2,20)");
    forms.configure(
        "B", body("visibility", "LOGIN", "allowAnonymous", false, "published", true), 1L);
    assertTrue(access.manage("B", 20L));
    List<Map<String, Object>> members = forms.members("B", 1L);
    assertEquals(1, members.size());
    assertTrue(flag(members.get(0).get("canManage")));
    // 旧前端不传 canManage 时保留原有管理权限。
    forms.member("B", 20L, body("canFill", true, "canHandle", true), 1L);
    assertTrue(flag(forms.members("B", 1L).get(0).get("canManage")));
    assertThrows(
        BaseException.class,
        () ->
            forms.member(
                "B", 20L, body("canFill", true, "canHandle", true, "canManage", false), 1L));
    forms.member("B", 30L, body("canManage", true), 1L);
    forms.member("B", 20L, body("canManage", false), 1L);
    assertFalse(access.manage("B", 20L));
    assertTrue(access.manage("B", 30L));
  }

  @Test
  void memberFlagsReturnBooleansForNumericJdbcValues() {
    Map<String, Object> numeric = new LinkedHashMap<>();
    numeric.put("canFill", 1);
    numeric.put("canHandle", 0);
    numeric.put("canManage", 1);
    Map<String, Object> bool = new LinkedHashMap<>();
    bool.put("canFill", Boolean.TRUE);
    bool.put("canHandle", Boolean.FALSE);
    bool.put("canManage", Boolean.TRUE);
    List<Map<String, Object>> rows = new ArrayList<>(Arrays.asList(numeric, bool));
    DemandForms.normalizeMemberFlags(rows);
    for (Map<String, Object> row : rows) {
      assertEquals(Boolean.TRUE, row.get("canFill"));
      assertEquals(Boolean.FALSE, row.get("canHandle"));
      assertEquals(Boolean.TRUE, row.get("canManage"));
    }
  }

  @Test
  void departmentGrantsApplyToMembersAndSubDepartments() {
    jdbc.update("INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(40,'D2'),(60,'D3')");
    departments.save(
        "A", "D1", body("canFill", true, "canHandle", true, "canManage", true), 1L);
    assertTrue(access.effectiveGrants(40L).canFill("A"));
    assertTrue(access.effectiveGrants(40L).canHandle("A"));
    assertTrue(access.effectiveGrants(40L).canManage("A"));
    assertFalse(access.effectiveGrants(60L).canFill("A"));
    assertFalse(access.effectiveGrants(60L).canHandle("A"));
    assertTrue(access.handler("A", 40L));
    assertTrue(access.manage("A", 40L));
    assertTrue(access.table("A", 40L));
    // 30 有直接经办，但不在部门内，不会被部门授权覆盖。
    assertTrue(access.effectiveGrants(30L).canHandle("A"));
    assertFalse(access.effectiveGrants(30L).canFill("A"));
  }

  @Test
  void departmentFillControlsSelectedFormVisibility() {
    jdbc.update("UPDATE rm_demand_form SET visibility='SELECTED' WHERE form_key='A'");
    jdbc.update("INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(40,'D2'),(60,'D3')");
    departments.save("A", "D1", body("canFill", true), 1L);
    assertTrue(access.visible(access.form("A"), 40L));
    assertFalse(access.visible(access.form("A"), 60L));
    assertTrue(access.effectiveGrants(40L).canFill("A"));
    assertFalse(access.effectiveGrants(60L).canFill("A"));
  }

  @Test
  void departmentHandlersIncludeMembersOnce() {
    jdbc.update(
        "INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(40,'D2'),(50,'D2'),(60,'D3')");
    departments.save("A", "D1", body("canHandle", true), 1L);
    List<Map<String, Object>> handlers = forms.handlers("A", 1L);
    List<Long> ids = new ArrayList<>();
    for (Map<String, Object> handler : handlers) ids.add(number(handler.get("userId")));
    assertTrue(ids.contains(30L));
    assertTrue(ids.contains(40L));
    assertEquals(1, Collections.frequency(ids, 40L));
    assertTrue(ids.contains(50L));
    assertFalse(ids.contains(60L));
  }

  @Test
  void departmentManagerSatisfiesLastManagerRule() {
    jdbc.update("INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(40,'D2')");
    departments.save("A", "D1", body("canManage", true), 1L);
    forms.member("A", 50L, body("canManage", false), 1L);
    assertFalse(access.manage("A", 50L));
    assertTrue(access.manage("A", 40L));
    assertThrows(BaseException.class, () -> departments.remove("A", "D1", 1L));
    forms.member("A", 30L, body("canManage", true), 1L);
    departments.remove("A", "D1", 1L);
    assertFalse(access.manage("A", 40L));
    assertTrue(access.manage("A", 30L));
  }

  @Test
  void memberRemovalProtectsLastManager() {
    jdbc.update(
        "INSERT INTO fm_user_form(form_key,name,description,status,user_id) VALUES('B','设备改善','测试',2,20)");
    forms.configure(
        "B", body("visibility", "LOGIN", "allowAnonymous", false, "published", true), 1L);
    BaseException error =
        assertThrows(BaseException.class, () -> forms.removeMember("B", 20L, 1L));
    assertTrue(error.getMessage().contains("至少保留一名有效单据管理员"));
    forms.member("B", 30L, body("canManage", true), 1L);
    forms.removeMember("B", 20L, 1L);
    List<Map<String, Object>> members = forms.members("B", 1L);
    assertEquals(1, members.size());
    assertEquals(30L, number(members.get(0).get("userId")));
  }

  @Test
  void memberRemovalDeletesGrantAndIsIdempotent() {
    forms.removeMember("A", 30L, 1L);
    List<Map<String, Object>> members = forms.members("A", 1L);
    assertFalse(members.stream().anyMatch(row -> number(row.get("userId")) == 30L));
    assertFalse(access.handler("A", 30L));
    forms.removeMember("A", 30L, 1L);
  }

  @Test
  void memberRemovalAllowsDepartmentManager() {
    jdbc.update("INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(40,'D2')");
    departments.save("A", "D1", body("canManage", true), 1L);
    forms.removeMember("A", 50L, 1L);
    assertFalse(access.manage("A", 50L));
    assertTrue(access.manage("A", 40L));
  }

  @Test
  void memberRemovalMissingGrantIsNoop() {
    int before = forms.members("A", 1L).size();
    forms.removeMember("A", 10L, 1L);
    assertEquals(before, forms.members("A", 1L).size());
  }

  @Test
  void departmentManageGrantScopesConsoleAdminForms() {
    jdbc.update("INSERT INTO rm_demand_admin(user_id) VALUES(60)");
    jdbc.update("INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(60,'D3')");
    departments.save("A", "D3", body("canManage", true), 1L);
    create(100, 10L, false);
    assertTrue(access.manage("A", 60L));
    assertEquals(1, forms.list(60L, true).size());
    assertEquals(
        1L, workflow.list(60L, "managed", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(1, workflow.summary(60L, "managed").size());
  }

  @Test
  void departmentImportRebuildsPathsAndMemberships() {
    Map<String, Object> root =
        body("key", "X", "name", "新总部", "parentKey", null, "sort", 0);
    Map<String, Object> child =
        body("key", "X1", "name", "新研发", "parentKey", "X", "sort", 1);
    Map<String, Object> byId = body("userId", 40L, "deptKeys", Arrays.asList("X1"));
    Map<String, Object> byName = body("userName", "人员50", "deptKeys", Arrays.asList("X"));
    Map<String, Object> payload =
        body(
            "replace",
            true,
            "departments",
            Arrays.asList(root, child),
            "members",
            Arrays.asList(byId, byName));
    Map<String, Object> result = departments.importTree(payload, 1L);
    assertEquals(2, result.get("departments"));
    assertEquals(2, result.get("memberships"));
    assertEquals(
        "/X/X1",
        store.one("SELECT path FROM rm_demand_dept WHERE dept_key='X1'").get("path"));
    assertFalse(flag(store.one("SELECT active FROM rm_demand_dept WHERE dept_key='D1'").get("active")));
    departments.save("A", "X", body("canFill", true), 1L);
    assertTrue(access.effectiveGrants(40L).canFill("A"));
    assertTrue(access.effectiveGrants(50L).canFill("A"));
    assertFalse(access.effectiveGrants(60L).canFill("A"));
    assertThrows(
        BaseException.class, () -> departments.save("A", "D1", body("canFill", true), 1L));
  }

  @Test
  void departmentRosterListsMembershipsAndUnassignedUsers() {
    jdbc.update(
        "INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(30,'D1'),(40,'D1'),(40,'D2'),(50,'D3')");
    jdbc.update("UPDATE rm_demand_dept SET active=FALSE WHERE dept_key='D3'");
    jdbc.update("INSERT INTO ac_user(id,name,deleted) VALUES(70,'已删除人员',TRUE)");
    List<Map<String, Object>> roster = departments.roster(1L);
    assertEquals(
        2,
        roster.stream()
            .filter(row -> "40".equals(String.valueOf(row.get("userId"))))
            .count());
    assertEquals(
        1,
        roster.stream()
            .filter(
                row -> "20".equals(String.valueOf(row.get("userId"))) && row.get("deptKey") == null)
            .count());
    assertEquals(
        1,
        roster.stream()
            .filter(
                row -> "50".equals(String.valueOf(row.get("userId"))) && row.get("deptKey") == null)
            .count());
    assertTrue(
        roster.stream().noneMatch(row -> "70".equals(String.valueOf(row.get("userId")))));
    assertThrows(BaseException.class, () -> departments.roster(30L));
  }

  @Test
  void departmentCrudMaintainsTreeAndMembers() {
    Map<String, Object> created =
        departments.create(body("name", "测试组", "parentKey", "D1", "sortNo", 9), 1L);
    String deptKey = String.valueOf(created.get("deptKey"));
    assertEquals("D1", created.get("parentKey"));
    assertTrue(String.valueOf(created.get("path")).startsWith("/R/D1/"));

    departments.update(deptKey, body("name", "测试二组"), 1L);
    assertEquals(
        "测试二组",
        store.one("SELECT name FROM rm_demand_dept WHERE dept_key=?", deptKey).get("name"));

    departments.update(deptKey, body("parentKey", "D2"), 1L);
    assertTrue(
        String.valueOf(
                store.one("SELECT path FROM rm_demand_dept WHERE dept_key=?", deptKey).get("path"))
            .startsWith("/R/D1/D2/"));
    assertThrows(
        BaseException.class, () -> departments.update("D1", body("parentKey", deptKey), 1L));
    assertThrows(
        BaseException.class,
        () -> departments.create(body("name", "测试二组", "parentKey", "D2"), 1L));

    departments.addMember(deptKey, 30L, 1L);
    assertEquals(
        1L,
        store.count(
            "SELECT COUNT(*) FROM rm_demand_user_dept WHERE user_id=30 AND dept_key=?", deptKey));
    departments.removeMember(deptKey, 30L, 1L);
    assertEquals(
        0L,
        store.count(
            "SELECT COUNT(*) FROM rm_demand_user_dept WHERE user_id=30 AND dept_key=?", deptKey));

    departments.setUserDepartments(40L, body("deptKeys", Arrays.asList("D1", "D2")), 1L);
    assertEquals(2L, store.count("SELECT COUNT(*) FROM rm_demand_user_dept WHERE user_id=40"));
    departments.setUserDepartments(40L, body("deptKeys", Collections.emptyList()), 1L);
    assertEquals(0L, store.count("SELECT COUNT(*) FROM rm_demand_user_dept WHERE user_id=40"));

    departments.addMember(deptKey, 40L, 1L);
    assertThrows(BaseException.class, () -> departments.delete(deptKey, 1L));
    departments.removeMember(deptKey, 40L, 1L);
    departments.delete(deptKey, 1L);
    assertEquals(0L, store.count("SELECT COUNT(*) FROM rm_demand_dept WHERE dept_key=?", deptKey));
    assertThrows(BaseException.class, () -> departments.create(body("name", "无权限"), 30L));
  }

  @Test
  @SuppressWarnings("unchecked")
  void departmentFieldDecoratesAndOverridesSubmission() {
    formFields.ensureDepartmentField("A", 1L);
    formFields.ensureDepartmentField("A", 1L);
    assertEquals(1L, store.count("SELECT COUNT(*) FROM fm_user_form_item WHERE form_key='A'"));
    Map<String, Object> row =
        store.one("SELECT type,CAST(scheme AS VARCHAR) AS scheme FROM fm_user_form_item WHERE form_key='A'");
    assertEquals("SELECT", row.get("type"));
    assertTrue(String.valueOf(row.get("scheme")).contains("demandDept"));

    jdbc.update("INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(30,'D1'),(30,'D2')");
    List<UserFormItemEntity> items = new ArrayList<>();
    items.add(departmentItem());
    formFields.decorate("A", items, 30L);
    Map<String, Object> scheme = items.get(0).getScheme();
    assertEquals(Boolean.TRUE, scheme.get("disabled"));
    Map<String, Object> config = (Map<String, Object>) scheme.get("config");
    List<String> values = (List<String>) config.get("defaultValue");
    assertTrue(values.contains("总部 / 技术部"));
    assertTrue(values.contains("总部 / 技术部 / 研发组"));
    List<Map<String, Object>> options = (List<Map<String, Object>>) config.get("options");
    assertTrue(options.stream().anyMatch(option -> "无".equals(option.get("value"))));

    List<UserFormItemEntity> anonymous = new ArrayList<>();
    anonymous.add(departmentItem());
    formFields.decorate("A", anonymous, null);
    assertEquals(
        Collections.singletonList("无"),
        ((Map<String, Object>) anonymous.get(0).getScheme().get("config")).get("defaultValue"));

    List<UserFormItemEntity> injected = new ArrayList<>();
    formFields.decorate("A", injected, 20L);
    assertEquals(1, injected.size());
    assertEquals("demandDept", injected.get(0).getFormItemId());

    List<UserFormItemEntity> other = new ArrayList<>();
    formFields.decorate("B", other, 1L);
    assertEquals(0, other.size());
    assertThrows(BaseException.class, () -> formFields.ensureDepartmentField("B", 1L));

    Map<String, Object> data = new LinkedHashMap<>();
    data.put("demandDept", Collections.singletonList("伪造"));
    UserFormDataEntity entity = new UserFormDataEntity();
    entity.setFormKey("A");
    entity.setOriginalData(data);
    entity.setDemandAnonymous(false);
    submission.before(entity, 30L, false);
    List<String> overridden = (List<String>) entity.getOriginalData().get("demandDept");
    assertTrue(overridden.contains("总部 / 技术部"));
    assertFalse(overridden.contains("伪造"));

    entity.setDemandAnonymous(true);
    submission.before(entity, 30L, false);
    assertEquals(Collections.singletonList("无"), entity.getOriginalData().get("demandDept"));

    // 未落库部门题的历史收集单：填写与详情都会补题，且重复调用不重复插入。
    jdbc.update(
        "INSERT INTO fm_user_form(form_key,name,description,status,user_id) VALUES('C','补题收集单','测试',2,1)");
    jdbc.update(
        "INSERT INTO rm_demand_form(form_key,visibility,allow_anonymous,published) VALUES('C','LOGIN',FALSE,TRUE)");
    List<UserFormItemEntity> backfill = new ArrayList<>();
    formFields.decorate("C", backfill, 30L);
    assertEquals(1, backfill.size());
    assertEquals(1L, store.count("SELECT COUNT(*) FROM fm_user_form_item WHERE form_key='C'"));
    List<UserFormItemEntity> repeated = new ArrayList<>();
    formFields.decorate("C", repeated, 30L);
    assertEquals(1L, store.count("SELECT COUNT(*) FROM fm_user_form_item WHERE form_key='C'"));
    List<FormFieldVO> detailFields = new ArrayList<>();
    formFields.decorateFormFields("C", detailFields);
    assertEquals(1, detailFields.size());
    assertEquals("填报人部门", detailFields.get(0).getLabel());
    formFields.decorateFormFields("C", detailFields);
    assertEquals(1, detailFields.size());
  }

  private UserFormItemEntity departmentItem() {
    UserFormItemEntity item = new UserFormItemEntity();
    item.setFormItemId("demandDept");
    Map<String, Object> config = new LinkedHashMap<>();
    config.put("demandDept", true);
    Map<String, Object> scheme = new LinkedHashMap<>();
    scheme.put("typeId", "SELECT");
    scheme.put("config", config);
    item.setScheme(scheme);
    return item;
  }

  @Test
  void departmentGrantsDriveCapabilitiesAndPendingCounts() {
    create(100, 10L, false);
    jdbc.update("INSERT INTO rm_demand_user_dept(user_id,dept_key) VALUES(50,'D1')");
    departments.save("A", "D1", body("canManage", true), 1L);
    Map<String, Object> capabilities = access.capabilities(50L);
    assertEquals(true, capabilities.get("handler"));
    assertEquals(true, capabilities.get("manage"));
    assertEquals(1L, capabilities.get("pending"));
    assertEquals(
        1L, workflow.list(50L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));
  }

  @Test
  void managerPendingMatchesCapabilitiesAndSummary() {
    String id = create(100, 10L, false);
    assertEquals(
        1L, workflow.list(50L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(
        0L, workflow.list(40L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(1L, access.capabilities(50L).get("pending"));
    List<Map<String, Object>> pending = workflow.summary(50L, "pending");
    assertEquals(1, pending.size());
    assertEquals(1L, ((Number) pending.get(0).get("pending")).longValue());
    assertTrue(flag(pending.get(0).get("canManage")));
    Map<String, Object> detail = workflow.detail(id, 50L, null);
    assertEquals(true, detail.get("canManage"));
    assertEquals(true, detail.get("canHandle"));
    assertEquals(true, detail.get("canAssign"));
    workflow.assign(id, 30L, revision(id), 50L);
    workflow.outcome(
        id,
        body(
            "revision",
            revision(id),
            "decision",
            "ACCEPTED",
            "progress",
            "PLANNED",
            "reason",
            "采纳说明",
            "completion",
            ""),
        50L);
    workflow.reply(id, "REPLY", "正式回复", 50L);
    workflow.publish(
        id,
        body("published", true, "version", 0, "title", "公示标题", "summary", "摘要", "content", "正文"),
        50L);
    assertEquals(0L, access.capabilities(50L).get("pending"));
    assertEquals(
        0L, workflow.list(50L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(
        1L, workflow.list(50L, "table", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(2, forms.handlers("A", 50L).size());
    assertThrows(BaseException.class, () -> forms.members("A", 50L));
    assertThrows(BaseException.class, () -> forms.handlers("A", 40L));
  }

  @Test
  void skipPublicationIsIdempotentAndBlocksPublished() {
    String id = create(100, 10L, false);
    long revision = revision(id);
    long publicVersion =
        number(
            store.one("SELECT public_version FROM rm_demand WHERE id=?", id).get("publicVersion"));
    Map<String, Object> first = workflow.skipPublication(id, revision, 50L);
    assertEquals(true, first.get("changed"));
    Map<String, Object> row =
        store.one("SELECT publication_skipped,revision,public_version FROM rm_demand WHERE id=?", id);
    assertTrue(flag(row.get("publicationSkipped")));
    assertEquals(revision + 1, number(row.get("revision")));
    assertEquals(publicVersion, number(row.get("publicVersion")));
    assertTrue(
        store.count(
                "SELECT COUNT(*) FROM rm_demand_message WHERE demand_id=? AND kind='AUDIT' AND content='选择不公示'",
                id)
            > 0);
    Map<String, Object> second = workflow.skipPublication(id, revision, 50L);
    assertEquals(false, second.get("changed"));
    workflow.reply(id, "REPLY", "正式回复", 50L);
    workflow.publish(
        id,
        body("published", true, "version", 0, "title", "公示标题", "summary", "摘要", "content", "正文"),
        50L);
    assertFalse(
        flag(
            store.one("SELECT publication_skipped FROM rm_demand WHERE id=?", id)
                .get("publicationSkipped")));
    assertThrows(BaseException.class, () -> workflow.skipPublication(id, revision(id), 50L));
    workflow.publish(id, body("published", false, "version", 1), 50L);
    assertEquals(
        true, workflow.skipPublication(id, revision(id), 50L).get("changed"));
  }

  @Test
  void autoPublishGeneratesContentAndIsIdempotent() {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    outcome(id, "ACCEPTED", "PLANNED", "采纳说明", "");
    workflow.reply(id, "REPLY", "正式回复", 30L);
    long revision = revision(id);
    Map<String, Object> first = workflow.autoPublish(id, revision, 50L);
    assertEquals(true, first.get("published"));
    assertEquals(false, first.get("unchanged"));
    Map<String, Object> publication =
        store.one("SELECT * FROM rm_demand_publication WHERE demand_id=?", id);
    assertEquals("改进建议100", publication.get("title"));
    assertEquals("采纳说明", publication.get("summary"));
    assertEquals("正式回复", publication.get("content"));
    assertThrows(BaseException.class, () -> workflow.autoPublish(id, revision, 50L));
    Map<String, Object> second = workflow.autoPublish(id, revision(id), 50L);
    assertEquals(true, second.get("unchanged"));
    assertEquals(
        1L,
        number(
            store.one("SELECT version FROM rm_demand_publication WHERE demand_id=?", id)
                .get("version")));
    String empty = create(101, 10L, false);
    assertThrows(BaseException.class, () -> workflow.autoPublish(empty, revision(empty), 50L));
    String fallback = create(102, 10L, false);
    assignTo(fallback, 30);
    outcome(fallback, "DEFERRED", "NONE", "暂缓说明", "");
    assertThrows(
        BaseException.class, () -> workflow.autoPublish(fallback, revision(fallback), 50L));
    workflow.reply(fallback, "REPLY", "正式回复", 30L);
    workflow.autoPublish(fallback, revision(fallback), 50L);
    Map<String, Object> fallbackPublication =
        store.one("SELECT summary,content FROM rm_demand_publication WHERE demand_id=?", fallback);
    assertEquals("暂缓说明", fallbackPublication.get("summary"));
    assertEquals("正式回复", fallbackPublication.get("content"));
  }

  @Test
  void publicationRequiresFormalReply() {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    outcome(id, "ACCEPTED", "PLANNED", "采纳说明", "");
    Map<String, Object> publication =
        body(
            "published", true, "version", 0, "title", "公示标题", "summary", "摘要", "content", "正文");
    assertThrows(BaseException.class, () -> workflow.publish(id, publication, 50L));
    assertThrows(BaseException.class, () -> workflow.autoPublish(id, revision(id), 50L));
    workflow.reply(id, "NOTE", "内部备注", 30L);
    assertThrows(BaseException.class, () -> workflow.publish(id, publication, 50L));
    assertThrows(BaseException.class, () -> workflow.autoPublish(id, revision(id), 50L));
    workflow.reply(id, "REPLY", "正式回复", 30L);
    workflow.publish(id, publication, 50L);
    assertEquals(1, workflow.publications(20L, false).size());
  }

  @Test
  void exportRespectsScopeAndTruncates() throws Exception {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    outcome(id, "ACCEPTED", "PLANNED", "采纳说明", "");
    workflow.reply(id, "REPLY", "正式回复", 30L);
    workflow.reply(id, "NOTE", "内部备注不可导出", 30L);
    DemandExport.ExportFile file =
        export.requests(30L, "table", "", "", "", "", "", "", "");
    assertFalse(file.truncated);
    try (XSSFWorkbook workbook = book(file)) {
      Sheet sheet = workbook.getSheetAt(0);
      assertEquals(1, sheet.getLastRowNum());
      assertEquals("需求标题", cell(sheet, 0, 0));
      assertEquals("需求征集", cell(sheet, 1, 2));
      assertEquals("人员10", cell(sheet, 1, 3));
      assertEquals("人员30", cell(sheet, 1, 4));
      assertEquals("采纳", cell(sheet, 1, 5));
      assertEquals("待实施", cell(sheet, 1, 6));
      for (int i = 0; i <= sheet.getLastRowNum(); i++)
        for (int j = 0; j < 9; j++) assertNotEquals("内部备注不可导出", cell(sheet, i, j));
    }
    assertEquals(1, rowCount(export.requests(10L, "mine", "", "", "", "", "", "", "")));
    assertEquals(0, rowCount(export.requests(20L, "mine", "", "", "", "", "", "", "")));
    assertEquals(1, rowCount(export.requests(30L, "pending", "", "", "", "", "", "", "")));
    create(101, 10L, false);
    Map<String, Object> limited =
        workflow.exportRows(30L, "table", "", "", "", "", "", "", "", 1);
    assertEquals(true, limited.get("truncated"));
    assertEquals(1, ((List<?>) limited.get("rows")).size());
  }

  @Test
  void exportPublicationsRespectsAudienceAndFilters() throws Exception {
    String id = create(100, 10L, false);
    workflow.reply(id, "REPLY", "正式回复", 1L);
    workflow.publish(
        id,
        body(
            "published", true, "version", 0, "title", "公开标题", "summary", "脱敏摘要", "content", "正式说明"),
        1L);
    DemandExport.ExportFile file = export.publications(20L, "", "", "", "");
    try (XSSFWorkbook workbook = book(file)) {
      Sheet sheet = workbook.getSheetAt(0);
      assertEquals(1, sheet.getLastRowNum());
      assertEquals("公开标题", cell(sheet, 1, 0));
      assertEquals("脱敏摘要", cell(sheet, 1, 9));
    }
    assertEquals(0, rowCount(export.publications(20L, "B", "", "", "")));
    assertEquals(0, rowCount(export.publications(20L, "", "不存在", "", "")));
    assertEquals(0, rowCount(export.publications(20L, "", "", "2000-01-01", "2000-12-31")));
    assertEquals(1, rowCount(export.publications(20L, "A", "公开", "2000-01-01", "2099-12-31")));
    workflow.publish(id, body("published", false, "version", 1), 1L);
    assertEquals(0, rowCount(export.publications(20L, "", "", "", "")));
  }

  @Test
  void permissionUnionAndImmediateRevocation() {
    assertEquals(false, access.capabilities(null).get("manage"));
    assertFalse(access.globalAdmin(30L));
    assertFalse(access.consoleAdmin(30L));
    assertTrue(access.handler("A", 30L));
    assertTrue(access.visible(access.form("A"), 30L));
    forms.admin(30L, true, 1L);
    assertTrue(access.consoleAdmin(30L));
    assertFalse(access.globalAdmin(30L));
    assertTrue(access.handler("A", 30L));
    forms.admin(30L, false, 1L);
    forms.member("A", 30L, body("canFill", true, "canHandle", false), 1L);
    assertFalse(access.handler("A", 30L));
    assertTrue(access.visible(access.form("A"), 30L));
    assertFalse(access.consoleAdmin(30L));
    assertThrows(BaseException.class, () -> forms.admin(20L, true, 30L));
  }

  @Test
  void assignedAdminSeesOnlyManagedFormsAndCanCreate() {
    jdbc.update("INSERT INTO rm_demand_admin(user_id) VALUES(60)");
    assertTrue(access.consoleAdmin(60L));
    assertFalse(access.globalAdmin(60L));
    assertFalse(access.manage("A", 60L));
    jdbc.update(
        "INSERT INTO fm_user_form(form_key,name,description,status,user_id) VALUES('C','自建收集单','测试',0,60)");
    List<Map<String, Object>> rows = forms.list(60L, true);
    assertEquals(1, rows.size());
    assertEquals("C", rows.get(0).get("formKey"));
    assertThrows(
        BaseException.class,
        () -> forms.configure("A", body("visibility", "LOGIN", "allowAnonymous", false), 60L));
    assertThrows(BaseException.class, () -> forms.members("A", 60L));
    forms.configure("C", body("visibility", "LOGIN", "allowAnonymous", false), 60L);
    assertTrue(access.manage("C", 60L));
    assertEquals(1, forms.list(60L, true).size());
    forms.member("A", 60L, body("canManage", true), 1L);
    assertTrue(access.manage("A", 60L));
    assertEquals(2, forms.list(60L, true).size());
    assertEquals(4, forms.members("A", 60L).size());
    assertThrows(BaseException.class, () -> forms.admins(60L));
    assertThrows(BaseException.class, () -> forms.admin(20L, true, 60L));
    assertTrue(forms.users("", 60L).stream().anyMatch(u -> number(u.get("id")) == 60L));
  }

  @Test
  void managedScopeRequiresConsoleAndFiltersForms() throws Exception {
    jdbc.update("INSERT INTO rm_demand_admin(user_id) VALUES(60)");
    String id = create(100, 10L, false);
    assertEquals(
        0L, workflow.list(60L, "managed", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(0, workflow.summary(60L, "managed").size());
    assertThrows(
        BaseException.class,
        () -> workflow.list(30L, "managed", "", "", "", "", "", "", "", 1, 20));
    assertThrows(BaseException.class, () -> workflow.detail(id, 60L, null));
    forms.member("A", 60L, body("canManage", true), 1L);
    assertEquals(
        1L, workflow.list(60L, "managed", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(1, workflow.summary(60L, "managed").size());
    assertEquals(true, workflow.detail(id, 60L, null).get("canManage"));
    assertEquals(1, rowCount(export.requests(60L, "managed", "", "", "", "", "", "", "")));
    assertEquals(
        1L, workflow.list(1L, "managed", "", "", "", "", "", "", "", 1, 20).get("total"));
  }

  @Test
  void managementExtensionOnlyGrantsConsoleManagers() {
    DemandManagementExtension extension = new DemandManagementExtension(access);
    jdbc.update("INSERT INTO rm_demand_admin(user_id) VALUES(60)");
    assertFalse(extension.canManage(60L, "A"));
    forms.member("A", 60L, body("canManage", true), 1L);
    assertTrue(extension.canManage(60L, "A"));
    assertFalse(extension.canManage(30L, "A"));
    assertTrue(extension.canManage(1L, "A"));
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
  void voidedCollectionStopsFillingAndRestores() {    jdbc.update("INSERT INTO rm_demand_admin(user_id) VALUES(60)");
    forms.member("A", 60L, body("canManage", true), 1L);
    assertThrows(BaseException.class, () -> forms.setVoided("A", true, 30L));
    forms.setVoided("A", true, 60L);
    assertTrue(flag(forms.list(60L, true).get(0).get("voided")));
    assertEquals(0, forms.list(null, false).size());
    assertThrows(BaseException.class, () -> access.requireFill("A", 10L, false));
    assertThrows(BaseException.class, () -> access.requireFill("A", null, true));
    assertEquals(false, forms.info("A", null).get("allowAnonymous"));
    forms.setVoided("A", false, 60L);
    access.requireFill("A", null, true);
    assertEquals(1, forms.list(null, false).size());
  }

  @Test
  void globalAdminDeletesRestoresAndPurgesCollections() {
    jdbc.update("INSERT INTO rm_demand_admin(user_id) VALUES(60)");
    create(100, 10L, false);
    assertEquals(1L, store.count("SELECT COUNT(*) FROM rm_demand WHERE form_key='A'"));
    Map<String, Object> deleted = forms.deleteCollection("A", 1L);
    assertEquals(1L, number(deleted.get("demandCount")));
    assertEquals(1L, number(deleted.get("dataCount")));
    assertTrue(
        store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key='A' AND is_deleted=TRUE")
            == 1);
    assertEquals(0, forms.list(null, false).size());
    assertThrows(BaseException.class, () -> access.requireFill("A", 10L, false));
    assertEquals(true, forms.info("A", null).get("deleted"));
    assertEquals(1, forms.deletedForms(1L).size());
    assertEquals(1L, number(forms.deletedForms(1L).get(0).get("demandCount")));
    assertThrows(BaseException.class, () -> forms.purgeCollection("A", 1L));
    forms.restoreCollection("A", 1L);
    access.requireFill("A", 10L, false);
    assertEquals(0, forms.deletedForms(1L).size());

    assertThrows(BaseException.class, () -> forms.deleteCollection("A", 60L));
    assertThrows(BaseException.class, () -> forms.purgeCollection("A", 60L));
    forms.member("A", 60L, body("canManage", true), 1L);
    forms.deleteCollection("A", 60L);
    forms.restoreCollection("A", 60L);

    jdbc.update(
        "INSERT INTO fm_user_form(form_key,name,description,status,user_id) VALUES('D','空表单','测试',0,1)");
    jdbc.update(
        "INSERT INTO rm_demand_form(form_key,visibility,allow_anonymous,published) VALUES('D','LOGIN',FALSE,FALSE)");
    jdbc.update("INSERT INTO fm_user_form_item(form_key,form_item_id) VALUES('D','i1')");
    Map<String, Object> purged = forms.purgeCollection("D", 1L);
    assertEquals(true, purged.get("purged"));
    assertEquals(0L, store.count("SELECT COUNT(*) FROM fm_user_form WHERE form_key='D'"));
    assertEquals(0L, store.count("SELECT COUNT(*) FROM rm_demand_form WHERE form_key='D'"));
    assertEquals(0L, store.count("SELECT COUNT(*) FROM fm_user_form_item WHERE form_key='D'"));
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
    assertEquals(0L, workflow.list(20L, "mine", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(1L, workflow.list(30L, "table", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertThrows(BaseException.class, () -> workflow.reply(id, "REPLY", "未分派回复", 30L));
    assignTo(id, 30);
    assertThrows(BaseException.class, () -> workflow.reply(id, "REPLY", "越权回复", 40L));
    workflow.reply(id, "REPLY", "正式回复", 30L);
    List<Map<String, Object>> rows =
        (List<Map<String, Object>>)
            workflow.list(30L, "table", "", "", "", "", "", "", "", 1, 20).get("rows");
    assertNotNull(rows.get(0).get("replyTime"));
    assertEquals(
        0L,
        workflow.list(30L, "table", "", "", "", "", "", "2000-01-01", "2000-12-31", 1, 20)
            .get("total"));
    assertEquals(
        1L,
        workflow.list(30L, "table", "", "", "", "", "", "2000-01-01", "2099-12-31", 1, 20)
            .get("total"));
    assertThrows(
        BaseException.class,
        () -> workflow.outcome(id, body("revision", 1, "decision", "REJECTED"), 1L));
  }

  @Test
  void summaryGroupsByFormAndKeepsScopeIsolation() {
    String first = create(100, 10L, false);
    String second = create(101, 10L, false);
    jdbc.update(
        "INSERT INTO fm_user_form(form_key,name,description,status,user_id) VALUES('B','设备改善','测试',2,1)");
    jdbc.update(
        "INSERT INTO rm_demand_form(form_key,visibility,allow_anonymous,published) VALUES('B','LOGIN',TRUE,TRUE)");
    jdbc.update("UPDATE rm_demand SET form_key='B' WHERE id=?", second);
    assignTo(first, 30);
    List<Map<String, Object>> mine = workflow.summary(10L, "mine");
    assertEquals(2, mine.size());
    Map<String, Object> a = summaryRow(mine, "A");
    Map<String, Object> b = summaryRow(mine, "B");
    assertEquals("需求征集", a.get("formName"));
    assertEquals(1L, ((Number) a.get("total")).longValue());
    assertEquals(1L, ((Number) a.get("unread")).longValue());
    assertEquals(1L, ((Number) a.get("pending")).longValue());
    assertEquals("设备改善", b.get("formName"));
    assertEquals(0L, ((Number) b.get("unread")).longValue());
    assertTrue(workflow.summary(20L, "mine").isEmpty());
    List<Map<String, Object>> pending = workflow.summary(30L, "pending");
    assertEquals(1, pending.size());
    assertEquals("A", pending.get(0).get("formKey"));
    assertThrows(BaseException.class, () -> workflow.summary(10L, "unknown"));
  }

  private Map<String, Object> summaryRow(List<Map<String, Object>> rows, String formKey) {
    return rows.stream()
        .filter(r -> formKey.equals(r.get("formKey")))
        .findFirst()
        .orElseThrow(AssertionError::new);
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
    workflow.reply(id, "REPLY", "正式回复", 30L);
    Map<String, Object> p =
        body(
            "published", true, "version", 0, "title", "公开标题", "summary", "脱敏摘要", "content", "正式说明");
    assertThrows(BaseException.class, () -> workflow.publish(id, p, 30L));
    workflow.publish(id, p, 1L);
    Map<String, Object> publicRow = workflow.publications(20L, false).get(0);
    assertEquals("A", publicRow.get("formKey"));
    assertEquals("需求征集", publicRow.get("formName"));
    assertEquals("人员10", publicRow.get("submitterName"));
    assertEquals("人员30", publicRow.get("handlerName"));
    assertNotNull(publicRow.get("createTime"));
    String publicationId = publicRow.get("id").toString();
    assertEquals(1L, access.capabilities(20L).get("publicationUnread"));
    outcome(id, "ACCEPTED", "COMPLETED", "实施完成", "私有完成记录");
    Map<String, Object> published = workflow.publication(publicationId, 20L);
    assertEquals("PLANNED", published.get("progress"));
    assertEquals("需求征集", published.get("formName"));
    assertFalse(published.containsKey("formKey"));
    assertFalse(published.containsKey("originalData"));
    assertFalse(published.containsKey("messages"));
    workflow.read("PUBLICATION", publicationId, 1, 20L);
    assertEquals(0L, access.capabilities(20L).get("publicationUnread"));
    workflow.publish(id, body("published", false, "version", 1), 1L);
    assertTrue(workflow.publications(20L, false).isEmpty());
    assertThrows(BaseException.class, () -> workflow.publication(publicationId, 20L));
  }

  @Test
  void publicationArchiveDeleteAndDeletedFormFiltering() {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    outcome(id, "ACCEPTED", "PLANNED", "准备实施", "");
    workflow.reply(id, "REPLY", "正式回复", 30L);
    workflow.publish(
        id,
        body("published", true, "version", 0, "title", "标题", "summary", "摘要", "content", "正文"),
        1L);
    Map<String, Object> row = workflow.publications(50L, false).get(0);
    String publicationId = row.get("id").toString();
    long version = number(row.get("version"));
    assertEquals(Boolean.FALSE, row.get("archived"));
    assertThrows(
        BaseException.class,
        () ->
            workflow.archivePublication(
                publicationId, body("archived", true, "version", version), 20L));

    workflow.archivePublication(publicationId, body("archived", true, "version", version), 1L);
    assertTrue(workflow.publications(20L, false).isEmpty());
    assertEquals(1, workflow.publications(50L, true).size());
    assertEquals(Boolean.TRUE, workflow.publications(50L, true).get(0).get("archived"));
    assertEquals(0L, access.capabilities(20L).get("publicationUnread"));
    assertEquals(0L, workflow.list(50L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));

    long archivedVersion = number(workflow.publications(50L, true).get(0).get("version"));
    workflow.archivePublication(
        publicationId, body("archived", false, "version", archivedVersion), 1L);
    assertEquals(1, workflow.publications(20L, false).size());
    assertEquals(0L, workflow.list(50L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));

    long restoredVersion = number(workflow.publications(20L, false).get(0).get("version"));
    workflow.deletePublication(publicationId, body("version", restoredVersion), 1L);
    assertTrue(workflow.publications(20L, false).isEmpty());
    assertTrue(workflow.publications(50L, true).isEmpty());
    assertThrows(BaseException.class, () -> workflow.publication(publicationId, 20L));
    assertEquals(1L, workflow.list(50L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));

    workflow.skipPublication(
        id,
        number(store.one("SELECT revision FROM rm_demand WHERE id=?", id).get("revision")),
        1L);
    assertEquals(0L, workflow.list(50L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));

    String second = create(101, 10L, false);
    workflow.reply(second, "REPLY", "正式回复", 1L);
    workflow.publish(
        second,
        body("published", true, "version", 0, "title", "标题二", "summary", "摘要二", "content", "正文二"),
        1L);
    Map<String, Object> secondRow = workflow.publications(20L, false).get(0);
    assertEquals("A", secondRow.get("formKey"));
    forms.deleteCollection("A", 1L);
    assertTrue(workflow.publications(20L, false).isEmpty());
    assertEquals(0L, access.capabilities(20L).get("publicationUnread"));
    assertThrows(
        BaseException.class,
        () -> workflow.publication(secondRow.get("id").toString(), 20L));
  }

  @Test
  void selectedFormPublicationRespectsAudience() {
    String id = create(100, 10L, false);
    workflow.reply(id, "REPLY", "正式回复", 1L);
    workflow.publish(
        id,
        body("published", true, "version", 0, "title", "标题", "summary", "摘要", "content", "正文"),
        1L);
    jdbc.update(
        "UPDATE rm_demand_form SET visibility='SELECTED',allow_anonymous=FALSE WHERE form_key='A'");
    assertTrue(workflow.publications(20L, false).isEmpty());
    assertEquals(0L, access.capabilities(20L).get("publicationUnread"));
    forms.member("A", 20L, body("canFill", true, "canHandle", false), 1L);
    assertEquals(1, workflow.publications(20L, false).size());
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
    jdbc.update("INSERT INTO rm_demand_admin(user_id) VALUES(60)");
    r.setAttribute("userId", 60L);
    assertTrue(guard.preHandle(r, new MockHttpServletResponse(), method));
    r.setServletPath("/user/form/item/create");
    assertTrue(guard.preHandle(r, new MockHttpServletResponse(), method));
    r.setServletPath("/user/form/wx/notify-qrcode");
    assertTrue(guard.preHandle(r, new MockHttpServletResponse(), method));
    r.setServletPath("/user/form/data/list");
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
  @SuppressWarnings("unchecked")
  void followupVisibleToSubmitterAndStaffButNotOtherUsers() {
    String id = create(100, 10L, false);
    long publicVersion =
        number(
            store.one("SELECT public_version FROM rm_demand WHERE id=?", id).get("publicVersion"));
    workflow.followUp(id, "补充说明", 10L);
    Map<String, Object> message =
        store.one(
            "SELECT author_id,author_name,kind,content FROM rm_demand_message WHERE demand_id=? AND kind='FOLLOWUP'",
            id);
    assertNotNull(message);
    assertEquals("10", message.get("authorId"));
    assertEquals("人员10", message.get("authorName"));
    assertEquals("补充说明", message.get("content"));
    // 追问不增加填报者未读。
    assertEquals(publicVersion, number(
        store.one("SELECT public_version FROM rm_demand WHERE id=?", id).get("publicVersion")));
    assertEquals(0L, access.capabilities(10L).get("mineUnread"));

    Map<String, Object> own = workflow.detail(id, 10L, null);
    assertEquals(Boolean.TRUE, own.get("canFollowUp"));
    assertEquals(Boolean.TRUE, own.get("followupPending"));
    assertTrue(
        ((List<Map<String, Object>>) own.get("messages"))
            .stream().anyMatch(m -> "FOLLOWUP".equals(m.get("kind"))));
    Map<String, Object> handler = workflow.detail(id, 30L, null);
    assertEquals(Boolean.FALSE, handler.get("canFollowUp"));
    assertEquals(Boolean.TRUE, handler.get("followupPending"));
    assertTrue(
        ((List<Map<String, Object>>) handler.get("messages"))
            .stream().anyMatch(m -> "FOLLOWUP".equals(m.get("kind"))));
    assertThrows(BaseException.class, () -> workflow.detail(id, 20L, null));

    List<Map<String, Object>> rows =
        (List<Map<String, Object>>)
            workflow.list(30L, "table", "", "", "", "", "", "", "", 1, 20).get("rows");
    assertEquals(Boolean.TRUE, rows.get(0).get("followupPending"));
    assertFalse(rows.get(0).containsKey("followupAt"));
  }

  @Test
  void followupAfterRejectedOrCompletedReturnsToPending() {
    String rejected = create(100, 10L, false);
    assignTo(rejected, 30);
    outcome(rejected, "REJECTED", "NONE", "超出范围", "");
    assertEquals(0L, access.capabilities(30L).get("pending"));
    assertEquals(0L, access.capabilities(50L).get("pending"));
    long publicVersion =
        number(
            store.one("SELECT public_version FROM rm_demand WHERE id=?", rejected)
                .get("publicVersion"));
    workflow.followUp(rejected, "为什么不采纳", 10L);
    assertEquals(
        publicVersion,
        number(
            store.one("SELECT public_version FROM rm_demand WHERE id=?", rejected)
                .get("publicVersion")));
    assertEquals(1L, access.capabilities(30L).get("pending"));
    assertEquals(1L, access.capabilities(50L).get("pending"));
    assertEquals(
        1L, workflow.list(30L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(
        1L, workflow.list(50L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(Boolean.TRUE, workflow.detail(rejected, 30L, null).get("followupPending"));

    String completed = create(101, 10L, false);
    assignTo(completed, 30);
    outcome(completed, "ACCEPTED", "COMPLETED", "采纳", "完成实施");
    // 已完成本身不进入待办，但上一条追问需求仍在待办中。
    assertEquals(1L, access.capabilities(30L).get("pending"));
    workflow.followUp(completed, "验收仍有问题", 10L);
    assertEquals(2L, access.capabilities(30L).get("pending"));
    assertEquals(2L, access.capabilities(50L).get("pending"));
    assertEquals(
        2L, workflow.list(30L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));
    List<Map<String, Object>> summary = workflow.summary(50L, "pending");
    assertEquals(1, summary.size());
    assertEquals(2L, ((Number) summary.get(0).get("pending")).longValue());
  }

  @Test
  void formalReplyClearsFollowupAndNotifiesSubmitter() {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    outcome(id, "REJECTED", "NONE", "超出范围", "");
    long beforeVersion =
        number(
            store.one("SELECT public_version FROM rm_demand WHERE id=?", id).get("publicVersion"));
    workflow.read("DEMAND", id, beforeVersion, 10L);
    assertEquals(0L, access.capabilities(10L).get("mineUnread"));
    workflow.followUp(id, "追问内容", 10L);
    assertEquals(1L, access.capabilities(30L).get("pending"));
    // 追问本身不增加填报者未读。
    assertEquals(0L, access.capabilities(10L).get("mineUnread"));

    workflow.reply(id, "REPLY", "已答复追问", 30L);
    assertNull(store.one("SELECT followup_at FROM rm_demand WHERE id=?", id).get("followupAt"));
    assertEquals(Boolean.FALSE, workflow.detail(id, 10L, null).get("followupPending"));
    assertEquals(0L, access.capabilities(30L).get("pending"));
    assertEquals(0L, access.capabilities(50L).get("pending"));
    assertEquals(1L, access.capabilities(10L).get("mineUnread"));
    long version =
        number(
            store.one("SELECT public_version FROM rm_demand WHERE id=?", id).get("publicVersion"));
    workflow.read("DEMAND", id, version, 10L);
    assertEquals(0L, access.capabilities(10L).get("mineUnread"));

    // 正式回复之后的新追问重新进入待办。
    workflow.followUp(id, "还有疑问", 10L);
    assertEquals(1L, access.capabilities(30L).get("pending"));
    assertEquals(Boolean.TRUE, workflow.detail(id, 30L, null).get("followupPending"));
  }

  @Test
  void noteEventAndDraftDoNotClearFollowup() {
    String id = create(100, 10L, false);
    assignTo(id, 30);
    workflow.followUp(id, "追问内容", 10L);
    workflow.reply(id, "NOTE", "内部备注", 30L);
    assertNotNull(store.one("SELECT followup_at FROM rm_demand WHERE id=?", id).get("followupAt"));
    assertEquals(Boolean.TRUE, workflow.detail(id, 30L, null).get("followupPending"));
    workflow.reply(id, "DRAFT", "内部草稿", 30L);
    assertNotNull(store.one("SELECT followup_at FROM rm_demand WHERE id=?", id).get("followupAt"));
    outcome(id, "ACCEPTED", "IN_PROGRESS", "已安排实施", "");
    assertNotNull(store.one("SELECT followup_at FROM rm_demand WHERE id=?", id).get("followupAt"));
    assertEquals(1L, workflow.list(30L, "pending", "", "", "", "", "", "", "", 1, 20).get("total"));
    assertEquals(
        1L,
        store.count(
            "SELECT COUNT(*) FROM rm_demand_message WHERE demand_id=? AND kind='FOLLOWUP'", id));
  }

  @Test
  void followupRejectsNonOwnerAnonymousDeletedFormAndInvalidContent() {
    String id = create(100, 10L, false);
    BaseException nonOwner =
        assertThrows(BaseException.class, () -> workflow.followUp(id, "越权追问", 20L));
    assertEquals(403, nonOwner.getCode());
    assertEquals(
        0L,
        store.count(
            "SELECT COUNT(*) FROM rm_demand_message WHERE demand_id=? AND kind='FOLLOWUP'", id));
    assertThrows(BaseException.class, () -> workflow.followUp(id, "   ", 10L));
    assertThrows(
        BaseException.class,
        () -> workflow.followUp(id, String.join("", Collections.nCopies(10001, "长")), 10L));
    assertNull(store.one("SELECT followup_at FROM rm_demand WHERE id=?", id).get("followupAt"));

    String anonymous = create(101, 10L, true);
    assertThrows(BaseException.class, () -> workflow.followUp(anonymous, "匿名追问", 10L));
    assertThrows(BaseException.class, () -> workflow.followUp(anonymous, "匿名追问", null));
    assertNull(
        store.one("SELECT followup_at FROM rm_demand WHERE id=?", anonymous).get("followupAt"));

    String removed = create(102, 10L, false);
    jdbc.update("UPDATE fm_user_form SET is_deleted=TRUE WHERE form_key='A'");
    BaseException deleted =
        assertThrows(BaseException.class, () -> workflow.followUp(removed, "已删除", 10L));
    assertEquals(403, deleted.getCode());
    assertThrows(BaseException.class, () -> workflow.followUp("missing", "内容", 10L));
  }

  @Test
  void httpEndpointsApplyRealAuthorizationInterceptor() throws Exception {
    String id = create(100, 10L, false);
    jdbc.update("INSERT INTO rm_demand_admin(user_id) VALUES(60)");
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
                new DemandController(access, forms, workflow, export, departments, formFields, store))
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
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/demand-api/admin/forms")
                .header("token", "qa60"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(200))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data")
                .isEmpty());
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/demand-api/admin/roles")
                .header("token", "qa60"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(403));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/demand-api/requests")
                .param("scope", "managed")
                .header("token", "qa60"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(200))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                    "$.data.total")
                .value(0));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/demand-api/forms/A/handlers")
                .header("token", "qa50"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(200));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/demand-api/forms/A/handlers")
                .header("token", "qa30"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(403));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/demand-api/export/requests")
                .param("scope", "table")
                .header("token", "qa30"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.header()
                .string(
                    "Content-Type",
                    org.hamcrest.Matchers.containsString("spreadsheetml")));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/demand-api/requests/" + id + "/publication/skip")
                .header("token", "qa30")
                .contentType("application/json")
                .content("{\"revision\":" + revision(id) + "}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(403));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/demand-api/requests/" + id + "/publication/skip")
                .header("token", "qa50")
                .contentType("application/json")
                .content("{\"revision\":" + revision(id) + "}"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(200));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/demand-api/admin/forms/A/members/30")
                .header("token", "qa30"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(403));
    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/demand-api/admin/forms/A/members/30")
                .header("token", "qa1"))
        .andExpect(
            org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                .value(200));
    assertFalse(
        forms.members("A", 1L).stream().anyMatch(row -> number(row.get("userId")) == 30L));
  }
}
