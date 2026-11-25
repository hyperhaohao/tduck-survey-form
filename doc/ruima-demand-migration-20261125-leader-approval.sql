-- 瑞玛需求模块 2026-11-25 增量迁移：部门主管审批。
-- 仅执行一次；执行前停止 app 容器并完成数据库备份。部署顺序：迁移、后端、前端。

ALTER TABLE rm_demand_form ADD COLUMN requires_leader_approval BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE rm_demand ADD COLUMN approval_status VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE rm_demand ADD COLUMN approval_leader_id BIGINT NULL;
ALTER TABLE rm_demand ADD COLUMN approval_comment VARCHAR(2000) NULL;
ALTER TABLE rm_demand ADD COLUMN approval_at TIMESTAMP NULL;

-- 执行后核对：
-- SELECT requires_leader_approval,COUNT(*) FROM rm_demand_form GROUP BY requires_leader_approval;
-- SELECT approval_status,COUNT(*) FROM rm_demand GROUP BY approval_status;
-- SELECT COUNT(*) FROM rm_demand WHERE approval_status IS NULL;
