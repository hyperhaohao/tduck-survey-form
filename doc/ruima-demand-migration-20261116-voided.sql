-- 2026-11-16 收集单作废状态：已有实例执行一次；新实例由 ruima-demand-schema.sql 直接创建。
-- MySQL 不支持 ADD COLUMN IF NOT EXISTS，执行前先确认列不存在。
ALTER TABLE rm_demand_form ADD COLUMN voided BOOLEAN NOT NULL DEFAULT FALSE;
