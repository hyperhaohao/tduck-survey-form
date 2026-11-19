-- 2026-11-19 公示归档/删除状态。
-- 已有实例先备份，再按 迁移 → 后端 → 前端 的顺序部署；只执行一次。
ALTER TABLE rm_demand_publication
  ADD COLUMN archived_at TIMESTAMP NULL,
  ADD COLUMN archived_by BIGINT NULL,
  ADD COLUMN deleted_at TIMESTAMP NULL,
  ADD COLUMN deleted_by BIGINT NULL;

-- 核对：已归档数量、已删除数量。
-- SELECT SUM(archived_at IS NOT NULL) AS archived, SUM(deleted_at IS NOT NULL) AS deleted
-- FROM rm_demand_publication;
