-- 瑞玛需求模块：收集单成员权限增加部门维度。已有库执行一次；不修改现有表。
CREATE TABLE IF NOT EXISTS rm_demand_dept (
 dept_key VARCHAR(64) PRIMARY KEY, name VARCHAR(100) NOT NULL, parent_key VARCHAR(64) NULL,
 path VARCHAR(512) NOT NULL, sort_no INT NOT NULL DEFAULT 0, active BOOLEAN NOT NULL DEFAULT TRUE,
 synced_at TIMESTAMP NULL
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
