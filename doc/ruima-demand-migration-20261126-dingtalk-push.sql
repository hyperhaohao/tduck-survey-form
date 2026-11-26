-- 瑞玛需求模块 2026-11-26 增量迁移：钉钉工作通知与待办 outbox。
-- 仅执行一次；执行前备份。新库只执行 ruima-demand-schema.sql，不回放本脚本。

CREATE TABLE IF NOT EXISTS rm_demand_dingtalk_user (
  dingtalk_user_id VARCHAR(64) NOT NULL PRIMARY KEY,
  union_id VARCHAR(64) NULL,
  user_id BIGINT NULL,
  name VARCHAR(100) NULL,
  mobile VARCHAR(32) NULL,
  email VARCHAR(200) NULL,
  dept_names VARCHAR(500) NULL,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  status VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED',
  match_source VARCHAR(16) NULL,
  confirmed_by BIGINT NULL,
  confirmed_at TIMESTAMP NULL,
  synced_at TIMESTAMP NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (user_id)
);
CREATE INDEX idx_ding_user_status ON rm_demand_dingtalk_user(status, active);
CREATE INDEX idx_ding_user_union ON rm_demand_dingtalk_user(union_id);

CREATE TABLE IF NOT EXISTS rm_demand_push (
  id VARCHAR(36) NOT NULL PRIMARY KEY,
  notice_id VARCHAR(36) NULL,
  user_id BIGINT NOT NULL,
  dingtalk_user_id VARCHAR(64) NULL,
  union_id VARCHAR(64) NULL,
  channel VARCHAR(16) NOT NULL,
  action VARCHAR(16) NOT NULL,
  kind VARCHAR(24) NOT NULL,
  form_key VARCHAR(64) NULL,
  demand_id VARCHAR(36) NULL,
  publication_id VARCHAR(36) NULL,
  round_no INT NULL,
  step_no INT NULL,
  due_at TIMESTAMP NULL,
  parent_id VARCHAR(36) NULL,
  source_id VARCHAR(128) NULL,
  todo_task_id VARCHAR(64) NULL,
  provider_task_id VARCHAR(64) NULL,
  title VARCHAR(200) NOT NULL,
  content VARCHAR(1000) NULL,
  link_url VARCHAR(512) NULL,
  dedupe_key VARCHAR(64) NOT NULL,
  batch_key VARCHAR(64) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_retry_at TIMESTAMP NULL,
  lease_until TIMESTAMP NULL,
  locked_by VARCHAR(64) NULL,
  last_error VARCHAR(500) NULL,
  sent_at TIMESTAMP NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (dedupe_key)
);
CREATE INDEX idx_push_due ON rm_demand_push(status, next_retry_at);
CREATE INDEX idx_push_lease ON rm_demand_push(status, lease_until);
CREATE INDEX idx_push_batch ON rm_demand_push(batch_key, status);
CREATE INDEX idx_push_notice ON rm_demand_push(notice_id);
CREATE INDEX idx_push_user ON rm_demand_push(user_id, create_time);
CREATE INDEX idx_push_parent ON rm_demand_push(parent_id);
CREATE INDEX idx_push_source ON rm_demand_push(source_id);

CREATE TABLE IF NOT EXISTS rm_demand_push_setting (
  setting_key VARCHAR(64) NOT NULL PRIMARY KEY,
  setting_value VARCHAR(500) NOT NULL,
  updated_by BIGINT NULL,
  update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 执行后核对：
-- SELECT COUNT(*) FROM rm_demand_dingtalk_user;
-- SELECT COUNT(*) FROM rm_demand_push;
-- SELECT COUNT(*) FROM rm_demand_push_setting;
