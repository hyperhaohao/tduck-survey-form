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
- “管理员授权”与全局配置仅全局管理员可操作；撤销后台管理员只移除 `rm_demand_admin`，不自动回收各收集单的 `can_manage`。原表单数据、报表、回收站等入口仍仅全局管理员可用。
- 公示由全局管理员或该收集单的单据管理员审核后发布快照，公示前必须已有面向填报者的正式回复，内部备注和草稿不能替代，私有处理进展不自动覆盖已发布内容；“不公示”记录 `publication_skipped` 并移出待办，之后可重新发布。本人数据、内部备注和附件均按实际权限隔离。
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
- 扩展入口为 `tduck-api/src/main/java/com/tduck/cloud/api/demand/`：DemandForms收集单、DemandAccess权限、DemandScope列表与待办口径、DemandSubmission提交关联、DemandWorkflow处理、DemandExport导出、DemandGuard管理约束、DemandFileGuard附件保护、OidcController/OidcIdentityService登录。
- AI 建表与设计器协作在 `tduck-ai`：`AiController`（`/ai/status`、`/ai/gen-form-chat/stream`、旧 GET 兼容）、`AiConfiguration` 从 `sys_env_config.deepSeekEnvConfig` 构建普通/深入思考服务、`AiHistoryBuilder` 裁剪多轮历史并注入当前表单/方案、`AiStreamParser` 解析 `content`/`reasoning_content`。流式接口支持 `mode=create/revise`、`stage=clarify/plan/generate` 与 `planJson`；提示词为 `form-prompt-clarify.md`、`form-prompt-plan.md`、`form-prompt-deepseek.md`、`form-prompt-revise.md`。配置、模型、权限、配图与排障见 `doc/AI建表与协作说明.md`；Nginx 的 `/ai/` location 必须关闭缓冲并放宽读超时。
- `/demand-api/*`为业务接口，`/demand/*`为前端页面；原提交接口与需求索引应保持同一事务，处理与分派保留版本校验、行锁及冲突拒绝。批量操作由前端逐条调用单条接口，不新增服务端批量事务。
- 新增表定义见 `doc/ruima-demand-schema.sql`；仅首次初始化执行一次，后续使用增量迁移。单据管理员与不公示升级使用 `doc/migration-20261114-form-manager.sql`，只执行一次。不得向已有业务库重导会删表的官方初始化SQL，不重复执行独立索引创建语句。
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
- DemandWorkflowTest和OidcIdentityServiceTest使用隔离H2及合成登录服务。按变更覆盖越权、匿名、经办结果、备注隔离、公示快照、角标、冲突及附件；本轮另覆盖创建人默认管理员、最后一名管理员保护、经理待办口径、跳过/自动公示与导出内容。只运行必要检查，不使用真实库当单元测试库。
- 2026-11-14 单据管理员、导出与批量改造：后端25项隔离测试通过；已部署240并完成数据库增量迁移，线上接口冒烟通过，合成数据已清理；尚未推送 GitHub。历史基线：2026-11-13后端18项隔离测试、240真实API16项及员工本人登录通过；2026-11-14品牌页面另有10项隔离和10项真实浏览器检查。不是本次文档修改重新执行的结果。
- 2026-11-14 公示前置正式回复：手工发布与自动发布入口统一要求已有 `REPLY`，新增1项隔离测试；`mvn -B -pl tduck-api -am test` 26项通过（DemandWorkflowTest 22、OidcIdentityServiceTest 4）。尚未部署、尚未推送。
- 2026-11-14 后台管理员分级：`rm_demand_admin` 仅获后台入口与建单能力，后台列表/总表/汇总/导出按本人创建或 `can_manage` 过滤；`/admin/roles` 仍仅全局管理员，原表单数据/报表/回收站仍仅全局管理员。后端 29 项隔离测试、240真实浏览器4项通过（DemandWorkflowTest 25、OidcIdentityServiceTest 4）；已部署240（应用镜像 `sha256:b6bf2581c2e19a6b398a0deccea16ee2a2a9b9379d545221c490dc73fd9c1059`），无数据库变更；尚未推送 GitHub。
- 2026-11-15 AI 建表修复与设计器协作：修复无配置时 NPE 与静默失败，模型默认迁移到 `deepseek-v4-flash`/`deepseek-v4-pro`，新增状态接口、POST 流式会话、深入思考、历史裁剪、错误分类与管理员权限；后端 51 项隔离测试通过。已部署 240（应用镜像 `sha256:8c59736095ae1f4b7a3a7719a408fa69c1cfab0f0fecf8fe62b5639e2d05305e`，Nginx 新增 `/ai/` 无缓冲 location），配置前验证通过；尚未推送 GitHub。
- 2026-11-15 方案模式与右侧平铺补充：流式接口新增 `stage=clarify/plan/generate` 与 `planJson`，新增澄清/方案提示词；设计器改为右侧可拖拽平铺。后端 52 项隔离测试通过（原有 29 + AI 23）。已再次部署 240（应用镜像 `sha256:023c3c601808a14d0be8e58e8723ca228b60ec24dc1f1ca53b9413279ace9b08`，JAR `b3c132ce…`），1024×768 真实浏览器核对平铺、拖拽、方案模式与配置页通过；真实 Key 联调通过（测试连接 453ms、创建流程 5 题、设计器合成表单方案/生成/应用成功且未改题目保留 ID、合成数据清零）；网关无独立 Pro/Reasoner，深入思考同模型并展示 `reasoning_content`；方案卡滚动修复静态 `index.html` `4fd3cbca…`。尚未推送 GitHub。详见 `doc/AI建表与协作说明.md`。
- 2026-11-15 最高管理员绑定：仅向 240 的 `rm_demand_identity` 插入 Casdoor `ruima-admin`（`f3ce4605-…`）→ 本地 `ac_user.id=1` 一条映射，未改代码、未重建镜像、未重启容器；插入 1 行并复核，其余身份映射不变。真实登录后的管理员能力待用户核验，尚未推送 GitHub。
- 尚未完成整栈异机恢复、异机备份、多人并发压测及业务试点完整验收；不能据此宣称已正式投产。

## 240部署边界

- 测试入口 `http://192.168.1.240:18000`；原15000实例和其他应用保持。应用仅回环18001，MySQL不发布主机端口。
- Compose及受限报告位于 `/data/apps/ruima-survey-test/`；静态页面位于 `/www/wwwroot/ruima-survey-test`；持久化数据位于 `/data/appdata/ruima-survey-test/{mysql,upload,logs}`。
- 外部配置位于服务器受限secrets目录，只通过已配置且获授权的运维连接访问必要项，不将其复制到普通目录或Git。独立登录应用为 `admin/app-ruima-survey`，组织ruima。
- 浏览器授权入口为 `http://wanghao.yun:9999/login/oauth/authorize`，令牌issuer为 `http://192.168.1.237:13004`，回调为 `http://192.168.1.240:18000/auth/oidc/callback`。不能混用浏览器入口和内部签发地址，不能复用其他应用密钥。
- 最高管理员：统一登录环境下，Casdoor 组织 `ruima` 的 `ruima-admin` 已通过 `rm_demand_identity` 映射到本地 `ac_user.id=1`（2026-11-15）；不新增代码路径、不采信 Casdoor 角色声明。回退为删除该映射行，无需重启应用。
- 部署前重新核对当前服务、挂载、授权和回退材料；只处置本次目标。静态资源先更新资源文件、后更新index.html；核对传输前后SHA256，不能用校验不一致的包部署。已有库先备份并执行增量迁移，再部署新后端和前端；回退时先前端后后端，不删除新增列。
- 验收后清理本次合成数据，核对既有服务基线，记录备份、保留依据、剩余风险及实际验证范围。异机备份、整栈恢复和业务验收不能由构建通过代替。

## 交付与接续

- 先说明完成了什么，再列修改、实际运行的检查及结果、未验证项、提交号和部署状态。
- 本文与关联说明中的日期、测试数量和环境状态是历史证据；接手时重新检查源码与环境，不保证未来仍保持相同数量或运行状态。
- 修改业务规则、构建命令、品牌或部署方式后，同步本文件及相应运行说明。CLAUDE.md只维护入口，不复制另一套项目规则。
