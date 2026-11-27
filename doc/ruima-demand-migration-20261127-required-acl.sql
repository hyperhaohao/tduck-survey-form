-- 瑞玛需求模块 2026-11-27 增量迁移：必填人员统一到成员/部门权限。
-- 仅执行一次；执行前停止 app 容器并完成数据库备份。部署顺序：迁移、后端、前端。
-- rm_demand_required 保留一个兼容窗口用于旧后端回滚，新后端以 ACL.required 为准并继续双写旧表。

ALTER TABLE rm_demand_acl ADD COLUMN required BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE rm_demand_acl_dept ADD COLUMN required BOOLEAN NOT NULL DEFAULT FALSE;

-- 旧表由服务器默认排序规则创建（常见 utf8mb4_0900_ai_ci），统一到 ACL 的排序规则，
-- 避免 JOIN 时 Illegal mix of collations。
ALTER TABLE rm_demand_required CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- 1) 已有用户授权行标必填，并同步可填报。数值比较兼容前导零。
UPDATE rm_demand_acl a
JOIN rm_demand_form c ON c.form_key = a.form_key
JOIN rm_demand_required r
  ON r.form_key = a.form_key
 AND r.subject_type = 'USER'
 AND r.subject_id REGEXP '^[0-9]+$'
 AND CAST(r.subject_id AS UNSIGNED) = a.user_id
SET a.required = TRUE,
    a.can_fill = TRUE;

-- 2) 仅必填、尚无 ACL 的用户补授权行。
INSERT INTO rm_demand_acl(form_key,user_id,can_fill,can_handle,can_manage,required)
SELECT r.form_key, CAST(r.subject_id AS UNSIGNED),
       TRUE,
       FALSE, FALSE, TRUE
FROM rm_demand_required r
JOIN rm_demand_form c ON c.form_key = r.form_key
JOIN ac_user u ON u.id = CAST(r.subject_id AS UNSIGNED) AND u.deleted = FALSE
WHERE r.subject_type = 'USER'
  AND r.subject_id REGEXP '^[0-9]+$'
  AND NOT EXISTS (
    SELECT 1 FROM rm_demand_acl a
    WHERE a.form_key = r.form_key AND a.user_id = CAST(r.subject_id AS UNSIGNED)
  );

-- 3) 已有部门授权行标必填，并同步可填报。
UPDATE rm_demand_acl_dept ad
JOIN rm_demand_form c ON c.form_key = ad.form_key
JOIN rm_demand_required r
  ON r.form_key = ad.form_key
 AND r.subject_type = 'DEPT'
 AND r.subject_id = ad.dept_key
SET ad.required = TRUE,
    ad.can_fill = TRUE;

-- 4) 仅必填、尚无 ACL 的部门补授权行。
INSERT INTO rm_demand_acl_dept(form_key,dept_key,can_fill,can_handle,can_manage,created_by,required)
SELECT r.form_key, r.subject_id,
       TRUE,
       FALSE, FALSE, r.created_by, TRUE
FROM rm_demand_required r
JOIN rm_demand_form c ON c.form_key = r.form_key
JOIN rm_demand_dept d ON d.dept_key = r.subject_id AND d.active = TRUE
WHERE r.subject_type = 'DEPT'
  AND NOT EXISTS (
    SELECT 1 FROM rm_demand_acl_dept ad
    WHERE ad.form_key = r.form_key AND ad.dept_key = r.subject_id
  );

-- 执行后核对（停用用户/部门除外，以下应尽量为 0）：
-- SELECT COUNT(*) FROM rm_demand_required r
--  WHERE r.subject_type='USER' AND r.subject_id REGEXP '^[0-9]+$'
--    AND EXISTS (SELECT 1 FROM ac_user u WHERE u.id=CAST(r.subject_id AS UNSIGNED) AND u.deleted=FALSE)
--    AND NOT EXISTS (SELECT 1 FROM rm_demand_acl a
--                    WHERE a.form_key=r.form_key AND a.user_id=CAST(r.subject_id AS UNSIGNED) AND a.required=TRUE);
-- SELECT COUNT(*) FROM rm_demand_required r
--  WHERE r.subject_type='DEPT'
--    AND EXISTS (SELECT 1 FROM rm_demand_dept d WHERE d.dept_key=r.subject_id AND d.active=TRUE)
--    AND NOT EXISTS (SELECT 1 FROM rm_demand_acl_dept ad
--                    WHERE ad.form_key=r.form_key AND ad.dept_key=r.subject_id AND ad.required=TRUE);
-- SELECT COUNT(*) FROM rm_demand_acl a JOIN rm_demand_form c ON c.form_key=a.form_key
--  WHERE c.visibility='SELECTED' AND a.required=TRUE AND a.can_fill=FALSE;
-- SELECT COUNT(*) FROM rm_demand_acl_dept ad JOIN rm_demand_form c ON c.form_key=ad.form_key
--  WHERE c.visibility='SELECTED' AND ad.required=TRUE AND ad.can_fill=FALSE;
