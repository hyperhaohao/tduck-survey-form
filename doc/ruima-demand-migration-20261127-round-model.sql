-- 瑞玛需求模块 2026-11-27 增量迁移：不启用轮次（隐式单轮）与周期锚点。
-- 仅执行一次；执行前停止 app 容器并完成数据库备份。部署顺序：迁移、后端、前端。

ALTER TABLE rm_demand_form ADD COLUMN round_day INT NULL;

ALTER TABLE rm_demand_form MODIFY COLUMN round_mode VARCHAR(16) NOT NULL DEFAULT 'SINGLE';

-- 历史「不启用轮次」统一为隐式单轮：整单截止优先，原轮次截止回填整单截止后清空。
UPDATE rm_demand_form
SET end_time = COALESCE(end_time, round_end_time)
WHERE round_mode = 'NONE';

UPDATE rm_demand_form
SET round_mode = 'SINGLE', round_end_time = NULL
WHERE round_mode = 'NONE';

-- 新环境：round_mode 默认 SINGLE；round_day 正数从周期开始，负数从周期结束倒数。
-- 历史 MONTH/QUARTER 周期保留，round_day 为空时沿用原按月/季推进算法。
