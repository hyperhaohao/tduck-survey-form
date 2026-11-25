-- 2026-11-25 平台能力细分：单据管理（含建单/设计/AI）、组织架构、用户管理。
-- 已有库执行一次；执行前备份。部署顺序：迁移 -> 后端 -> 前端。
-- 存量后台管理员保留原有能力，统一迁移为 form.manage；组织架构与用户管理需另行授予。
-- rm_demand_admin 保留用于回滚与 OIDC 首次登录并发锁，不作为新鉴权依据。

CREATE TABLE IF NOT EXISTS rm_demand_permission (
 user_id BIGINT NOT NULL,
 capability VARCHAR(32) NOT NULL,
 granted_by BIGINT NULL,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 PRIMARY KEY(user_id, capability)
);

INSERT INTO rm_demand_permission(user_id, capability, granted_by)
SELECT a.user_id, 'form.manage', NULL
FROM rm_demand_admin a
WHERE a.user_id <> 0
  AND NOT EXISTS (
    SELECT 1 FROM rm_demand_permission p
    WHERE p.user_id = a.user_id AND p.capability = 'form.manage'
  );

-- 核对 1：旧后台管理员数量。
SELECT COUNT(*) AS legacy_admins FROM rm_demand_admin;

-- 核对 2：迁移后缺少 form.manage 的旧管理员应为 0。
SELECT COUNT(*) AS missing_form_manage
FROM rm_demand_admin a
WHERE a.user_id <> 0
  AND NOT EXISTS (
    SELECT 1 FROM rm_demand_permission p
    WHERE p.user_id = a.user_id AND p.capability = 'form.manage'
  );

-- 核对 3：列出旧后台管理员，供补授组织架构/用户管理时人工复核。
SELECT a.user_id, u.name
FROM rm_demand_admin a
JOIN ac_user u ON u.id = a.user_id
WHERE a.user_id <> 0
ORDER BY u.name;

-- 回滚代码时保留本表；确认不再回退后才可删除（会丢失细分授权）。
