-- 瑞玛需求模块 2026-11-19 增量迁移：部门快照、收集单全局排序与部门题移除。
-- 仅执行一次；执行前停止 app 容器并完成数据库与静态备份。

ALTER TABLE rm_demand ADD COLUMN department TEXT NULL;
ALTER TABLE rm_demand_form ADD COLUMN sort_no INT NOT NULL DEFAULT 0;

-- 存量需求部门快照：匿名固定 ["无"]；其余优先 demandDeptlabel，其次 demandDept。
UPDATE rm_demand d
JOIN fm_user_form_data x ON x.id = CAST(d.data_id AS UNSIGNED)
SET d.department = CASE
  WHEN d.anonymous = TRUE THEN '["无"]'
  WHEN JSON_VALID(x.original_data) = 1
   AND JSON_TYPE(JSON_EXTRACT(x.original_data, '$.demandDeptlabel')) = 'ARRAY'
    THEN CAST(JSON_EXTRACT(x.original_data, '$.demandDeptlabel') AS CHAR)
  WHEN JSON_VALID(x.original_data) = 1
   AND JSON_TYPE(JSON_EXTRACT(x.original_data, '$.demandDept')) = 'ARRAY'
    THEN CAST(JSON_EXTRACT(x.original_data, '$.demandDept') AS CHAR)
  WHEN JSON_VALID(x.original_data) = 1
   AND JSON_TYPE(JSON_EXTRACT(x.original_data, '$.demandDeptlabel')) = 'STRING'
    THEN JSON_ARRAY(JSON_UNQUOTE(JSON_EXTRACT(x.original_data, '$.demandDeptlabel')))
  WHEN JSON_VALID(x.original_data) = 1
   AND JSON_TYPE(JSON_EXTRACT(x.original_data, '$.demandDept')) = 'STRING'
    THEN JSON_ARRAY(JSON_UNQUOTE(JSON_EXTRACT(x.original_data, '$.demandDept')))
  ELSE NULL
END
WHERE d.department IS NULL;

-- 存量收集单顺序按原表单创建时间倒序初始化（最新为 1）。
UPDATE rm_demand_form c
JOIN (
  SELECT form_key,
         ROW_NUMBER() OVER (ORDER BY create_time DESC, form_key) AS rn
  FROM fm_user_form
) x ON x.form_key = c.form_key
SET c.sort_no = x.rn;

-- 删除需求收集单遗留的「填报人部门」系统题。
DELETE FROM fm_user_form_item
WHERE form_key IN (SELECT form_key FROM rm_demand_form)
  AND (
    form_item_id = 'demandDept'
    OR (
      JSON_VALID(scheme) = 1
      AND JSON_UNQUOTE(JSON_EXTRACT(scheme, '$.config.demandDept')) = 'true'
    )
  );

-- 执行后核对：
-- SELECT COUNT(*) FROM rm_demand WHERE department IS NULL;
-- SELECT id,request_no FROM rm_demand WHERE department IS NULL LIMIT 20;
-- SELECT COUNT(*) FROM fm_user_form_item
--  WHERE form_key IN (SELECT form_key FROM rm_demand_form)
--    AND (form_item_id='demandDept'
--         OR (JSON_VALID(scheme)=1
--             AND JSON_UNQUOTE(JSON_EXTRACT(scheme,'$.config.demandDept'))='true'));
-- SELECT sort_no,COUNT(*) FROM rm_demand_form GROUP BY sort_no HAVING COUNT(*)>1;
-- SELECT c.form_key FROM rm_demand_form c
--  LEFT JOIN fm_user_form f ON f.form_key=c.form_key WHERE f.form_key IS NULL;
