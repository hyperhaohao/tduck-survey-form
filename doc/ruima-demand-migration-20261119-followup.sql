-- 2026-11-19 填报者追问：记录最近一次尚未答复的追问时间。
-- 已有实例先备份，再按 迁移 → 后端 → 前端 的顺序部署；只执行一次。
ALTER TABLE rm_demand ADD COLUMN followup_at TIMESTAMP NULL;

-- 核对：当前存在未答复追问的需求数量。
-- SELECT COUNT(*) AS followup_pending FROM rm_demand WHERE followup_at IS NOT NULL;
