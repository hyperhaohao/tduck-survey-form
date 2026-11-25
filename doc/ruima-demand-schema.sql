-- 瑞玛需求模块：新增表，不修改原表单表。首次启用前执行一次；后续升级按增量迁移，不重复创建索引。
CREATE TABLE IF NOT EXISTS rm_demand_form (
 form_key VARCHAR(64) PRIMARY KEY, summary VARCHAR(2000), visibility VARCHAR(16) NOT NULL DEFAULT 'LOGIN',
 allow_anonymous BOOLEAN NOT NULL DEFAULT FALSE, published BOOLEAN NOT NULL DEFAULT FALSE,
 end_time TIMESTAMP NULL, voided BOOLEAN NOT NULL DEFAULT FALSE,
 voided_by BIGINT NULL, voided_at TIMESTAMP NULL, deleted_by BIGINT NULL, deleted_at TIMESTAMP NULL,
 revision INT NOT NULL DEFAULT 0, sort_no INT NOT NULL DEFAULT 0, collect_only BOOLEAN NOT NULL DEFAULT FALSE,
 round_mode VARCHAR(16) NOT NULL DEFAULT 'NONE', round_period VARCHAR(16) NULL, round_no INT NOT NULL DEFAULT 0,
 round_start_time TIMESTAMP NULL, round_end_time TIMESTAMP NULL,
 reminder_mode VARCHAR(16) NOT NULL DEFAULT 'DEFAULT', require_members BOOLEAN NOT NULL DEFAULT FALSE,
 requires_leader_approval BOOLEAN NOT NULL DEFAULT FALSE
);
CREATE TABLE IF NOT EXISTS rm_demand_acl (
 form_key VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, can_fill BOOLEAN NOT NULL DEFAULT FALSE,
 can_handle BOOLEAN NOT NULL DEFAULT FALSE, can_manage BOOLEAN NOT NULL DEFAULT FALSE,
 PRIMARY KEY(form_key,user_id)
);
CREATE TABLE IF NOT EXISTS rm_demand_dept (
 dept_key VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL, parent_key VARCHAR(64) NULL,
 path VARCHAR(512) NOT NULL, sort_no INT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE,
 leader_id BIGINT NULL, synced_at TIMESTAMP NULL
);
CREATE INDEX idx_rm_demand_dept_parent ON rm_demand_dept(parent_key);
CREATE INDEX idx_rm_demand_dept_path ON rm_demand_dept(path);
CREATE TABLE IF NOT EXISTS rm_demand_user_dept (
 user_id BIGINT NOT NULL, dept_key VARCHAR(64) NOT NULL,
 PRIMARY KEY(user_id,dept_key)
);
CREATE INDEX idx_rm_demand_user_dept_dept ON rm_demand_user_dept(dept_key);
CREATE TABLE IF NOT EXISTS rm_demand_acl_dept (
 form_key VARCHAR(64) NOT NULL, dept_key VARCHAR(64) NOT NULL,
 can_fill BOOLEAN NOT NULL DEFAULT FALSE, can_handle BOOLEAN NOT NULL DEFAULT FALSE,
 can_manage BOOLEAN NOT NULL DEFAULT FALSE, created_by BIGINT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
 create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 update_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_rm_demand_submitter ON rm_demand(submitter_id,create_time);
CREATE INDEX idx_rm_demand_handler ON rm_demand(handler_id,decision,progress);
CREATE INDEX idx_rm_demand_form ON rm_demand(form_key,create_time);
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
 round_no INT NULL, title VARCHAR(200) NOT NULL, content VARCHAR(1000) NULL,
 read_at TIMESTAMP NULL, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_notice_user ON rm_demand_notice(user_id,create_time);
CREATE INDEX idx_notice_user_read ON rm_demand_notice(user_id,read_at);
CREATE INDEX idx_notice_demand ON rm_demand_notice(demand_id);
CREATE INDEX idx_notice_publication ON rm_demand_notice(publication_id);
