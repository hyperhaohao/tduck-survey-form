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
- 填报者、经办人、管理员能力取并集；仅管理员创建收集单，前端按能力显示入口，后端必须校验权限。
- 经办人查看获授权总表，处理分派给自己的需求并填写采纳及实施结果；正式回复对本人可见，内部备注不向填报者暴露。
- 公示由管理员审核后发布快照，私有处理进展不自动覆盖已发布内容。本人数据、内部备注和附件均按实际权限隔离。
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
- 扩展入口为 `tduck-api/src/main/java/com/tduck/cloud/api/demand/`：DemandForms收集单、DemandAccess权限、DemandSubmission提交关联、DemandWorkflow处理、DemandGuard管理约束、DemandFileGuard附件保护、OidcController/OidcIdentityService登录。
- `/demand-api/*`为业务接口，`/demand/*`为前端页面；原提交接口与需求索引应保持同一事务，处理与分派保留版本校验、行锁及冲突拒绝。
- 新增表定义见 `doc/ruima-demand-schema.sql`；仅首次初始化执行一次，后续使用增量迁移。不得向已有业务库重导会删表的官方初始化SQL，不重复执行独立索引创建语句。
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
- DemandWorkflowTest和OidcIdentityServiceTest使用隔离H2及合成登录服务。按变更覆盖越权、匿名、经办结果、备注隔离、公示快照、角标、冲突及附件；只运行必要检查，不使用真实库当单元测试库。
- 历史基线：2026-11-13后端18项隔离测试、240真实API16项及员工本人登录通过；2026-11-14品牌页面另有10项隔离和10项真实浏览器检查。不是本次文档修改重新执行的结果。
- 尚未完成整栈异机恢复、异机备份、多人并发压测及业务试点完整验收；不能据此宣称已正式投产。

## 240部署边界

- 测试入口 `http://192.168.1.240:18000`；原15000实例和其他应用保持。应用仅回环18001，MySQL不发布主机端口。
- Compose及受限报告位于 `/data/apps/ruima-survey-test/`；静态页面位于 `/www/wwwroot/ruima-survey-test`；持久化数据位于 `/data/appdata/ruima-survey-test/{mysql,upload,logs}`。
- 外部配置位于服务器受限secrets目录，只通过已配置且获授权的运维连接访问必要项，不将其复制到普通目录或Git。独立登录应用为 `admin/app-ruima-survey`，组织ruima。
- 浏览器授权入口为 `http://wanghao.yun:9999/login/oauth/authorize`，令牌issuer为 `http://192.168.1.237:13004`，回调为 `http://192.168.1.240:18000/auth/oidc/callback`。不能混用浏览器入口和内部签发地址，不能复用其他应用密钥。
- 部署前重新核对当前服务、挂载、授权和回退材料；只处置本次目标。静态资源先更新资源文件、后更新index.html；核对传输前后SHA256，不能用校验不一致的包部署。
- 验收后清理本次合成数据，核对既有服务基线，记录备份、保留依据、剩余风险及实际验证范围。异机备份、整栈恢复和业务验收不能由构建通过代替。

## 交付与接续

- 先说明完成了什么，再列修改、实际运行的检查及结果、未验证项、提交号和部署状态。
- 本文与关联说明中的日期、测试数量和环境状态是历史证据；接手时重新检查源码与环境，不保证未来仍保持相同数量或运行状态。
- 修改业务规则、构建命令、品牌或部署方式后，同步本文件及相应运行说明。CLAUDE.md只维护入口，不复制另一套项目规则。
