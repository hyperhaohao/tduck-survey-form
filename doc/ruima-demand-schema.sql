-- 瑞玛需求模块：新增表，不修改原表单表。首次启用前执行一次；后续升级按增量迁移，不重复创建索引。
CREATE TABLE IF NOT EXISTS rm_demand_form (
 form_key VARCHAR(64) PRIMARY KEY, summary VARCHAR(2000), visibility VARCHAR(16) NOT NULL DEFAULT 'LOGIN',
 allow_anonymous BOOLEAN NOT NULL DEFAULT FALSE, published BOOLEAN NOT NULL DEFAULT FALSE,
 end_time TIMESTAMP NULL, voided BOOLEAN NOT NULL DEFAULT FALSE,
 voided_by BIGINT NULL, voided_at TIMESTAMP NULL, deleted_by BIGINT NULL, deleted_at TIMESTAMP NULL,
 revision INT NOT NULL DEFAULT 0, sort_no INT NOT NULL DEFAULT 0, collect_only BOOLEAN NOT NULL DEFAULT FALSE,
 round_mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE', round_period VARCHAR(16) NULL, round_day INT NULL, round_no INT NOT NULL DEFAULT 0,
 round_start_time TIMESTAMP NULL, round_end_time TIMESTAMP NULL,
 reminder_mode VARCHAR(16) NOT NULL DEFAULT 'DEFAULT', require_members BOOLEAN NOT NULL DEFAULT FALSE,
 requires_leader_approval BOOLEAN NOT NULL DEFAULT FALSE, approval_mode VARCHAR(16) NOT NULL DEFAULT 'NONE'
);
CREATE TABLE IF NOT EXISTS rm_demand_acl (
 form_key VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, can_fill BOOLEAN NOT NULL DEFAULT FALSE,
 can_handle BOOLEAN NOT NULL DEFAULT FALSE, can_manage BOOLEAN NOT NULL DEFAULT FALSE,
 required BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(form_key,user_id)
);
CREATE TABLE IF NOT EXISTS rm_demand_dept (
 dept_key VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL, parent_key VARCHAR(64) NULL,
 path VARCHAR(512) NOT NULL, sort_no INT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE,
 leader_id BIGINT NULL, synced_at TIMESTAMP NULL
);
CREATE INDEX idx_rm_demand_dept_parent ON rm_demand_dept(parent_key);
CREATE INDEX idx_rm_demand_dept_path ON rm_demand_dept(path);
CREATE TABLE IF NOT EXISTS rm_demand_dept_leader (
 dept_key VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, sort_no INT NOT NULL DEFAULT 0,
 created_by BIGINT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(dept_key,user_id)
);
CREATE INDEX idx_rm_dept_leader_user ON rm_demand_dept_leader(user_id);
CREATE TABLE IF NOT EXISTS rm_demand_user_dept (
 user_id BIGINT NOT NULL, dept_key VARCHAR(64) NOT NULL,
 PRIMARY KEY(user_id,dept_key)
);
CREATE INDEX idx_rm_demand_user_dept_dept ON rm_demand_user_dept(dept_key);
CREATE TABLE IF NOT EXISTS rm_demand_acl_dept (
 form_key VARCHAR(64) NOT NULL, dept_key VARCHAR(64) NOT NULL,
 can_fill BOOLEAN NOT NULL DEFAULT FALSE, can_handle BOOLEAN NOT NULL DEFAULT FALSE,
 can_manage BOOLEAN NOT NULL DEFAULT FALSE, created_by BIGINT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, required BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(form_key,dept_key)
);
CREATE INDEX idx_rm_demand_acl_dept_key ON rm_demand_acl_dept(dept_key);
CREATE TABLE IF NOT EXISTS rm_demand_admin (user_id BIGINT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS rm_demand_permission (
 user_id BIGINT NOT NULL, capability VARCHAR(32) NOT NULL, granted_by BIGINT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id,capability)
);
CREATE TABLE IF NOT EXISTS rm_demand (
 id VARCHAR(36) PRIMARY KEY, data_id VARCHAR(32) NOT NULL UNIQUE, form_key VARCHAR(64) NOT NULL,
 request_no VARCHAR(64) NOT NULL UNIQUE, title VARCHAR(200) NOT NULL, submitter_id BIGINT NULL, submitter_name VARCHAR(100) NOT NULL,
 anonymous BOOLEAN NOT NULL, submitted_by BIGINT NULL, handler_id BIGINT NULL,
 decision VARCHAR(20) NOT NULL DEFAULT 'PENDING', progress VARCHAR(20) NOT NULL DEFAULT 'NONE',
 reason TEXT, completion TEXT, department TEXT NULL, query_hash VARCHAR(64), revision INT NOT NULL DEFAULT 0,
 public_version BIGINT NOT NULL DEFAULT 1, publication_skipped BOOLEAN NOT NULL DEFAULT FALSE,
 followup_at TIMESTAMP NULL,
 approval_status VARCHAR(16) NOT NULL DEFAULT 'NONE', approval_leader_id BIGINT NULL,
 approval_comment VARCHAR(2000) NULL, approval_at TIMESTAMP NULL,
 approval_mode VARCHAR(16) NOT NULL DEFAULT 'NONE', approval_step INT NOT NULL DEFAULT 0,
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rm_demand_submitter ON rm_demand(submitter_id,create_time);
CREATE INDEX idx_rm_demand_handler ON rm_demand(handler_id,decision,progress);
CREATE INDEX idx_rm_demand_form ON rm_demand(form_key,create_time);
CREATE TABLE IF NOT EXISTS rm_demand_approval_step (
 id VARCHAR(36) PRIMARY KEY, demand_id VARCHAR(36) NOT NULL, step_no INT NOT NULL,
 dept_key VARCHAR(64) NULL, dept_name VARCHAR(512) NULL, status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
 comment_text VARCHAR(2000) NULL, acted_by BIGINT NULL, acted_at TIMESTAMP NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(demand_id,step_no)
);
CREATE INDEX idx_rm_approval_step ON rm_demand_approval_step(demand_id,status);
CREATE TABLE IF NOT EXISTS rm_demand_approval_approver (
 step_id VARCHAR(36) NOT NULL, user_id BIGINT NOT NULL, user_name VARCHAR(100) NOT NULL,
 PRIMARY KEY(step_id,user_id)
);
CREATE INDEX idx_rm_approval_approver_user ON rm_demand_approval_approver(user_id);
CREATE TABLE IF NOT EXISTS rm_demand_message (
 id VARCHAR(36) PRIMARY KEY, demand_id VARCHAR(36) NOT NULL, author_id BIGINT NOT NULL,
 author_name VARCHAR(100) NOT NULL, kind VARCHAR(16) NOT NULL, content TEXT NOT NULL,
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rm_message_demand ON rm_demand_message(demand_id,create_time);
CREATE TABLE IF NOT EXISTS rm_demand_read (
 user_id BIGINT NOT NULL, object_type VARCHAR(16) NOT NULL, object_id VARCHAR(36) NOT NULL,
 seen_version BIGINT NOT NULL DEFAULT 0, PRIMARY KEY(user_id,object_type,object_id)
);
CREATE TABLE IF NOT EXISTS rm_demand_publication (
 id VARCHAR(36) PRIMARY KEY, demand_id VARCHAR(36) NOT NULL UNIQUE, title VARCHAR(200) NOT NULL,
 summary TEXT NOT NULL, content TEXT NOT NULL, published BOOLEAN NOT NULL DEFAULT FALSE,
 version BIGINT NOT NULL DEFAULT 0, decision VARCHAR(20), progress VARCHAR(20),
 archived_at TIMESTAMP NULL, archived_by BIGINT NULL, deleted_at TIMESTAMP NULL, deleted_by BIGINT NULL,
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE IF NOT EXISTS rm_demand_identity (
 identity_hash VARCHAR(64) PRIMARY KEY, issuer VARCHAR(255) NOT NULL, subject VARCHAR(255) NOT NULL, user_id BIGINT NOT NULL,
 UNIQUE(user_id)
);
CREATE TABLE IF NOT EXISTS rm_demand_login_state (
 state_hash VARCHAR(64) PRIMARY KEY, browser_hash VARCHAR(64) NOT NULL, nonce VARCHAR(100) NOT NULL,
 verifier VARCHAR(100) NOT NULL, return_path VARCHAR(1000) NOT NULL, expires_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS rm_demand_login_ticket (
 ticket_hash VARCHAR(64) PRIMARY KEY, user_id BIGINT NOT NULL, browser_hash VARCHAR(64) NOT NULL,
 expires_at TIMESTAMP NOT NULL
);
CREATE TABLE IF NOT EXISTS rm_demand_required (
 form_key VARCHAR(64) NOT NULL, subject_type VARCHAR(8) NOT NULL, subject_id VARCHAR(64) NOT NULL,
 created_by BIGINT NULL, created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(form_key,subject_type,subject_id)
);
CREATE INDEX idx_required_form ON rm_demand_required(form_key);
CREATE TABLE IF NOT EXISTS rm_demand_notice (
 id VARCHAR(36) PRIMARY KEY, user_id BIGINT NOT NULL, kind VARCHAR(24) NOT NULL,
 form_key VARCHAR(64) NULL, demand_id VARCHAR(36) NULL, publication_id VARCHAR(36) NULL,
 round_no INT NULL, step_no INT NULL, title VARCHAR(200) NOT NULL, content VARCHAR(1000) NULL,
 read_at TIMESTAMP NULL, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notice_user ON rm_demand_notice(user_id,create_time);
CREATE INDEX idx_notice_user_read ON rm_demand_notice(user_id,read_at);
CREATE INDEX idx_notice_demand ON rm_demand_notice(demand_id);
CREATE INDEX idx_notice_publication ON rm_demand_notice(publication_id);
CREATE TABLE IF NOT EXISTS rm_demand_dingtalk_user (
 dingtalk_user_id VARCHAR(64) PRIMARY KEY, union_id VARCHAR(64) NULL, user_id BIGINT NULL,
 name VARCHAR(100) NULL, mobile VARCHAR(32) NULL, email VARCHAR(200) NULL,
 dept_names VARCHAR(500) NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
 status VARCHAR(20) NOT NULL DEFAULT 'UNMATCHED', match_source VARCHAR(16) NULL,
 confirmed_by BIGINT NULL, confirmed_at TIMESTAMP NULL, synced_at TIMESTAMP NULL,
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(user_id)
);
CREATE INDEX idx_ding_user_status ON rm_demand_dingtalk_user(status,active);
CREATE INDEX idx_ding_user_union ON rm_demand_dingtalk_user(union_id);
CREATE TABLE IF NOT EXISTS rm_demand_push (
 id VARCHAR(36) PRIMARY KEY, notice_id VARCHAR(36) NULL, user_id BIGINT NOT NULL,
 dingtalk_user_id VARCHAR(64) NULL, union_id VARCHAR(64) NULL,
 channel VARCHAR(16) NOT NULL, action VARCHAR(16) NOT NULL, kind VARCHAR(24) NOT NULL,
 form_key VARCHAR(64) NULL, demand_id VARCHAR(36) NULL, publication_id VARCHAR(36) NULL,
 round_no INT NULL, step_no INT NULL, due_at TIMESTAMP NULL, parent_id VARCHAR(36) NULL, source_id VARCHAR(128) NULL,
 todo_task_id VARCHAR(64) NULL, provider_task_id VARCHAR(64) NULL,
 title VARCHAR(200) NOT NULL, content VARCHAR(1000) NULL, link_url VARCHAR(512) NULL,
 dedupe_key VARCHAR(64) NOT NULL, batch_key VARCHAR(64) NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'PENDING', attempts INT NOT NULL DEFAULT 0,
 next_retry_at TIMESTAMP NULL, lease_until TIMESTAMP NULL, locked_by VARCHAR(64) NULL,
 last_error VARCHAR(500) NULL, sent_at TIMESTAMP NULL,
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(dedupe_key)
);
CREATE INDEX idx_push_due ON rm_demand_push(status,next_retry_at);
CREATE INDEX idx_push_lease ON rm_demand_push(status,lease_until);
CREATE INDEX idx_push_batch ON rm_demand_push(batch_key,status);
CREATE INDEX idx_push_notice ON rm_demand_push(notice_id);
CREATE INDEX idx_push_user ON rm_demand_push(user_id,create_time);
CREATE INDEX idx_push_parent ON rm_demand_push(parent_id);
CREATE INDEX idx_push_source ON rm_demand_push(source_id);
CREATE TABLE IF NOT EXISTS rm_demand_push_setting (
 setting_key VARCHAR(64) PRIMARY KEY, setting_value VARCHAR(500) NOT NULL,
 updated_by BIGINT NULL, update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
