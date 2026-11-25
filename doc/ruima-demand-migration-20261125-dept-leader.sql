-- 2026-11-25 部门主管标识：每部门一名主管，当前仅用于展示与后续审批流程。
-- 已有库执行一次；执行前备份。新建库已包含在 ruima-demand-schema.sql 中。
ALTER TABLE rm_demand_dept ADD COLUMN leader_id BIGINT NULL;

-- 核对：列已存在且历史部门默认 NULL。
SELECT dept_key, name, leader_id FROM rm_demand_dept ORDER BY sort_no, name;
