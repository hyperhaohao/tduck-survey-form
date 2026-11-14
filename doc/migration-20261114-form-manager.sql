-- 2026-11-14 单据管理员与不公示状态升级脚本。
-- 已有数据库仅执行一次；先备份，再按顺序部署 迁移 → 后端 → 前端。新环境直接使用 ruima-demand-schema.sql，不执行本文件。
ALTER TABLE rm_demand_acl ADD COLUMN can_manage BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rm_demand ADD COLUMN publication_skipped BOOLEAN NOT NULL DEFAULT FALSE;

-- 收集单创建人默认成为单据管理员；已有 ACL 行只补管理标记，不覆盖填报、经办权限。
INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle,can_manage)
SELECT f.form_key,f.user_id,FALSE,FALSE,TRUE
FROM fm_user_form f
JOIN rm_demand_form c ON c.form_key=f.form_key
JOIN ac_user u ON u.id=f.user_id
WHERE f.is_deleted=FALSE AND u.deleted=FALSE
ON DUPLICATE KEY UPDATE can_manage=TRUE;

-- 核对零显式管理员的收集单，由全局管理员在成员抽屉补授权。
-- SELECT c.form_key FROM rm_demand_form c
-- LEFT JOIN rm_demand_acl a ON a.form_key=c.form_key AND a.can_manage=TRUE
-- GROUP BY c.form_key HAVING COUNT(a.user_id)=0;
