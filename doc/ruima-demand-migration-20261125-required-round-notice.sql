-- 瑞玛需求模块 2026-11-26 增量迁移：单据必填人与本轮期限、站内消息中心。
-- 仅执行一次；执行前停止 app 容器并完成数据库备份。部署顺序：迁移、后端、前端。

ALTER TABLE rm_demand_form
  ADD COLUMN round_mode VARCHAR(16) NOT NULL DEFAULT 'NONE',
  ADD COLUMN round_period VARCHAR(16) NULL,
  ADD COLUMN round_no INT NOT NULL DEFAULT 0,
  ADD COLUMN round_start_time TIMESTAMP NULL,
  ADD COLUMN round_end_time TIMESTAMP NULL,
  ADD COLUMN reminder_mode VARCHAR(16) NOT NULL DEFAULT 'DEFAULT',
  ADD COLUMN require_members BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE IF NOT EXISTS rm_demand_required (
  form_key VARCHAR(64) NOT NULL,
  subject_type VARCHAR(8) NOT NULL,
  subject_id VARCHAR(64) NOT NULL,
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(form_key, subject_type, subject_id)
);
CREATE INDEX idx_required_form ON rm_demand_required(form_key);

CREATE TABLE IF NOT EXISTS rm_demand_notice (
  id VARCHAR(36) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  kind VARCHAR(24) NOT NULL,
  form_key VARCHAR(64) NULL,
  demand_id VARCHAR(36) NULL,
  publication_id VARCHAR(36) NULL,
  round_no INT NULL,
  title VARCHAR(200) NOT NULL,
  content VARCHAR(1000) NULL,
  read_at TIMESTAMP NULL,
  create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notice_user ON rm_demand_notice(user_id, create_time);
CREATE INDEX idx_notice_user_read ON rm_demand_notice(user_id, read_at);
CREATE INDEX idx_notice_demand ON rm_demand_notice(demand_id);
CREATE INDEX idx_notice_publication ON rm_demand_notice(publication_id);

-- 执行后核对：
-- SELECT round_mode,COUNT(*) FROM rm_demand_form GROUP BY round_mode;
-- SELECT COUNT(*) FROM rm_demand_form WHERE round_mode IS NULL OR reminder_mode IS NULL;
-- SHOW TABLES LIKE 'rm_demand_required';
-- SHOW TABLES LIKE 'rm_demand_notice';
