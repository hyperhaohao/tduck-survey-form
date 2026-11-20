-- 瑞玛需求模块 2026-11-20 增量迁移：收集单“只收集”模式。
-- 仅执行一次；执行前停止 app 容器并完成数据库备份。

ALTER TABLE rm_demand_form ADD COLUMN collect_only BOOLEAN NOT NULL DEFAULT FALSE;

-- 执行后核对：
-- SELECT collect_only,COUNT(*) FROM rm_demand_form GROUP BY collect_only;
-- SELECT COUNT(*) FROM rm_demand_form WHERE collect_only IS NULL;
-- SELECT COUNT(*) FROM rm_demand_form;
