package com.tduck.cloud.api.demand;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.util.*;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

/** 钉钉 outbox/待办派发测试；不访问真实钉钉。 */
class DemandPushTest {
  JdbcTemplate jdbc;
  DemandStore store;
  DemandDingTalkProperties props;
  DemandPushSettings settings;
  DemandDingTalkDirectory directory;
  DemandPush push;
  FakeClient client;

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
        "CREATE TABLE ac_user(id BIGINT PRIMARY KEY,name VARCHAR(100),email VARCHAR(100),deleted BOOLEAN DEFAULT FALSE)");
    jdbc.update("INSERT INTO ac_user(id,name,email) VALUES(10,'人员10','u10@example.com')");
    props = new DemandDingTalkProperties();
    props.setEnabled(true);
    props.setAppKey("key");
    props.setAppSecret("secret");
    props.setAgentId(123);
    props.setLinkBase("http://example.test");
    props.setCreatorUnionId("creator-union");
    client = new FakeClient(props);
    directory = new DemandDingTalkDirectory(store, client, props);
    settings = new DemandPushSettings(store, new com.tduck.cloud.api.demand.DemandProperties(), props);
    Map<String, Object> values = new LinkedHashMap<>();
    values.put("enabled", "true");
    values.put("quiet.start", "00:00");
    values.put("quiet.end", "00:00");
    settings.save(values, 1L);
    push = new DemandPush(store, settings, client, directory, props);
    jdbc.update(
        "INSERT INTO rm_demand_dingtalk_user(dingtalk_user_id,union_id,user_id,name,email,active,status,confirmed_at) VALUES('ding-10','union-10',10,'人员10','u10@example.com',TRUE,'CONFIRMED',CURRENT_TIMESTAMP)");
  }

  @Test
  void approvalCreatesWorkAndTodoThenCompletesOnStepDone() {
    push.enqueue(event("n1", DemandNotices.APPROVAL, "d1", 1, null));
    assertEquals(2L, count("SELECT COUNT(*) FROM rm_demand_push"));
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE channel='WORK' AND status='PENDING'"));
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE channel='TODO' AND status='PENDING'"));
    assertEquals(2, push.dispatch());
    assertEquals(1, client.workCalls);
    assertEquals(1, client.todoCreates);
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE channel='WORK' AND status='SENT'"));
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE channel='TODO' AND status='SENT' AND todo_task_id IS NOT NULL"));

    push.completeApprovalStep("d1", 1);
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE action='COMPLETE' AND status='PENDING'"));
    push.dispatch();
    assertEquals(1, client.todoCompletes);
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE action='CREATE' AND channel='TODO' AND status='DONE'"));
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE action='COMPLETE' AND status='SENT'"));
  }

  @Test
  void requiredTodoIsCompletedOnSubmission() {
    push.enqueue(event("n2", DemandNotices.REQUIRED, null, null, 2));
    push.dispatch();
    assertEquals(1, client.todoCreates);
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE channel='TODO' AND status='SENT'"));
    push.completeRequired(10L, "A", 2);
    push.dispatch();
    assertEquals(1, client.todoCompletes);
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE action='CREATE' AND channel='TODO' AND status='DONE'"));
  }

  @Test
  void workNotificationSkipsWhenStationNoticeRead() {
    jdbc.update(
        "INSERT INTO rm_demand_notice(id,user_id,kind,title,content,read_at,create_time) VALUES('read-n',10,'APPROVAL','t','c',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
    push.enqueue(event("read-n", DemandNotices.APPROVAL, "d5", 1, null));
    push.dispatch();
    assertEquals(0, client.workCalls);
    assertEquals(1, client.todoCreates);
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE channel='WORK' AND status='SKIPPED'"));
  }

  @Test
  void noMappingCreatesSkippedRowsAndNoCalls() {
    jdbc.update("UPDATE rm_demand_dingtalk_user SET status='UNMATCHED',confirmed_at=NULL WHERE user_id=10");
    push.enqueue(event("n3", DemandNotices.APPROVAL, "d3", 1, null));
    assertEquals(2L, count("SELECT COUNT(*) FROM rm_demand_push WHERE status='SKIPPED'"));
    push.dispatch();
    assertEquals(0, client.workCalls + client.todoCreates);
  }

  @Test
  void noCreatorSkipsTodoButSendsWork() {
    props.setCreatorUnionId("");
    // 重新构造目录以读取新配置
    directory = new DemandDingTalkDirectory(store, client, props);
    push = new DemandPush(store, settings, client, directory, props);
    push.enqueue(event("n4", DemandNotices.APPROVAL, "d4", 1, null));
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE channel='TODO' AND status='SKIPPED' AND last_error='NO_CREATOR'"));
    push.dispatch();
    assertEquals(1, client.workCalls);
    assertEquals(0, client.todoCreates);
  }

  @Test
  void repeatedRequiredNoticeCreatesOnlyOneTodo() {
    push.enqueue(event("n5", DemandNotices.REQUIRED, null, null, 3));
    push.enqueue(event("n6", DemandNotices.REQUIRED, null, null, 3));
    assertEquals(2L, count("SELECT COUNT(*) FROM rm_demand_push WHERE channel='WORK'"));
    assertEquals(1L, count("SELECT COUNT(*) FROM rm_demand_push WHERE channel='TODO' AND action='CREATE'"));
  }

  private DemandNoticeCreatedEvent event(
      String noticeId, String kind, String demandId, Integer stepNo, Integer roundNo) {
    return new DemandNoticeCreatedEvent(
        noticeId,
        10L,
        kind,
        "A",
        demandId,
        null,
        roundNo,
        stepNo,
        "有新的需求待您审批",
        "需求A · 第1级 技术部",
        roundNo == null ? null : new Timestamp(System.currentTimeMillis() + 3600_000L));
  }

  private long count(String sql) {
    return jdbc.queryForObject(sql, Long.class);
  }

  static class FakeClient extends DemandDingTalkClient {
    int workCalls;
    int todoCreates;
    int todoCompletes;

    FakeClient(DemandDingTalkProperties properties) {
      super(properties, new ObjectMapper());
    }

    @Override
    public String sendWork(String userIds, Map<String, Object> message) {
      workCalls++;
      return "work-task";
    }

    @Override
    public String createTodo(
        String creatorUnionId,
        String executorUnionId,
        String subject,
        String description,
        Long dueTime,
        String detailUrl,
        String sourceId) {
      todoCreates++;
      return "todo-task-" + todoCreates;
    }

    @Override
    public void completeTodo(String creatorUnionId, String taskId) {
      todoCompletes++;
    }

    @Override
    public void deleteTodo(String creatorUnionId, String taskId) {
      /* no-op */
    }
  }
}
