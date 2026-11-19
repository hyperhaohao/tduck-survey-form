-- 2026-11-19 为存量需求收集单补「填报人部门」系统题。
-- 已由 ruima-demand-migration-20261119-demand-delete-dept-sort.sql 取代：
-- 部门题不再注入并会从存量表单删除；新环境不要执行本脚本。
-- 可重复执行；只新增 fm_user_form_item 数据，不修改表结构。
-- 已有实例先备份，再按 迁移 → 后端 → 前端 的顺序部署。
INSERT INTO fm_user_form_item
  (form_key, form_item_id, type, label, is_display_type, is_hide_type, is_special_type,
   show_label, default_value, required, placeholder, sort, span, scheme, reg_list)
SELECT c.form_key, 'demandDept', 'SELECT', '填报人部门', 0, 0, 0, 1, NULL, 1, '请选择',
       (SELECT COALESCE(MAX(i.sort), 0) + 1 FROM fm_user_form_item i WHERE i.form_key = c.form_key),
       24,
       '{"typeId":"SELECT","config":{"label":"填报人部门","showLabel":true,"labelWidth":null,"tag":"t-select","tagIcon":"select","mobileTag":"mobile-t-select","span":24,"required":true,"regList":[],"changeTag":true,"optionsType":0,"options":[],"demandDept":true,"formId":"demandDept"},"placeholder":"请选择","style":{"width":"100%"},"clearable":false,"disabled":false,"filterable":false,"multiple":true,"vModel":"demandDept","formItemId":"demandDept"}',
       '[]'
FROM rm_demand_form c
JOIN fm_user_form f ON f.form_key = c.form_key AND f.is_deleted = FALSE
WHERE NOT EXISTS (
  SELECT 1 FROM fm_user_form_item x
  WHERE x.form_key = c.form_key
    AND (x.form_item_id = 'demandDept'
         OR JSON_UNQUOTE(JSON_EXTRACT(x.scheme, '$.config.demandDept')) = 'true')
);

-- 核对每个收集单只有一条部门题。
-- SELECT form_key, COUNT(*) FROM fm_user_form_item
-- WHERE form_item_id = 'demandDept' GROUP BY form_key HAVING COUNT(*) > 1;
