-- 瑞玛需求模块 2026-11-27 增量迁移：历史轮次数据回填（可重复执行）。
-- 用于 round-model 之后补：历史单轮 round_end_time 迁到整单截止，并补齐轮次起点/轮次号。
-- 部署顺序仍为迁移、后端、前端；执行前建议完成数据库备份。

-- 1) 历史单轮只写了 round_end_time 的，升级后仍按整单截止生效。
UPDATE rm_demand_form
SET end_time = COALESCE(end_time, round_end_time),
    round_end_time = NULL
WHERE round_mode = 'SINGLE';

-- 2) 不启用轮次（内部 SINGLE）需要有效轮次起点，必填统计与提醒才能按轮工作。
UPDATE rm_demand_form
SET round_no = CASE WHEN round_no IS NULL OR round_no <= 0 THEN 1 ELSE round_no END,
    round_start_time = COALESCE(round_start_time, CURRENT_TIMESTAMP)
WHERE round_mode = 'SINGLE';

-- 执行后核对（以下应为 0）：
-- SELECT COUNT(*) FROM rm_demand_form
--  WHERE round_mode='SINGLE' AND (round_start_time IS NULL OR round_no<=0);
