-- 瑞玛需求模块 2026-11-26 增量迁移：部门主管多选、三模式审批与逐级审批链。
-- 仅执行一次；执行前停止 app 容器并完成数据库备份。部署顺序：迁移、后端、前端。

CREATE TABLE IF NOT EXISTS rm_demand_dept_leader (
  dept_key VARCHAR(64) NOT NULL,
  user_id BIGINT NOT NULL,
  sort_no INT NOT NULL DEFAULT 0,
  created_by BIGINT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY(dept_key, user_id)
);
CREATE INDEX idx_rm_dept_leader_user ON rm_demand_dept_leader(user_id);

INSERT INTO rm_demand_dept_leader(dept_key,user_id,sort_no)
SELECT d.dept_key,d.leader_id,0 FROM rm_demand_dept d
WHERE d.leader_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM rm_demand_dept_leader l
                  WHERE l.dept_key=d.dept_key AND l.user_id=d.leader_id);

ALTER TABLE rm_demand_form ADD COLUMN approval_mode VARCHAR(16) NOT NULL DEFAULT 'NONE';
UPDATE rm_demand_form SET approval_mode='DIRECT'
 WHERE requires_leader_approval=TRUE AND approval_mode='NONE';

ALTER TABLE rm_demand ADD COLUMN approval_mode VARCHAR(16) NOT NULL DEFAULT 'NONE';
ALTER TABLE rm_demand ADD COLUMN approval_step INT NOT NULL DEFAULT 0;

CREATE TABLE IF NOT EXISTS rm_demand_approval_step (
  id VARCHAR(36) PRIMARY KEY,
  demand_id VARCHAR(36) NOT NULL,
  step_no INT NOT NULL,
  dept_key VARCHAR(64) NULL,
  dept_name VARCHAR(512) NULL,
  status VARCHAR(16) NOT NULL DEFAULT 'WAITING',
  comment_text VARCHAR(2000) NULL,
  acted_by BIGINT NULL,
  acted_at TIMESTAMP NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE(demand_id, step_no)
);
CREATE INDEX idx_rm_approval_step ON rm_demand_approval_step(demand_id, status);

CREATE TABLE IF NOT EXISTS rm_demand_approval_approver (
  step_id VARCHAR(36) NOT NULL,
  user_id BIGINT NOT NULL,
  user_name VARCHAR(100) NOT NULL,
  PRIMARY KEY(step_id, user_id)
);
CREATE INDEX idx_rm_approval_approver_user ON rm_demand_approval_approver(user_id);

ALTER TABLE rm_demand_notice ADD COLUMN step_no INT NULL;

-- 历史在途单级审批回填为一步审批（当前环境预期为 0 行）。
INSERT INTO rm_demand_approval_step(id,demand_id,step_no,dept_key,dept_name,status,created_at)
SELECT CONCAT('L',REPLACE(d.id,'-','')),d.id,1,NULL,'历史审批','PENDING',CURRENT_TIMESTAMP
FROM rm_demand d
WHERE d.approval_status='PENDING' AND d.approval_leader_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM rm_demand_approval_step s WHERE s.demand_id=d.id);
INSERT INTO rm_demand_approval_approver(step_id,user_id,user_name)
SELECT CONCAT('L',REPLACE(d.id,'-','')),d.approval_leader_id,COALESCE(u.name,'用户')
FROM rm_demand d
LEFT JOIN ac_user u ON u.id=d.approval_leader_id
WHERE d.approval_status='PENDING' AND d.approval_leader_id IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM rm_demand_approval_approver a
                  WHERE a.step_id=CONCAT('L',REPLACE(d.id,'-','')) AND a.user_id=d.approval_leader_id);
UPDATE rm_demand SET approval_step=1,approval_mode='DIRECT'
 WHERE approval_status='PENDING' AND approval_leader_id IS NOT NULL AND approval_step=0;
UPDATE rm_demand SET approval_mode='DIRECT'
 WHERE approval_status IN ('APPROVED','REJECTED') AND approval_leader_id IS NOT NULL
   AND approval_mode='NONE';

-- 执行后核对：
-- SELECT COUNT(*) FROM rm_demand_dept_leader;
-- SELECT approval_mode,COUNT(*) FROM rm_demand_form GROUP BY approval_mode;
-- SELECT approval_status,COUNT(*) FROM rm_demand GROUP BY approval_status;
-- SELECT COUNT(*) FROM rm_demand_approval_step;
-- SELECT COUNT(*) FROM rm_demand_approval_approver;
-- SELECT COUNT(*) FROM rm_demand_notice WHERE step_no IS NOT NULL;
