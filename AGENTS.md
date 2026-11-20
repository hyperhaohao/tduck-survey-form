# 瑞玛问卷系统后端 Agent 工作约定

本仓库负责需求征集、经办处理、采纳实施、本人回复及公示的后端扩展。**接手先核对Git状态和实际需求，以服务端权限与原功能兼容为前提完成任务。** 本文件可供独立克隆本仓库的Agent使用，不依赖父目录或聊天记录。

## 阅读目录

- [仓库与阅读入口](#仓库与阅读入口)
- [业务与产品约定](#业务与产品约定)
- [GitHub与协作流程](#github与协作流程)
- [修改与安全边界](#修改与安全边界)
- [架构与修改入口](#架构与修改入口)
- [构建与验证](#构建与验证)
- [240部署边界](#240部署边界)
- [交付与接续](#交付与接续)

## 仓库与阅读入口

- 当前仓库：[hyperhaohao/tduck-survey-form](https://github.com/hyperhaohao/tduck-survey-form/tree/haoX/tduck-demand-loop)；配套前端：[hyperhaohao/tduck-front](https://github.com/hyperhaohao/tduck-front/tree/haoX/tduck-demand-loop)。
- origin：`https://github.com/hyperhaohao/tduck-survey-form.git`；upstream：`https://github.com/TDuckCloud/tduck-survey-form.git`。
- 开始先读[需求闭环说明](doc/瑞玛需求闭环.md)，涉及部署再读[240部署说明](deploy/test240/README.md)。仓库README的上游介绍仅供来源参考，当前项目约定以本文件和瑞玛说明为准。

## 业务与产品约定

- 中文名“瑞玛问卷系统”，英文名“RUIMA Survey”；保持Vue 2 / Element UI现有视觉风格。公司红色圆标用于页头和网站图标，维护登录页左侧使用问卷图表插画，不放大圆标。
- 默认登录填写；只有收集单显式允许时才可选匿名。匿名记录不关联登录账户，查询码只在领取时提供，不能出现在URL、日志和截图中。
- 填报者、经办人、单据管理员能力取并集。管理员分为两级：全局管理员（原系统超级管理员、`ruima.demand.admin-ids`）看全部收集单；后台管理员（`rm_demand_admin`）可进后台并建单，但只看本人创建或 `can_manage` 的收集单。仅后台管理员及以上可创建收集单，前端按能力显示入口，后端必须校验权限。经办人查看获授权总表，仅处理分派给自己的需求；单据管理员由 `rm_demand_acl.can_manage` 授予，创建人默认拥有且每个收集单至少保留一名，可查看本收集单全部未办结需求、分派经办、代填结果并发布或跳过公示。全局管理员对所有收集单拥有同等能力。
- “管理员授权”与全局配置仅全局管理员可操作；撤销后台管理员只移除 `rm_demand_admin`，不自动回收各收集单的 `can_manage`。原表单后台（我的项目）入口、原表单数据、报表、回收站等仍仅全局管理员可用；后台管理员通过“新建收集单/设计与发布”进入原表单设计器。表单级提交、回收、通知、分享、数据推送设置随该收集单的管理权限开放。
- 收集单可作废/恢复：作废后停止新填报并从员工入口隐藏，已有需求与处理记录保留；删除原表单仍按原回收站规则，不自动清理需求数据。单据管理员或全局管理员可删除单条需求：删除需求、消息、已读和公示，原答卷保留在原表单数据中。
- 收集单可设为“只收集”：不进入正式回复、采纳与公示；需求未读时计入待我处理，打开详情标记已读后移出待办，角标同步减少。详情隐藏全部处理区，仍可查看提交内容；`read` 与删除保留，回复/采纳/分派/追问/发布接口拒绝。
- 收集单首页可见范围跟随填报与成员权限：`LOGIN` 对所有登录用户可见可填；`SELECTED` 对 `can_fill`、`can_handle`、`can_manage` 任一成员可见，但只有 `can_fill` 能填报；非填报成员卡片显示“查看需求”，进入待我处理或获授权总表。
- 公示由全局管理员或该收集单的单据管理员审核后发布快照，公示前必须已有明确采纳结果（采纳/暂缓/不采纳），正式回复为选填，内部备注和草稿不能替代，私有处理进展不自动覆盖已发布内容；“不公示”记录 `publication_skipped` 并移出待办，之后可重新发布。本人数据、内部备注和附件均按实际权限隔离。登录填报者可在详情中追问本人非匿名需求，追问后需求重新进入经办人/单据管理员待办，只有新的正式回复才清除；匿名需求暂不支持在线追问。首页收集单由全局管理员拖动保存全局顺序，所有用户按该顺序看到；新建收集单默认最前，需求收集页不启用拖动。
- 保留首页可见收集单、我的需求、待我处理、需求公示、后台管理；保留侧栏收缩、手机抽屉和未读数量角标。
- 用户界面只显示“登录”；统一登录的技术配置仅出现在运维与代码说明中。

## GitHub与协作流程

- 开发分支固定为 `haoX/tduck-demand-loop`；`origin`为hyperhaohao的用户Fork，`upstream`为TDuckCloud原项目，只作为来源。
- 接手先检查分支、工作区和远端差异；保留他人修改，前后端分别提交、分别推送。只暂存本任务明确修改的文件。
- 不执行强制推送、硬重置、覆盖远端或自动合并主分支；不向上游发布。远端领先或有冲突时先分析合并范围，不能用本地版本覆盖。
- 推送及部署按当前会话的任务授权执行；本说明不授予其他系统的永久管理权限，也不要求对已经授权的步骤重复确认。
- 推送后读回远端分支和文件，交付仓库、分支、提交号及测试结果。提交成功、远端同步、部署成功分别报告。
- 无认证或网络不可用时完成可做的本地工作，明确报告未推送及原因；不能虚构GitHub成功。

```bash
git status --short
git branch --show-current
git remote -v
git fetch origin
git rev-list --left-right --count HEAD...origin/haoX/tduck-demand-loop
```

## 修改与安全边界

- 以最小必要修改完成任务，先读相关实现再编辑，保留原功能；避免无关重构、批量格式化、自动升级依赖或修改锁文件。
- 保留内部依赖名、Java包名、接口路径及开源许可证和版权来源；产品品牌替换不等于技术标识整体重命名。
- 原答卷为正文正本，扩展表保存处理信息；不通过测试脚本覆盖真实问卷内容、员工或权限。
- 密钥、密码、令牌、查询码和认证输入不进入Git、普通文档、聊天、日志、截图或临时明文文件；使用运行配置或环境注入，不输出完整配置或认证响应。
- 不提交node_modules、dist、target、测试认证状态、构建日志、私有配置或本机工具。真实环境测试需标识合成记录，按确切ID清理自己创建的数据。
- 文档使用中文、一个一级标题、最多三级标题、代码围栏注明语言；先写结论。规则和行为变更同步相关说明，历史实测保留日期。

## 架构与修改入口

- Java 8、Spring Boot 2.7.8、Maven多模块、MyBatis Plus/MySQL；当前运行实例使用EHCache，不因旧文档默认新增Redis。
- 扩展入口为 `tduck-api/src/main/java/com/tduck/cloud/api/demand/`：DemandForms收集单、DemandAccess权限、DemandScope列表与待办口径、DemandSubmission提交关联、DemandWorkflow处理、DemandExport导出、DemandGuard管理约束、DemandDepartments部门目录与成员名册、DemandFormFields填报人部门快照、DemandFileGuard附件保护、OidcController/OidcIdentityService登录。
- 只收集与成员可见性：`rm_demand_form.collect_only`；`DemandScope.pending` 分为普通未办结、只收集未读、追问，全局管理员跨 ACL 看待办；`DemandAccess.canFill` 是唯一填报闸门，`visible` 仅决定列表可见；`DemandWorkflow.requireWorkflow` 拒绝只收集的处理写操作，详情/列表返回 `collectOnly`。
- AI 建表与设计器协作在 `tduck-ai`：`AiController`（`/ai/status`、`/ai/gen-form-chat/stream`、旧 GET 兼容）、`AiConfiguration` 从 `sys_env_config.deepSeekEnvConfig` 构建普通/深入思考服务、`AiHistoryBuilder` 裁剪多轮历史并注入当前表单/方案、`AiStreamParser` 解析 `content`/`reasoning_content`。流式接口支持 `mode=create/revise`、`stage=clarify/plan/generate` 与 `planJson`；提示词为 `form-prompt-clarify.md`、`form-prompt-plan.md`、`form-prompt-deepseek.md`、`form-prompt-revise.md`。配置、模型、权限、配图与排障见 `doc/AI建表与协作说明.md`；Nginx 的 `/ai/` location 必须关闭缓冲并放宽读超时。
- `/demand-api/*`为业务接口，`/demand/*`为前端页面；原提交接口与需求索引应保持同一事务，处理与分派保留版本校验、行锁及冲突拒绝。批量操作由前端逐条调用单条接口，不新增服务端批量事务。
- 新增表定义见 `doc/ruima-demand-schema.sql`；仅首次初始化执行一次，后续使用增量迁移。单据管理员与不公示升级使用 `doc/migration-20261114-form-manager.sql`，只执行一次。部门快照、收集单排序与部门题移除使用 `doc/ruima-demand-migration-20261119-demand-delete-dept-sort.sql`，只执行一次；旧的 `doc/migration-20261119-dept-field.sql` 已被该迁移取代，新环境不再执行。只收集模式使用 `doc/ruima-demand-migration-20261120-collect-only.sql`，只新增 `rm_demand_form.collect_only`。公示归档/删除状态使用 `doc/ruima-demand-migration-20261119-publication-state.sql`，只新增 `rm_demand_publication` 列。不得向已有业务库重导会删表的官方初始化SQL，不重复执行独立索引创建语句。
- 功能开关为 `ruima.demand.enabled`。存在私有数据时不能通过直接关开关并开放旧静态附件路径回退。
- 登录保留授权码、PKCE、签名、issuer、audience、nonce、组织及禁用状态校验，使用issuer与subject映射身份，不通过邮箱/姓名合并或信任上游声明直接授予管理员。
- 私有附件通过需求授权下载接口读取，保留旧静态路径绕过防护。新接口不能只依靠前端菜单隐藏保护。

## 构建与验证

在本仓库根目录运行，先确认 `java -version` 与 `mvn -version` 使用Java 8。完整本地工作区的父目录.tools可能有JDK和Maven；独立克隆时自行配置等效工具，不依赖该目录。

```bash
mvn -B -pl tduck-api -am test
mvn -B -pl tduck-api -am package
```

- 产物：`tduck-api/target/tduck-api.jar`。外部配置示例为 `tduck-api/src/main/resources/application-demand.example.yml`；真实运行配置必须留在忽略文件或服务器受限目录。
- DemandWorkflowTest和OidcIdentityServiceTest使用隔离H2及合成登录服务。按变更覆盖越权、匿名、经办结果、备注隔离、公示快照、角标、冲突及附件；本轮另覆盖需求删除级联、部门快照与旧键过滤、采纳结果拒绝待评估、无正式回复可公示、全局排序与新建收集单置顶；本轮新增只收集待办已读清零、拒绝处理写操作、详情 flags、配置/列表、SELECTED 成员可见与填报闸门、全局管理员待办。只运行必要检查，不使用真实库当单元测试库。
- 2026-11-14 单据管理员、导出与批量改造：后端25项隔离测试通过；已部署240并完成数据库增量迁移，线上接口冒烟通过，合成数据已清理；尚未推送 GitHub。历史基线：2026-11-13后端18项隔离测试、240真实API16项及员工本人登录通过；2026-11-14品牌页面另有10项隔离和10项真实浏览器检查。不是本次文档修改重新执行的结果。
- 2026-11-14 公示前置正式回复：手工发布与自动发布入口统一要求已有 `REPLY`，新增1项隔离测试；`mvn -B -pl tduck-api -am test` 26项通过（DemandWorkflowTest 22、OidcIdentityServiceTest 4）。尚未部署、尚未推送。
- 2026-11-14 后台管理员分级：`rm_demand_admin` 仅获后台入口与建单能力，后台列表/总表/汇总/导出按本人创建或 `can_manage` 过滤；`/admin/roles` 仍仅全局管理员，原表单数据/报表/回收站仍仅全局管理员。后端 29 项隔离测试、240真实浏览器4项通过（DemandWorkflowTest 25、OidcIdentityServiceTest 4）；已部署240（应用镜像 `sha256:b6bf2581c2e19a6b398a0deccea16ee2a2a9b9379d545221c490dc73fd9c1059`），无数据库变更；尚未推送 GitHub。
- 2026-11-15 AI 建表修复与设计器协作：修复无配置时 NPE 与静默失败，模型默认迁移到 `deepseek-v4-flash`/`deepseek-v4-pro`，新增状态接口、POST 流式会话、深入思考、历史裁剪、错误分类与管理员权限；后端 51 项隔离测试通过。已部署 240（应用镜像 `sha256:8c59736095ae1f4b7a3a7719a408fa69c1cfab0f0fecf8fe62b5639e2d05305e`，Nginx 新增 `/ai/` 无缓冲 location），配置前验证通过；尚未推送 GitHub。
- 2026-11-15 方案模式与右侧平铺补充：流式接口新增 `stage=clarify/plan/generate` 与 `planJson`，新增澄清/方案提示词；设计器改为右侧可拖拽平铺。后端 52 项隔离测试通过（原有 29 + AI 23）。已再次部署 240（应用镜像 `sha256:023c3c601808a14d0be8e58e8723ca228b60ec24dc1f1ca53b9413279ace9b08`，JAR `b3c132ce…`），1024×768 真实浏览器核对平铺、拖拽、方案模式与配置页通过；真实 Key 联调通过（测试连接 453ms、创建流程 5 题、设计器合成表单方案/生成/应用成功且未改题目保留 ID、合成数据清零）；网关无独立 Pro/Reasoner，深入思考同模型并展示 `reasoning_content`；方案卡滚动修复静态 `index.html` `4fd3cbca…`。尚未推送 GitHub。详见 `doc/AI建表与协作说明.md`。
- 2026-11-15 最高管理员绑定：仅向 240 的 `rm_demand_identity` 插入 Casdoor `ruima-admin`（`f3ce4605-…`）→ 本地 `ac_user.id=1` 一条映射，未改代码、未重建镜像、未重启容器；插入 1 行并复核，其余身份映射不变。真实登录后的管理员能力待用户核验，尚未推送 GitHub。
- 2026-11-16 ruima-admin 密码重置：经 Casdoor 管理接口更新 Casdoor 库中密码（bcrypt），旧密码校验通过；新密码明文仅存 240 的 `secrets/ruima-admin-account.json`（root 0600）。该账号亦用于 OpenViking，其他系统需同步更新。尚未推送 GitHub。
- 2026-11-16 收集单作废与表单级通知权限：`DemandGuard` 放行后台管理员的 `/user/form/wx/`；新增 `rm_demand_form.voided` 与 `/demand-api/admin/forms/{key}/void`，作废后停止新填报、从员工入口隐藏、已有需求保留且可恢复。后端 53 项、前端 34 项回归通过；已部署 240（应用镜像 `sha256:734380e3…`，JAR `9ff0256d…`，最终 `index.html` `5ec77ce0…`），1024×768 真实浏览器核对作废/已作废/恢复通过，合成数据清零，AI 状态仍已就绪；首次部署因验收清理脚本表名错误自动回滚，修正后重试成功。尚未推送 GitHub。
- 2026-11-16 超级管理员删除与全局回收站：新增 `/demand-api/admin/forms/deleted`、`/{key}/delete`、`/{key}/restore`、`/{key}/purge`，删除为逻辑删除并保留需求、答卷与附件；彻底删除仅限零需求表单；`rm_demand_form` 新增 `voided_by/voided_at/deleted_by/deleted_at`。后端 54 项、前端 36 项回归通过；已部署 240（应用镜像 `sha256:13a0f3c6…`，JAR `5f86c82e…`，`index.html` `7190d6b8…`），真实接口与 1024×768 浏览器删除/恢复/彻底删除验证通过，合成数据清零；尚未推送 GitHub。
- 2026-11-16 237 新环境部署：在 `ai-server` 新建 `ruima-survey` Compose（app `127.0.0.1:18001`、MySQL 内部、Nginx `18000`、UFW 放行 18000），迁移 240 的 6 个表单、2 条需求、3 份答卷与 AI 配置；Casdoor 新建 `app-ruima-survey-237`（公网 `http://wanghao.yun:93` + 内网回调）；内网真实 Casdoor 登录、AI 测试 743ms、删除/回收站合成验证通过，其他 8 个 Compose 项目未重启；外网 `http://wanghao.yun:93` 登录与真实表单提交验证通过，240 保留；已全量同步 Casdoor 104 个用户（100 新建、4 已有、104 身份映射），成员选择器 104；同步脚本已上传，日更 cron 已安装（19:17 UTC）。尚未推送 GitHub。详见 `deploy/237/README.md`。
- 2026-11-18 部门成员名册：新增只读 `GET /demand-api/admin/departments/members`，按有效部门返回 `{userId,name,deptKey}`，未归属或仅归属停用部门的成员返回一条 `deptKey=null`；后端 67 项隔离测试、前端 44 项回归通过；已前后端一起部署 237（应用镜像 `sha256:f295d41f…`，JAR `0e96521f…`，旧 JAR 备份 `application.jar.20261117`，旧镜像标签 `ruima/survey:20261118-pre-tree`，静态 `index.html` `2e37daf8…`），新接口未登录返回 401、虚构路径 404，其他容器未重启；尚未推送 GitHub。
- 2026-11-18 组织架构与填报人部门：新增部门/成员 CRUD、`PUT /admin/users/{userId}/departments`、`POST /admin/forms/{key}/dept-field`；`DemandFormFields` 在填写详情注入部门题并锁定，提交前用服务端部门覆盖；后端 69 项、前端 48 项回归通过；已部署 237（应用镜像 `sha256:8cbcef3a…`，JAR `dfcebb9a…`，旧 JAR 备份 `application.jar.20261118-pre-org`，旧镜像 `ruima/survey:20261118-pre-org`，静态 `index.html` `93b6b12f…`），无数据库结构变更，其他容器未重启；尚未推送 GitHub。
- 2026-11-19 需求详情标签与部门题补题：详情按表单字段顺序显示中文标签，选择类字段只显示 `xxxlabel` 显示值；`DemandFormFields.persistDepartmentField` 在首次填写与新建收集单时落库补题，`DemandWorkflow.detail` 对未落库题目补充标签；新增 `doc/migration-20261119-dept-field.sql` 回填存量收集单。后端 69 项、前端 49 项回归通过；已部署 237（应用镜像 `sha256:7b40d52c…`，JAR `662c700c…`，静态 151 个文件，`index.html` `d6b7032a…`，备份 `deptfix-20261119-pre`），回填仅 `D5zmF3Hq`，4 个活跃收集单各 1 条部门题，详情与 `/s/D5zmF3Hq` 1024×768 真实浏览器核对通过；尚未推送 GitHub。
- 2026-11-19 公示归档/删除与测试表单清理：`rm_demand_publication` 新增 `archived_at/archived_by/deleted_at/deleted_by`；新增 `POST /demand-api/publications/{id}/archive|delete`，仅 `requireManage`（单据管理员或全局管理员）；公示列表/详情/未读/导出排除已归档、已删除和已删除收集单；`DemandScope.pending` 仅把未删除的已发布公示视为已公示。后端 70 项、前端 52 项回归通过；已部署 237（应用镜像 `sha256:00e65ed0…`，JAR `86642248…`，静态 151 个文件，`index.html` `82aeccba…`，备份 `publication-state-20261119-pre`），迁移已执行，公示列表与未读为 0，归档/删除路由冒烟 404，1440/1024 真实浏览器列宽与无溢出核对通过；同日按精确 ID 彻底删除 `EpA5AG0f`「需求征集（测试）」的 2 条需求、2 份答卷、2 条消息、2 条已读、2 条 ACL 和 1 条公示，其他收集单计数不变，备份 `testform-purge-20261119-pre`（`42a58e40…`），回收站不再显示该表单；尚未推送 GitHub。
- 2026-11-19 填报者追问：`rm_demand` 新增 `followup_at`，新增 `POST /demand-api/requests/{id}/followup`（仅登录的填报者本人、非匿名、收集单未删除）；写入 `FOLLOWUP` 消息，`DemandScope.pending` 增加“未答复追问”分支（不受办结/公示/跳过限制），只有 `REPLY` 清除 `followup_at`，`NOTE`/`EVENT`/`DRAFT` 不清除；`summary` 的 pending 列按 scope 区分。后端 75 项、前端 57 项回归通过；已部署 237（应用镜像 `sha256:9bd25ac3…`，JAR `1f2046cb…`，静态 151 个文件，`index.html` `8c287709…`，备份 `followup-20261119-pre`），迁移已执行，真实接口与 1024/1440 浏览器追问/回复/空状态/按钮对齐通过，合成数据已清理；尚未推送 GitHub。
- 2026-11-19 需求删除、部门快照、结果必填与全局排序：新增 `POST /requests/{id}/delete`（清理消息、已读、公示，原答卷保留）；移除填报人部门题目并改为 `rm_demand.department` 提交快照，详情固定显示；`outcome` 拒绝待评估，采纳结果/说明及条件进度、完成说明显示必填星号，正式回复改为选填，公示前置由“正式回复”改为“明确采纳结果”；`rm_demand_form.sort_no` 与 `POST /admin/forms/sort` 支持全局管理员首页全局排序，新建收集单默认最前。后端 79 项（DemandWorkflowTest 52）、前端 60 项回归通过，生产构建通过；已部署 237（迁移已执行，JAR `bd46db6e…`，镜像 `sha256:fa76fbce…`，静态 152 个文件，`index.html` `38600e88…`，备份 `demand-state-20261120-pre`），真实浏览器验收通过，合成数据已清理；已推送 GitHub。
- 2026-11-20 只收集与成员可见性：新增 `rm_demand_form.collect_only` 与 `doc/ruima-demand-migration-20261120-collect-only.sql`；`DemandScope.pending` 增加只收集未读与全局管理员分支，`DemandAccess` 拆分 `canFill`/`visible`，`DemandWorkflow` 拒绝只收集处理写操作并返回 `collectOnly`；前端后台开关、首页标签/查看需求、待办已读重载、详情隐藏全部处理区。后端 85 项（DemandWorkflowTest 58、Oidc 4）、前端 65 项回归通过，生产构建通过；JAR `e6a71739…`，`index.html` `811532ca…`，152 个静态文件；已推送 GitHub，尚未部署 237。
- 尚未完成整栈异机恢复、异机备份、多人并发压测及业务试点完整验收；不能据此宣称已正式投产。

## 240部署边界

- 测试入口 `http://192.168.1.240:18000`；原15000实例和其他应用保持。应用仅回环18001，MySQL不发布主机端口。
- Compose及受限报告位于 `/data/apps/ruima-survey-test/`；静态页面位于 `/www/wwwroot/ruima-survey-test`；持久化数据位于 `/data/appdata/ruima-survey-test/{mysql,upload,logs}`。
- 外部配置位于服务器受限secrets目录，只通过已配置且获授权的运维连接访问必要项，不将其复制到普通目录或Git。独立登录应用为 `admin/app-ruima-survey`，组织ruima。
- 浏览器授权入口为 `http://wanghao.yun:9999/login/oauth/authorize`，令牌issuer为 `http://192.168.1.237:13004`，回调为 `http://192.168.1.240:18000/auth/oidc/callback`。不能混用浏览器入口和内部签发地址，不能复用其他应用密钥。
- 最高管理员：统一登录环境下，Casdoor 组织 `ruima` 的 `ruima-admin` 已通过 `rm_demand_identity` 映射到本地 `ac_user.id=1`（2026-11-15）；不新增代码路径、不采信 Casdoor 角色声明。回退为删除该映射行，无需重启应用。
- 部署前重新核对当前服务、挂载、授权和回退材料；只处置本次目标。静态资源先更新资源文件、后更新index.html；核对传输前后SHA256，不能用校验不一致的包部署。已有库先备份并执行增量迁移，再部署新后端和前端；回退时先前端后后端，不删除新增列。
- 验收后清理本次合成数据，核对既有服务基线，记录备份、保留依据、剩余风险及实际验证范围。异机备份、整栈恢复和业务验收不能由构建通过代替。

### 237新环境

- 内网入口 `http://192.168.1.237:18000`；外网计划 `http://wanghao.yun:93`，路由器公网端口 93 映射到 `192.168.1.237:18000`（已映射并验证）；已全量同步 Casdoor 104 个用户，成员选择器可用；每日同步 cron 已安装（19:17 UTC）。
- 项目 `/data/apps/ruima-survey`，静态 `/www/wwwroot/ruima-survey`，数据 `/data/appdata/ruima-survey`；Nginx vhost `ruima-survey.conf`，UFW 放行 18000；Casdoor 应用 `admin/app-ruima-survey-237`。
- 240 测试实例保留；237 只新增 `ruima-survey` Compose，其他 8 个 Compose 项目、Nginx 其他 vhost 和数据库均不改动。详见 `deploy/237/README.md`。

## 交付与接续

- 先说明完成了什么，再列修改、实际运行的检查及结果、未验证项、提交号和部署状态。
- 本文与关联说明中的日期、测试数量和环境状态是历史证据；接手时重新检查源码与环境，不保证未来仍保持相同数量或运行状态。
- 修改业务规则、构建命令、品牌或部署方式后，同步本文件及相应运行说明。CLAUDE.md只维护入口，不复制另一套项目规则。
