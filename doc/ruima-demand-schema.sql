-- 瑞玛需求模块：新增表，不修改原表单表。首次启用前执行一次；后续升级按增量迁移，不重复创建索引。
CREATE TABLE IF NOT EXISTS rm_demand_form (
 form_key VARCHAR(64) PRIMARY KEY, summary VARCHAR(2000), visibility VARCHAR(16) NOT NULL DEFAULT 'LOGIN',
 allow_anonymous BOOLEAN NOT NULL DEFAULT FALSE, published BOOLEAN NOT NULL DEFAULT FALSE,
 end_time TIMESTAMP NULL, revision INT NOT NULL DEFAULT 0
);
CREATE TABLE IF NOT EXISTS rm_demand_acl (
 form_key VARCHAR(64) NOT NULL, user_id BIGINT NOT NULL, can_fill BOOLEAN NOT NULL DEFAULT FALSE,
 can_handle BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(form_key,user_id)
);
CREATE TABLE IF NOT EXISTS rm_demand_admin (user_id BIGINT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS rm_demand (
 id VARCHAR(36) PRIMARY KEY, data_id VARCHAR(32) NOT NULL UNIQUE, form_key VARCHAR(64) NOT NULL,
 request_no VARCHAR(64) NOT NULL UNIQUE, title VARCHAR(200) NOT NULL, submitter_id BIGINT NULL, submitter_name VARCHAR(100) NOT NULL,
 anonymous BOOLEAN NOT NULL, submitted_by BIGINT NULL, handler_id BIGINT NULL,
 decision VARCHAR(20) NOT NULL DEFAULT 'PENDING', progress VARCHAR(20) NOT NULL DEFAULT 'NONE',
 reason TEXT, completion TEXT, query_hash VARCHAR(64), revision INT NOT NULL DEFAULT 0,
 public_version BIGINT NOT NULL DEFAULT 1, create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
 version BIGINT NOT NULL DEFAULT 0, decision VARCHAR(20), progress VARCHAR(20), create_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
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
