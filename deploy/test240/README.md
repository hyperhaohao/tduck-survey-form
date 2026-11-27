# 240 独立测试实例部署说明

**瑞玛需求征集已在 http://192.168.1.240:18000 部署，沿用原填鸭界面风格，默认登录填写、可选匿名。** 本目录保留无凭据部署模板；实际配置由服务器受限运行目录维护，不复制到仓库。

**2026-11-26 240 已升级到当前版本（多主管、逐级审批、消息中心导航、钉钉推送代码）。240 现在是 237 的前置测试环境：先在 240 验证，通过后再迁移 237。** 钉钉应用凭证尚未配置，240 上 `ruima.demand.dingtalk.enabled` 默认关闭，不会向真实员工发送消息；验证真实待办/工作通知前需在 240 受限 secrets 增加钉钉配置并重启 app。

## 一、运行配置

- Compose：`/data/apps/ruima-survey-test/compose.yaml`，项目名 `ruima-survey-test`。
- 新增应用与 MySQL 两个容器，独立网络；应用仅绑定 `127.0.0.1:18001`，数据库不发布端口。
- 18000 由 Nginx 提供静态页面及接口代理，Nginx 与 UFW 均限制 `192.168.1.0/24`。原 15000 实例保持运行。
- 页面：`/www/wwwroot/ruima-survey-test`；配置：`/www/server/panel/vhost/nginx/ruima-survey-test.conf`。
- 数据：`/data/appdata/ruima-survey-test/mysql`、`upload`、`logs`。数据库512MiB，应用1400MiB；EHCache 无需新增 Redis。
- 外部配置：`/data/apps/ruima-survey-test/secrets/application-test.yml`，配合 `SPRING_CONFIG_ADDITIONAL_LOCATION`；目录root0700。
- MySQL 密码文件单独只读挂载，归容器 mysql UID、0400；不能将root0700的整目录挂进 mysqld 运行用户的路径。

## 二、登录与表单

- 独立应用 `admin/app-ruima-survey`，组织 `ruima`，回调精确为 `http://192.168.1.240:18000/auth/oidc/callback`。
- 浏览器授权入口使用 `http://wanghao.yun:9999/login/oauth/authorize`，签发者仍为 `http://192.168.1.237:13004`；令牌和公钥接口走内网。二者不能混淆，否则上游登录可能提示回调域名错误。
- 员工界面只显示“登录”。客户端密钥仅存本实例必要运行配置，禁止复用其他应用密钥或入库。
- 登录不会自动授予管理员；本次成功登录账户已显式开通测试管理员，并授权样例收集单的填报和经办能力。
- 保留“需求征集（测试）”样例收集单 `EpA5AG0f`。原表单组件的 `scheme` 必须包含 `config`；附件 `accept` 采用数组，与当前生成器一致。
- 存储配置 `domain` 为 `http://192.168.1.240:18000/u`，上传目录 `/application/upload`。私有附件必须经授权下载接口读取。

## 三、维护与回退

```bash
cd /data/apps/ruima-survey-test
sudo docker compose ps
sudo docker compose up -d
sudo nginx -t
```

部署镜像由经过测试的 JAR 和固定原填鸭基础镜像构建。本次构建用 JAR 保留在 `release/application.jar`；重新构建时先复制到 `build/application.jar`。静态资源采用文件名摘要，先更新资源再更新 `index.html`，无需重启其他站点。

受限报告位于 `reports/`，本机备份位于 `backups/`；数据库备份不包含 `CREATE DATABASE/USE`，恢复时必须明确指定新建的隔离数据库。备份内含运行凭据和业务数据，保持root专用，不上传Git。

回退只处理本实例的镜像、配置及静态资源。不要对含私有数据的实例直接关闭需求开关并开放旧附件路径；不要将官方初始化脚本导入已有业务数据库。

## 四、验证与后续事项

- 后端隔离测试18项，前端隔离页面测试9项；240真实API检查16项，真实浏览器检查6项，员工本人登录成功。
- 真实浏览器覆盖首页、附件上传、原表单填写提交、回执进入详情、390px手机菜单；页面运行错误和本地接口失败均为0。
- 本次合成测试用户、答卷与附件按确切归属清理，保留空样例收集单及员工权限。原有服务基线与备份验证结果以服务器 `reports/closeout.json`、`backup-verification.json` 为准。
- 本机数据库原生导入及表行数比对不等于整栈恢复；尚未完成异机备份、整栈恢复、并发压测和业务试点验收。

后续事项：内网试用、确认实际收集单与经办授权、配置独立备份目标，再评估正式切换。

## 五、2026-11-14品牌更新

界面品牌统一为“瑞玛问卷系统 / RUIMA Survey”，使用公司红色圆标。前端 `src/config/brand.js` 为名称、页脚与带版本图标链接的统一配置，`public/favicon.ico` 包含16、32、48、64、128、256像素，另有180像素手机收藏图标。

仅替换用户可见品牌及旧默认名称，保留开源许可、内部依赖及接口路径。生成器中可见文案通过随仓库维护的构建适配替换，不编辑node_modules。原15000实例保持运行。

发布时备份本实例静态目录及相关显示配置；自定义问卷内容不迁移。后续品牌修改从集中配置更新，并递增图标版本。

本轮最终验证：生产构建通过、10项隔离页面回归通过；240真实浏览器10项检查通过，另核对后台、设计器、预览、维护登录及404页面，无可见旧品牌与页面运行错误。22个既有容器基线保持；本次两条合成答卷和两份附件已清理，保留发布前回退材料。

## 六、2026-11-14 按收集单分类更新

- 后端新增 `/demand-api/requests/summary` 汇总接口，公示返回所属收集单；前端“我的需求 / 待我处理 / 需求公示”改为收集单卡片下钻。
- 构建校验：后端 JAR SHA256 `d122a9df81446fc8f5a2ae02ac31c212b58a99188bffac4cf47ae9669a352e39`；前端 150 个静态文件，`index.html` SHA256 `5a1e60999f4bfeb420d33c528778ba430089db6a113609f34959b3e2ecdab714`，先更新资源后更新入口。
- 部署范围：仅重建 `ruima-survey-test-app-1`；数据库、Nginx 配置及其它容器未改动，22 个既有容器与 15000 等入口保持正常。
- 验证结果：后端 19 项隔离测试、前端 12 项页面回归通过；240 真实接口与浏览器 7 项检查通过，无页面运行错误与失败请求；合成需求与公示按 ID 清理，相关表剩余 0 行。
- 回退材料：`backups/groups-20261114-pre/` 保留原 JAR、静态目录和 `image-before.txt`；旧镜像标记为 `ruima/survey-test:rollback-20261114-groups`。

## 七、2026-11-14 单据管理员、导出与批量更新

- 数据库增量迁移已执行：`rm_demand_acl.can_manage`、`rm_demand.publication_skipped` 均已存在；创建人回填为单据管理员，当前显式管理员 1 行。
- 构建校验：后端 JAR SHA256 `f8269bb3a28bd79d0441f4733eed33dac282ec7c3b658feced58112416d0795d`；前端 150 个静态文件，`index.html` SHA256 `eb0472d41903023730f043024cfa2f114213f9d5c5632d8fe28fe468688547f9`，先更新资源后更新入口。
- 运行状态：`ruima-survey-test-app-1` 健康，镜像 `sha256:aa5f141d24fae6d180bd4cbb500ccbb9b7d0dedd4d89630354062cec6a9339b2`；入口、我的需求、待我处理、需求公示、后台和登录页均返回 HTTP 200。
- 线上冒烟：管理员能力包含 `manage`，可经办成员 1 人；需求导出与公示导出均返回 xlsx；合成需求完成“跳过公示 → 自动公示 → 按 ID 清理”，相关表剩余 0 行。
- 首次全量部署因冒烟断言使用布尔身份比较、未接受数据库返回的非布尔真值而自动回滚；随后复用已构建镜像和已上传静态资源完成部署，未重复迁移，也未重复传输 159MB 包。
- 回退材料：`backups/manager-20261114-pre/` 保留迁移前数据库转储（SHA256 `5f6b4dcb1db457b9fb57e55e57de9038cb91ed88523984797085b5bad4648b3c`）、原 JAR（`d122a9df…`）、静态目录和原镜像 ID（`79028f9c…`）；旧镜像标记为 `ruima/survey-test:rollback-20261114-manager`。回退时先恢复前端，再切回旧镜像；数据库新增列保留，不做破坏性删除。
- 本次未推送 GitHub；对应本地提交为后端 `293de7d`、前端 `7f179a2`。

## 八、2026-11-14 后台管理员分级上线

- 本次无数据库变更，未执行迁移；仅重建 `ruima-survey-test-app-1`，数据库、Nginx 配置及其它容器未改动，其他容器保持运行。
- 构建校验：后端 JAR SHA256 `f617f75cad89170c7dbe5c962555562ecfb70afb705ea9e50b0e723a0ea64b4c`；前端 150 个静态文件，`index.html` SHA256 `c33049de6d74a553ad8a05e9bdd3851471f949360d5ab78b9a228c513325e9fb`；应用镜像 `sha256:b6bf2581c2e19a6b398a0deccea16ee2a2a9b9379d545221c490dc73fd9c1059`，部署前镜像 `sha256:aa5f141d24fae6d180bd4cbb500ccbb9b7d0dedd4d89630354062cec6a9339b2`。
- 验证结果：维护账户 `capabilities` 的 `admin/global/manage` 均为 true；后台收集单列表正常；`scope=managed` 导出返回 xlsx；合成需求验证“未回复时自动公示返回 409、正式回复后可发布”，跳过/自动公示及公示导出通过；`/`、`/demand/mine`、`/demand/pending`、`/demand/publications`、`/demand/admin`、`/project/my`、`/login/entry` 均 HTTP 200。真实浏览器 4 项（首页、后台、我的项目、设计器）通过，页面运行错误与失败请求均为 0；受限管理员路径由后端隔离测试与前端合成角色回归覆盖。合成需求、公示、消息与原始数据按 ID 清理，剩余 0 行。
- 回退材料：`backups/admin-scope-20261114-pre/` 保留数据库转储、原 JAR、静态目录和原镜像 ID；旧镜像标记为 `ruima/survey-test:rollback-20261114-admin-scope`。回退时先恢复静态目录，再切回旧镜像；本次无数据库变更。
- 部署来源为本地未提交工作区（后端 HEAD `72ec30f`、前端 HEAD `a974195`，各有 13 个修改文件）；尚未推送 GitHub。

## 九、2026-11-15 AI 建表与设计器协作上线

配置、接口、权限与排障见 [AI 建表与协作说明](../../doc/AI建表与协作说明.md)；本节只记录部署与验证。

- 本次无数据库结构变更，不执行迁移；`deepSeekEnvConfig` 由管理员在后台保存时自动创建。部署前镜像 `sha256:b6bf2581c2e19a6b398a0deccea16ee2a2a9b9379d545221c490dc73fd9c1059`，部署后镜像 `sha256:8c59736095ae1f4b7a3a7719a408fa69c1cfab0f0fecf8fe62b5639e2d05305e`；应用健康，其他容器保持运行。
- 构建校验：后端 JAR SHA256 `2dd1b0f5fccb4773d0df2908e800c95bc08f1be53080009546d340d8d3a07b83`；前端 150 个静态文件，`index.html` SHA256 `8e72e3160e117a148ae444d741febe4bd9b2dc5da71cef1739db28eda3133805`（部署前 `c33049de6d74a553ad8a05e9bdd3851471f949360d5ab78b9a228c513325e9fb`）；先更新资源后更新入口，逐文件 SHA256 校验通过。
- Nginx：在 `/www/server/panel/vhost/nginx/ruima-survey-test.conf` 通用正则之前插入 `location ^~ /ai/`，关闭 `proxy_buffering`/`proxy_cache`/`gzip`，`proxy_http_version 1.1`、`Connection ""`、读写超时 600 秒；`nginx -t` 与 reload 通过。仓库模板 `backend/deploy/test240/nginx.conf` 已同步。
- 配置前验证：维护账户 `/ai/status` 返回 `configured=false` 及明确中文原因；`POST /ai/gen-form-chat/stream` 返回 `text/event-stream` 和 `AI_NOT_CONFIGURED` 错误事件，不再出现 `AiChatUtils` NPE；未登录访问 `/ai/test-gen-form` 返回 401；`/`、`/demand/mine`、`/demand/pending`、`/demand/publications`、`/demand/admin`、`/project/my`、`/login/entry` 均 HTTP 200。
- 真实浏览器（1024×768）：`/project/my` 的 AI 抽屉显示「AI 服务不可用」和「AI 服务未配置」、发送禁用、无横向溢出；设计器 `?ai=1` 自动打开 AI 协作抽屉并显示同一提示；`/manage/system?tab=deepSeekEnvConfig` 打开 AI 配置，默认普通模型 `deepseek-v4-flash`、深入思考模型 `deepseek-v4-pro`。页面运行错误与失败请求均为 0。
- 回退材料：`backups/ai-20261115-pre/` 保留部署前 JAR、静态目录、镜像 ID、`index.html` 哈希和 Nginx 配置（root 0600）；旧镜像标记为 `ruima/survey-test:rollback-20261115-ai`。回退时先恢复 Nginx 和静态目录，再切回旧镜像；本次无数据库变更。
- 未创建合成业务数据；AI 未配置探测未调用上游模型。真实生成、深入思考与设计器修正需管理员在「系统配置 → AI 配置」填写 Key 后联调。尚未推送 GitHub。

## 十、2026-11-15 方案模式与设计器右侧平铺

方案模式流程、配图、已知限制与设计取舍见 [AI 建表与协作说明](../../doc/AI建表与协作说明.md)。

- 本次无数据库变更；应用重建一次（方案模式阶段接口与提示词），随后仅做前端静态资源最小替换（响应式修复），未重启应用、未改 Nginx。`/ai/` location 沿用第九节配置。
- 应用镜像：部署前 `sha256:8c59736095ae1f4b7a3a7719a408fa69c1cfab0f0fecf8fe62b5639e2d05305e`，部署后 `sha256:023c3c601808a14d0be8e58e8723ca228b60ec24dc1f1ca53b9413279ace9b08`。
- 构建校验：JAR SHA256 `b3c132ce2a4b76bd1fd4d03577f6599db9482db836835ad7e784dc17691bbbb0`；前端 150 个静态文件，首次 `index.html` SHA256 `d8fa69dbcf02446c82638fe400af160ea0a4c4d4b973a8f02abb1e5f3e013972`，随后响应式修复替换为 `9d54ac2378a7de164ddd851c033df5ea39c002ae9f1bb433f46d51a86ef3003a`；逐文件哈希校验通过。
- 配置前验证：7 个页面均 HTTP 200；`/ai/status` 仍返回 `configured=false` 和明确原因；流式接口仍返回 `AI_NOT_CONFIGURED` 事件；未登录旧接口 401；其他容器保持运行。
- 1024×768 真实浏览器：建表页方案模式/快速生成切换、方案记录空态、未配置横幅与发送禁用；设计器 AI 面板右侧平铺，无遮罩，默认 340px、画布 594px，拖拽后 383px，页面无横向溢出；配置页默认 `deepseek-v4-flash`/`deepseek-v4-pro`；页面运行错误与失败请求为 0。
- 回退材料：`backups/ai-workflow-20261115-pre/` 保留方案模式上线前的 JAR、静态目录、镜像 ID、`index.html` 哈希和 Nginx 配置；`backups/ai-static-20261115-pre/` 保留静态替换前的完整静态目录。旧镜像标记为 `ruima/survey-test:rollback-20261115-ai-workflow`。回退时先恢复静态目录，再切回旧镜像；本次无数据库变更。
- 未创建合成业务数据；真实 Key 下的澄清、方案、生成、深入思考与设计器应用联调待管理员填写 Key 后进行。尚未推送 GitHub。

## 十一、2026-11-15 最高管理员绑定 Casdoor 账号

- 员工入口只保留统一登录；原系统超级管理员是本地 `ac_user.id=1`，原先只能由旧账号密码登录。本次将 Casdoor 组织 `ruima` 的 `ruima-admin`（Casdoor 用户 id `f3ce4605-714e-42af-9038-bdc825052550`）显式绑定到本地 `ac_user.id=1`，使其经统一登录后即拥有系统配置、用户管理、模板和全部收集单权限。
- 变更仅一条映射：`rm_demand_identity` 新增 `identity_hash=SHA-256(issuer + "\n" + subject)`、issuer `http://192.168.1.237:13004`、subject 为该 Casdoor 用户 id、`user_id=1`。未改代码，未重建镜像，未重启容器，未改 Nginx。
- 备份：`backups/identity-20261115-pre/identity-and-user.sql`（root 0600，SHA256 `c8d11e317befc463c5c5feafad87e9b488081a76f31605cccba1937acd8b3d62`），保留 `rm_demand_identity` 与 `ac_user` 两表。
- 验证：预检 `rm_demand_identity` 无 `user_id=1`、无目标哈希；插入影响 1 行；复核 `user_id=1` 恰 1 行，其余两条身份映射不变。真实 Casdoor 登录后的管理员能力待用户登录核验。
- 回退：`DELETE FROM rm_demand_identity WHERE identity_hash='fda2520e48cf1c99d453630cb15ef2e5cdc9a6773c0851a79e7a18a0825b7efd';`；必要时用上述备份还原两张表。回退无需重启应用。
- 未关闭旧账号密码登录，本地 `admin@tduckcloud.com` 保留为应急维护通道，凭据仅在服务器受限 secrets 目录。尚未推送 GitHub。
- 2026-11-16 密码重置：经 Casdoor 管理接口重置 `ruima-admin` 密码，Casdoor 库内密码已更新为 bcrypt 哈希并通过旧密码校验；新密码明文仅存 240 的 `secrets/ruima-admin-account.json`（root 0600）。该账号同时用于 OpenViking，其他系统需同步更新。真实浏览器登录后的管理员菜单仍待核验。
- 真实 Key 联调（2026-11-15 23:40）：管理员配置的内网 New API 网关 Base URL 原为 `http://192.168.1.236:3000//v1`（多一个斜杠），网关返回 HTML，测试连接解析失败、流式请求超时；保留 Key 不变，仅把 Base URL 规范为 `/v1` 并保存即时生效。测试连接 453ms 成功；创建流程澄清无需提问、方案 5 题、生成 5 题；设计器合成表单方案 3 题、确认生成并应用成功，最终题目为多选“您常用的沟通方式”、多行文本“您对远程办公的建议”、数字“每周远程办公天数”，未改动的多行文本保留原 `formItemId`；合成表单与题目按 ID 清理，剩余 0 行。
- 网关当前只提供 `deepseek-flash`、`deepseek-v4-flash` 等模型，没有独立 Pro/Reasoner；深入思考开关使用配置的 `reasoningModelName`，当前与普通模型相同，不会切换到更强模型；网关返回的 `reasoning_content` 仍会在面板展示。若需更强推理，请在网关新增模型后修改 AI 配置。
- 方案卡出现时自动滚动到卡片顶部，保证「确认并生成」可见；该滚动修复已静态替换，最终 `index.html` SHA256 `4fd3cbca60190cb1d75ba7911e4669524cd572dd658601599a1ef8c63835beb0`，备份 `backups/ai-static-scroll-20261115-pre/`，未重启应用、未改 Nginx。尚未推送 GitHub。

## 十二、2026-11-16 收集单作废与表单级通知权限

- 背景：后台管理员在「后台管理 → 收集单」找不到删除/作废入口；打开通知设置时提示“仅全局管理员可执行此操作”，但设置本身仍能保存。
- 修复：`DemandGuard.consoleFormAllowed` 放行 `/user/form/wx/`（通知二维码、提醒人查询与删除），控制器仍通过 `FormAuthUtils.hasPermission` 校验该收集单的管理权限；新增 `rm_demand_form.voided` 与 `POST /demand-api/admin/forms/{key}/void`，作废后停止新填报、从员工入口隐藏，已有需求与处理记录保留，可恢复。原表单删除仍走「我的项目 → 删除表单 → 回收站」。
- 数据库：`rm_demand_form` 新增 `voided BOOLEAN NOT NULL DEFAULT FALSE`；执行前保存 `backups/void-20261116-pre/rm_demand_form.sql`（root 0600）。首次执行迁移，重试部署时检测为已存在并跳过。
- 构建：后端 53 项隔离测试通过；前端生产构建及 34 项页面回归通过（新增作废、已作废→恢复 2 项）。
- 部署：应用镜像 `sha256:023c3c60…` → `sha256:734380e3bc1e92e5c1a5a2353d1aaf4eebf7e93bbc8e9b6824f57a7d8abdea2a`；JAR SHA256 `9ff0256dab7849cca9a544c8ac4881cf6abaf9b549b5e51d5fd33f4a363deaf4`；前端 150 个静态文件，`index.html` `4fd3cbca…` → `5ec77ce000c939a4a16a8c85198be15eb0cede18acd41517475fb74f810521fc`；先资源后入口，逐文件 SHA256 校验通过；未改 Nginx，其他容器保持运行。
- 真实验证（1024×768）：合成收集单作废后 `/demand-api/forms` 不再返回、公开详情 `voided=true`/`allowAnonymous=false`、后台列表 `voided=true`；公开提交返回“该收集单已作废”；恢复后重新可见。浏览器合成表单卡片显示“作废”，确认后出现“已作废”标签与“恢复”按钮，恢复成功；页面运行错误 0。`/user/form/wx/notify-qrcode` 对维护账户返回 HTTP 200/code 200（非全局后台管理员路径由 53 项隔离测试覆盖，未在线上用独立非全局账户复测）。`/ai/status` 仍为已就绪。
- 首次部署因验收清理脚本使用了错误的表名 `fm_user_item` 触发自动回滚，应用恢复旧镜像；修正为 `fm_user_form_item` 并重试成功。`voided` 列保留，未做破坏性回退。
- 清理：合成表单、配置、权限与需求按确切 `form_key` 清理，剩余 0 行；未触碰真实收集单。
- 回退材料：`backups/void-20261116-pre/` 保留部署前 JAR、静态目录、镜像 ID、`index.html` 哈希与 `rm_demand_form` 转储；旧镜像标记为 `ruima/survey-test:rollback-20261116-void`。回退时先恢复静态目录，再切回旧镜像；`voided` 列保留，不回滚删除。
- 部署来源为本地未提交工作区（后端 HEAD `5176015352771032ccdccafe73d4e3bc4052a78b`、前端 HEAD `5c3cb9dd478540e565d08a9dda7cf5d89a3495fd`；后端 36、前端 29 个修改文件）；尚未推送 GitHub。

## 十三、2026-11-16 超级管理员收集单删除与全局回收站

- 权限：超级管理员（本地 `ac_user.id=1`）可删除任意收集单、恢复任意回收站收集单、彻底删除无需求表单；后台管理员仍限本人创建或 `can_manage` 的收集单。作废权限沿用第十二节。
- 删除语义：`fm_user_form.is_deleted=TRUE` 逻辑删除并记录 `deleted_by/deleted_at`；`rm_demand`、`fm_user_form_data`、附件和公示保留；收集单从后台列表与员工入口隐藏，可在全局回收站恢复。彻底删除仅允许 `rm_demand` 与 `fm_user_form_data` 均为 0 的表单，事务清理原表单关联表及 `rm_demand_form`/`rm_demand_acl`。
- 作废审计：`voided_by/voided_at` 记录操作人；恢复作废清空。
- 数据库：`rm_demand_form` 新增 `voided_by`、`voided_at`、`deleted_by`、`deleted_at`；执行前备份 `backups/delete-20261116-pre/rm_demand_form.sql`。
- 构建：后端 54 项隔离测试通过；前端生产构建及 36 项页面回归通过（新增删除/回收站恢复/彻底删除 2 项）。
- 部署：应用镜像 `sha256:734380e3…` → `sha256:13a0f3c618dc68bcdd1e60a143832bed13e870d7b316847ad397f91ca751b02d`；JAR `5f86c82ef8faafbd605197baa1820b0f58b1b7cb12512648c71af92b465aec7d`；前端 150 个静态文件，`index.html` `5ec77ce0…` → `7190d6b86907d238adc3f2a9ed26f02879f2c9af385962dbf034d22db580590f`；未改 Nginx，其他容器保持运行。
- 真实验证：空收集单作废审计 `voided_by=1`、删除审计 `deleted_by=1`、员工列表隐藏、公开详情 `deleted=true`、提交返回“该收集单已删除”、回收站可见、彻底删除后关联行 0；有 1 条需求与 1 份答卷的收集单删除后回收站显示 1/1，彻底删除被拒，数据保留，恢复后重新可见。1024×768 浏览器删除、恢复、彻底删除（输入名称确认）通过，页面错误 0。合成数据按精确 key 清理，剩余 0 行。
- 说明：全局回收站同时列出历史上已删除的原表单（截图中的两条“未命名问卷”），非本次验收创建，未清理。
- 回退材料：`backups/delete-20261116-pre/`；旧镜像 `ruima/survey-test:rollback-20261116-delete`；新增列保留，不回滚删除。
- 部署来源为本地未提交工作区（后端 HEAD `5176015352771032ccdccafe73d4e3bc4052a78b`、前端 HEAD `5c3cb9dd478540e565d08a9dda7cf5d89a3495fd`）；尚未推送 GitHub。

## 十四、2026-11-26 当前版本与钉钉推送（待配置）

- 240 从旧版本升级到当前构建：JAR SHA256 `02905be22910b2a2e96c326027dee86433d3e603273bf35501cd12d525eace47`，应用镜像 `sha256:c68275e3c1d8f919efcbb9cf1659268cd1844db7a6f4f3d40bff4063ef6f2019`（此前钉钉构建 `ac3bc416…`/`81d238ac…`、`2be711ca…`/`7953417a…`），旧镜像 `ec612908…` 保留为 `ruima/survey-test:20261126-pre-dingtalk`，`81d238ac…` 保留为 `ruima/survey-test:20261126-pre-readskip`；读后跳过修正备份 `backups/read-skip-20261126`、报告 `reports/read-skip-deployment-20261126.json`；最终后端备份 `backups/final-dingtalk-20261126`、报告 `reports/dingtalk-final-backend-20261126.json`；静态 156 个文件，`index.html` `ba184e97ac4d77c4d11dce46b969fdf03cbe1d500a235a6512ad1d4e713c379e`，旧 `index.html` `7190d6b8…`。备份 `backups/dingtalk-20261126-pre`（全库转储、旧 JAR、旧镜像 ID、旧静态），报告 `reports/dingtalk-deployment-20261126.json`。
- 迁移：240 缺失的部门 ACL、部门快照/排序、追问、公示归档、只收集、平台权限、部门主管、必填/消息、单级审批、多主管、钉钉 outbox 已按顺序执行；已有作废/删除/成员管理迁移跳过。升级前计数 2 条需求、2 条已配置收集单、5 个本地用户、6 个原表单。
- 验证：12 个页面（含 `/demand/approvals`、`/demand/messages`、`/demand/admin`）均 200；维护账号 `capabilities` 为全局管理员，后台 4 个收集配置返回 `approvalMode`；`/admin/departments`、`/notices`、`scope=approval` 和 `/admin/dingtalk/status` 均 200。真实浏览器 1024/390 打开后台「钉钉推送」页签，状态、映射、设置区正常且无横向溢出；当前显示“部署总开关未开启或应用凭证缺失”，符合预期。
- 测试：后端 129 项隔离测试通过（DemandWorkflowTest 96、DemandPushTest 6、Oidc 4、AI 23）；前端生产构建通过，94 项页面回归通过。
- 钉钉真实发送未验证：240 的 `secrets/application-test.yml` 尚无 `ruima.demand.dingtalk` 配置，`admin/dingtalk/status` 返回 `configured=false`，没有外呼。需要在 240 secrets 中增加 `ruima.demand.dingtalk`（app-key/app-secret/agent-id/creator-union-id、`link-base: http://192.168.1.240:18000`、`todo-enabled: true`），重启 app 后同步通讯录、确认管理员映射，再只向管理员本人发送测试；不向全体员工发送。若 240 链接只在内网可用，验收时需确认钉钉手机端可达，或使用 237 的公网链接做最终验收。
- 237 尚未迁移钉钉推送：240 真实钉钉测试通过后再按 237 迁移/部署流程执行。
- 回退：恢复旧静态、旧 JAR/镜像并重启 app；新增表和列保留，不做破坏性删除。

## 十五、2026-11-26 钉钉配置页、凭证与 240 真实验证（进行中）

- 钉钉配置独立成全局管理员顶级页 `/demand/dingtalk`，不再放在后台管理页签；页面包含 AppKey、AppSecret、AgentId、链接基址、部署总开关、待办创建人、推送事件/待办开关、安静时段、通讯录同步、映射确认/绑定、测试连接、测试发送和推送日志。AppSecret 只写服务器受控运行时文件，接口不回显。
- 240 新构建已部署：JAR SHA256 `699e4ed43d2f609439193279fc245bef7b8af855d504d42a187f17dd6b5e84f9`，应用镜像 `sha256:a87415ae6cb24fa11287c66a5c6120a2037c5a53e309ba3ae0ee457a41d929de`，静态 `index.html` `5bf0da135a65f0598bc9eabc5e18c5e834fc4e354e0f2d7ab59ae6cb51212368`（旧 `ba184e97…`）；13 个页面（含 `/demand/dingtalk`）200，后端 129 项、前端 94 项回归通过。部署备份 `backups/dingtalk-20261126-pre`，报告 `reports/dingtalk-deployment-20261126.json`。
- 运行时配置：compose 的 app 增加 `./runtime:/data/runtime`，宿主目录 `/data/apps/ruima-survey-test/runtime` 为 0700；配置写入 `/data/runtime/dingtalk.json`（0600），启动时覆盖 secrets 默认值，保存后立即生效并清空 token 缓存。compose 变更前备份 `backups/runtime-config-20261126-pre/compose.yaml`。
- 应用凭证与开关：AppKey、AgentId `5165258519`、链接基址 `http://192.168.1.240:18000` 已保存；部署总开关和推送总开关已开启；状态返回 `configured=true`、`masterEnabled=true`、`enabled=true`，`creatorConfigured=false`，映射 0；测试连接返回 `workToken=true`、`todoToken=true`，耗时约 603ms。配置报告 `reports/dingtalk-config-20261126.json`。
- 通讯录同步：240 调用钉钉通讯录失败，应用返回 `60011 应用尚未开通所需的权限：[qyapi_get_department_list]`；未生成任何映射，也没有向真实员工发送消息。需要在钉钉开发者后台为该应用开通通讯录部门列表、部门成员/用户读权限，并确认应用可见范围覆盖员工；随后在「钉钉配置」页重新同步。
- 真实浏览器检查：1024×768 与 390px 打开 `/demand/dingtalk` 通过，应用配置、应用状态、人员映射、推送设置均显示，配置开关为已选中、状态标签“已启用”，无页面运行错误和横向溢出；截图 `.tools/dingtalk-240-config-1024.png`、`.tools/dingtalk-240-config-390.png`。
- 待办创建人和真实发送尚未设置/验证：同步成功后需在页面确认管理员映射、点击「设为创建人」保存创建人 unionId，再只向管理员本人发送测试工作通知和待办。当前 240 本地用户 5 个，维护管理员本地 ID 为 1；通讯录同步后可将其邮箱匹配项或手工绑定项设为创建人。
- 237 尚未迁移钉钉推送；240 真实发送、审批/待填报待办闭环验证通过后再迁移。另：本次修复了 `DemandPush.nextAttempt` 的时区换算，按 Asia/Shanghai 转绝对时间，避免容器 UTC 时首次派发被推迟 8 小时；后端 129 项测试覆盖。

## 十六、2026-11-26 钉钉代码复核对齐修复

- 测试发送部分成功：工作通知发送成功后，待办创建失败不再整体报错；接口返回 `taskId`、`todoStatus=FAILED` 和 `todoError`，前端提示“工作通知已发送、待办失败”，避免管理员误以为全部失败而重复发送。待办未启用、未配置创建者或缺 unionId 时返回 `SKIPPED` 及原因。
- 通讯录同步冲突：邮箱唯一命中的本地账户已被其他钉钉账号占用时，先释放 `INVALID/停用` 的旧映射；仍被有效映射占用则标记 `CONFLICT` 且不再写 `user_id`，避免 `UNIQUE(user_id)` 导致整次同步回滚。
- 派发重新解析映射：工作通知与待办创建在派发时按当前 `CONFIRMED` 映射重新解析，不再使用入队快照；解绑后已排队消息跳过并标记 `NO_MAPPING`，确认映射后重试可正常发送。派发同时把当前 `dingtalk_user_id/union_id` 回写推送行。
- 完成/取消覆盖更多状态：`completeApprovalStep`、`completeRequired`、`cancelDemand`、`cancelForm` 现在覆盖 `PENDING/SENT/FAILED/SENDING`；未创建待办的失败行直接取消，已创建或正在创建的行写 COMPLETE/DELETE，派发时若父待办仍未创建则按父状态重试或跳过，避免遗留待办。
- 测试：后端 133 项通过（DemandPushTest 10，新增测试发送部分成功、FAILED/SENDING 完成、解绑后派发跳过、同步冲突不绑定；DemandWorkflowTest 96、Oidc 4、AI 23）；前端生产构建与钉钉页测试通过。新 JAR 已部署 240，部署后复验测试发送、解绑后派发和同步冲突。
- 2026-11-26 修复版部署与复验：JAR SHA256 `cb4571162e107a9f3143e206c49f24f5e3aaf6255541e6a73f57e960053e2af1`，镜像 `sha256:0fc5bb372cdfebd0210387852e049de25d7b84e44ab3b3aca7679b070228bcc1`，`index.html` `e43a8a75e74a644fd19b05124b655dfa68fbc2816f66eab37328453bc159b111`；13 个页面 200，钉钉状态 200、`configured=true`。通讯录同步返回 users=3、departments=2、matched=2、pendingConfirm=0、unmatched=1、conflict=0、invalid=0；测试发送返回 `taskId=3457214392467`、`todoTaskId=task6e894e85295d1eb184fef3d16cfb4298`、`todoStatus=SENT`。冲突/解绑/FAILED 分支由 133 项隔离测试覆盖。

## 十七、2026-11-26 轮次模型与必填统一部署

- 240 已部署：JAR `30a12b5aa90ffd75fd4edb4f742e15f80a5b30e10355146e7b0314494e91dbb9`，镜像 `sha256:68a4b0c5f0f68296a44c692f148cadb6018f0fc09f5743645d35aa4c7596827c`，`index.html` `f54e8935c398ec0677ed936abf72effaa4698972386d20cc00bc13bc6c71eadc`，13 个页面 200。迁移 `roundModel` 首次执行成功；`requiredAcl` 首次在 `rm_demand_required` 与 ACL 的排序规则 JOIN 处失败，补 `ALTER TABLE rm_demand_required CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci` 后按条件跳过已完成部分；`required` 列已存在，240 旧必填数据为空，无需回填。
- 必填统计接口在 MySQL 下因 ACL 用户与部门字段排序规则不同触发 1267，已改为两条独立查询合并；240 重新部署后 `/admin/forms/EpA5AG0f/required-members` 返回 `roundMode=SINGLE`、`roundDeadline=null`、`reminderMode=DEFAULT`、`subjects=[]`、`stats.total=0`，接口正常。
- 1024/390 真实浏览器打开「1」收集单的收集配置和成员权限：轮次模式「不启用轮次/周期」、周期「每天/每周/每月/每季度」、每周几、提醒「不提醒」；成员权限显示「全选必填」「可填报成员全部必填」「本轮截止」；1024 和 390 均无横向溢出。截图 `.tools/collection-240-config-1024.png`、`collection-240-members-1024.png`、`collection-240-members-390.png`。
- 后端 136 项（DemandWorkflowTest 99、DemandPushTest 10、Oidc 4、AI 23）、前端 88 项需求页回归通过。237 尚未迁移本轮改动。
- 2026-11-26 复核修复与再次部署：历史单轮只写 `round_end_time` 时 `roundDeadline` 回退并在此后保存时迁入 `end_time`；`SELECTED` 保存配置时补齐 required ACL 的 `can_fill`；历史周期 `round_day=NULL` 在前端显示「不指定（沿用原周期）」并保存 `null`，不再重置进行中的轮次；切换周/月/季时校正越界锚点；`formDue` 按轮次模式取整单或本轮截止；桌面成员表压缩列宽，移动端改为名称单元格内 2×2 权限复选。
- 新增幂等迁移 `ruima-demand-migration-20261127-round-backfill.sql`、`ruima-demand-migration-20261127-required-backfill.sql`，部署脚本在列存在时也执行回填，修复 `requiredAcl` 半途失败后无法补跑的问题。240 执行结果 `roundBackfill=applied`、`requiredBackfill=applied`；核对两个 SINGLE 表单均为 `round_no=1`、`round_start_time` 已回填、`round_end_time=NULL`，`single_missing_start=0`，`selected_required_not_fillable=0`。
- 修复版部署：JAR SHA256 `e818592675081f2fa60c5535dee2fcb0b7de90a7f29d3f60178ef69005b49cfd`，镜像 `sha256:fbe477f621fe7896d97f74e704c722d8c280756bbc85f434758db9175333f3de`，`index.html` `1dd4e709c8090ebbc304ed51a3b18037bd6f08e14c3e5049c53755dcdba2eeb2`，13 个页面 200。后端 140 项（DemandWorkflowTest 102、DemandPushTest 11、Oidc 4、AI 23）、前端 89 项需求页回归通过。
- 真实浏览器复验：required API 返回 `roundMode=SINGLE`、`roundStarted=true`；历史周期空锚点选中「不指定（沿用原周期）」；1024 成员表操作列右边界 1000px、完整可见；390/320 成员名称单元格显示 4 项权限复选，页面无横向溢出。截图 `.tools/collection-240-config-1024.png`、`collection-240-members-1024.png`、`collection-240-members-390.png`、`collection-240-members-320.png`。代码未提交、未推送；237 未迁移。
- 2026-11-26 成员授权与轮次补丁再次部署：`rm_demand_acl`/`rm_demand_acl_dept` 部分更新未传 `canFill/canHandle` 时保留原值；`hasOtherManager` 排除停用账户；周期收集配置保存时不再把 `round_end_time` 提升为整单截止；不启用轮次的手工下一轮改为更新整单截止并清空 `round_end_time`。最终 JAR `6d7f026fe646d9bf809c73a7a8b26e7ed8211e26ae5b0c2f3f670f8ca34e3e13`，镜像 `sha256:1d2c36fbc7b999230cc740d77d7dad6d07b17bdf1f2790d8c46b27a5aaade112`，`index.html` 不变 `1dd4e709…`，13 个页面 200；后端 143 项测试通过，required API 复验 200 且 `roundStarted=true`。部署脚本改为每次使用独立备份目录与回退标签（本次 `backups/round-fix-20261126-151719`、`ruima/survey-test:pre-20261126-151719`），避免二次部署覆盖首次回退材料。

## 十八、2026-11-26 消息默认未读、必填联动与仅微信表单防抖

- 消息中心默认「未读」，可切换「全部」。
- 成员/部门权限勾选必填时同步勾选并锁定填报，批量「全选必填」同样联动；后端 `DemandForms`/`DemandDepartments`/`DemandRequired` 与兼容 `/required-members` 统一把 required 行写成 `can_fill=TRUE`，两个回填迁移同步修复历史行。240 核对 `rm_demand_acl`/`rm_demand_acl_dept` 中 `required=TRUE AND can_fill=FALSE` 为 0。
- `/s/Dzd7Wxjt` 闪烁根因：该表单 `fm_user_form_setting.settings.onlyWxWrite=true`、`recordWxUser=true`，桌面端 `/wx/jsapi/authorization/url` 返回 `data:null`，旧代码执行 `location.href=''` 导致页面反复刷新。修复为仅在微信环境尝试授权、等待有效地址、非微信或空地址显示「请在微信客户端打开链接」，只有有效且不同于当前页的地址才 `location.replace`。该表单仍保持仅微信填写；如需桌面填写需在填写设置中关闭「仅在微信中打开」。
- 前端 91 项需求页回归（新增批量必填联动、仅微信表单防抖 2 项）、后端 144 项通过。240 部署 JAR `73c0f0a87fc0cd3412efad8f710d6515ad732e4aef0160dcf70337f8d2e370da`、镜像 `sha256:508120a7b45ec6cf9fcf2f3c083e7993c65c8dd262be84b2ee9d57e94b102be9`、`index.html` `a4fae3d56e91913f1eca1eecb6217c2405b6c7ba679fc184aba661da9e8f259f`，13 个页面 200，备份 `backups/round-fix-20261126-160829`。

## 十九、2026-11-26 Dzd7Wxjt 关闭仅微信填写（仅该表单）

- 原因：`Dzd7Wxjt` 的 `fm_user_form_setting.settings.onlyWxWrite=true`，填写设置页开启「微信答题次数限制」会自动同时打开 `onlyWxWrite` 和 `recordWxUser`；桌面端授权地址为空时旧代码刷新循环，前次已修复为提示「请在微信客户端打开链接」。按用户选择，仅修正该表单，不改设置页联动或产品默认。
- 变更：通过 `POST /user/form/setting/save` 提交 `{formKey:"Dzd7Wxjt", onlyWxWrite:false, recordWxUser:false, wxWriteCountLimitStatus:false}`；后端按 `settings.putAll` 合并，33 个设置键中仅这三项变化，其他字段不变。关闭微信次数限制是因为该表单原 `wxWriteCountLimit=1`，桌面提交的 `wxOpenId` 为空，第一次提交后第二条会被「该微信已经提交过数据」拦截。变更前完整设置保存于 `.tools/dzd7wxjt-setting-before-20261126.json`。
- 验证：`GET /user/form/setting/Dzd7Wxjt` 三项均为 `false`，其他键数量与值不变；`GET /user/form/public/settings/Dzd7Wxjt` 返回 `onlyWxWrite:false`、`recordWxUser:false`；`GET /user/form/setting-status?formKey=Dzd7Wxjt&type=1&wxOpenId=` 连续两次返回 `data:true`。
- 真实浏览器（维护管理员登录）1024×768 与 390×844 打开 `/s/Dzd7Wxjt`：主框架导航次数 1，无刷新循环；显示标题「员工满意度与体验反馈调查」和两道题，不出现「请在微信客户端打开链接」，无页面错误；未提交真实答卷。截图 `.tools/form-240-flicker-1024-5s.png`、`.tools/form-240-flicker-390-5s.png`。
- 注意：设置页联动未改，以后重新开启「微信答题次数限制」或「获取微信用户个人信息」时 `onlyWxWrite` 会再次变为 true；如需桌面填写需再次关闭，或另行修改联动。本次未改代码、未重新构建，240 应用版本不变（JAR `73c0f0a8…`、`index.html` `a4fae3d5…`）。

## 二十、2026-11-26 唯一管理员自己填报后的新需求提醒

- 现象：910004 于 18:56 提交 `Dzd7Wxjt`，`rm_demand` 已生成 `f9c79c57-9604-4963-a476-3fd30759c17e`，但 `rm_demand_notice` 没有对应消息。该收集单 `rm_demand_acl` 只有 910004（`can_fill=1, can_manage=1`），而 `DemandNotices.newDemand` 原本只通知除提交人以外的单据管理员，收件人为空，因此站内消息与钉钉推送都没有产生。
- 修复：`DemandNotices.newDemand` 先取管理员集合；仅当提交人是唯一管理员时保留本人，否则继续排除提交人。存在其他管理员时行为不变；审批中的需求仍在审批完成后才发 `NEW_DEMAND`。新增 2 项隔离测试，后端 146 项通过（DemandWorkflowTest 108、DemandPushTest 11、Oidc 4、AI 23）。
- 部署：240 JAR SHA256 `8474016ffb305d4b1fbebe47e35922da5260c5ca9620a98412a5e72432678f2a`，镜像 `sha256:b21edfe76d072d31dfdb02c4f52ebda89d6a7755368253bf3e46217232996350`，`index.html` 不变 `a4fae3d56e91913f1eca1eecb6217c2405b6c7ba679fc184aba661da9e8f259f`，13 个页面 200；备份 `backups/round-fix-20261126-190625`，回退标签 `ruima/survey-test:pre-20261126-190625`；迁移结果为 skipped/applied 的幂等组合。
- 历史数据：对 `f9c79c57-9604-4963-a476-3fd30759c17e` 回填一条 `NEW_DEMAND` 站内消息给 910004，`read_at IS NULL`，标题十六进制核对为「有新的需求待处理」。回退：`DELETE FROM rm_demand_notice WHERE demand_id='f9c79c57-9604-4963-a476-3fd30759c17e' AND kind='NEW_DEMAND';`
- 注意：钉钉工作通知仍受「钉钉配置」中 `NEW_DEMAND` 开关控制，默认关闭，本次未改默认；消息中心页面需以 910004 登录确认。代码未提交、未推送；237 未迁移。
- 钉钉补充（2026-11-26 19:17）：240 的 `rm_demand_push_setting` 原先只有 `enabled=true`，`event.NEW_DEMAND.work` 未开启；`rm_demand_dingtalk_user` 中「王浩的浩」（`2742276725909073187`）原绑定维护管理员用户 1，910004 没有映射，因此即使生成站内消息也不会推送。已通过 `/demand-api/admin/dingtalk/settings` 开启 `NEW_DEMAND` 工作通知，并把该钉钉账户改绑 910004（`match_source=MANUAL`、`CONFIRMED`）。用维护管理员向 `Dzd7Wxjt` 提交一条测试答卷（`fm_user_form_data.id=211768`，需求 `efea2c02-9511-47df-ba46-b66de886d1a7`），19:17:58 工作通知状态 `SENT`，标题「【瑞玛问卷】有新的需求待处理」、内容「非常满意」、链接到该需求；910004 的站内消息同时生成。该测试需求可按需在待我处理中删除。
- 免打扰：默认 `quiet.start=20:00`、`quiet.end=08:00`；`nextAllowed` 支持 `quiet.bypass.<kind>`，但设置接口 `allowedKey` 目前只允许 `quiet.bypass.APPROVAL`，因此 20:00 后产生的 `NEW_DEMAND` 工作通知会延迟到次日 08:00。白天验收不受影响。
- 通知内容完善（2026-11-26 19:39 部署）：`NEW_DEMAND` 站内消息与钉钉工作通知改为三行——单据（`fm_user_form.name`）、填报人/部门/时间、单号/内容；匿名只显示「填报人：匿名」，不显示姓名和部门。部门取 `rm_demand.department` 的提交时快照，240 当前尚未建部门，因此显示「部门：无」。JAR `604704f8816825bb09c23d5a971cc7c8844c0114450162c843055a44715f111d`、镜像 `sha256:607dae16729fb02df3d4423659afffe32db3be9495bc41f14706200d0be7e62c`、`index.html` `1d4af960a02f3de3106a332f86e79e2edd57a96f8a3949c6ce9405775fb8e77e`，备份 `backups/round-fix-20261126-193648`、回退标签 `ruima/survey-test:pre-20261126-193648`；后端 148 项、前端 92 项回归通过。维护管理员测试答卷 `211769`、需求 `048488fd-e668-4527-97f4-c3c86cfd67ac`，19:40:20 工作通知 `SENT`，内容为三行；该测试需求可按需删除。
- 换行调整（2026-11-26 19:57 部署）：正文拆为四行（单据 / 填报人+部门 / 时间 / 单号+内容），钉钉 action_card 把每行渲染为 Markdown 列表项，避免多行被合并成一长段；站内消息桌面 clamp 4、移动端 5。JAR `1f2188b777f5e08d0aad37e660e5dbeec38f1b5d583da839f89ac61ee47723af`、镜像 `sha256:2cfd74a728419277d0cbc76f73a8a43a4145c639e68464c7b3c0773d517d3be5`、`index.html` `19907867a6ee8a09ab164ce974057327555a92c2c599db82e1edaee43d65a11c`，备份 `backups/round-fix-20261126-195517`、回退标签 `ruima/survey-test:pre-20261126-195517`；后端 148 项通过。维护管理员测试答卷 `211770`、需求 `9c5a6dd2-a451-4019-b235-72798cd93f4e`，19:58:26 工作通知 `SENT`，正文为四行；该测试需求可按需删除。
- 删除最后一行（2026-11-26 20:21 部署）：按用户要求去掉「单号+内容」行，正文固定三行——单据 / 填报人+部门 / 时间；钉钉仍按 Markdown 列表逐行显示，站内消息桌面 clamp 3、移动端 4。JAR `ae614c7b86a67b0fdaed72c042eae7367d418f0e5de107a756e0142ee61e8dee`、镜像 `sha256:57770c74f64762c23a375568806e4d2b6e87d931ea6cc45091bebdd1ed3414b6`、`index.html` 回到 `1d4af960a02f3de3106a332f86e79e2edd57a96f8a3949c6ce9405775fb8e77e`，备份 `backups/round-fix-20261126-201918`、回退标签 `ruima/survey-test:pre-20261126-201918`；后端 148 项、前端多行用例通过。维护管理员测试答卷 `211771`、需求 `e65ac884-6ad5-4f43-8648-730e99267124`，站内正文已核对为三行；因进入 20:00–08:00 免打扰，工作通知为 `PENDING`，计划 08:00 发送，删除该测试需求会同时取消。
- 验收与审批路径时间修复（2026-11-26 21:29 部署）：13 项通知场景（普通成员、多管理员、匿名、部门继承、审批通过/驳回、只收集/作废、回复/分派/追问/结果/公示、无映射、免打扰、必填提醒、消息中心）全部通过，测试需求、部门、账号已清理，`EpA5AG0f` 配置与 ACL 已恢复。验收发现审批通过后补发的 `NEW_DEMAND` 时间比实际早 8 小时（05:01 vs 21:01），原因是审批路径把数据库 `create_time` 当 Timestamp 再做 Asia/Shanghai 换算；改为查询 `CONCAT('', create_time)` 取 `yyyy-MM-dd HH:mm`，提交与审批路径统一。JAR `3b7c643d3966940f1e1d46748cea043ae3e0ee9c4850184e4d5cea44a6fd355d`、镜像 `sha256:a34421a6e6ddc4e40a5711ff544c7ef80bc219dd2a70c7147e4233162f2a83a6`、`index.html` 不变 `1d4af960…`，备份 `backups/round-fix-20261126-212658`、回退标签 `ruima/survey-test:pre-20261126-212658`；后端 148 项通过。复跑 13 项场景全部通过，N6 审批后 `NEW_DEMAND` 时间已为实际提交时间 21:30。
- 钉钉姓名匹配（2026-11-26 22:08 部署，仅后端）：`DemandDingTalkDirectory.sync` 在邮箱缺失/未命中时按姓名唯一匹配，重名双方标 `CONFLICT` 待手工处理；新增 `DemandPushTest` 两项（唯一姓名匹配、重名冲突），后端 150 项通过（DemandWorkflowTest 110、DemandPushTest 13、Oidc 4、AI 23）。240 JAR `70c989d977fe9b805534adaed10c3c3c11beb5b9b6a584845f331f1b4517a93f`、镜像 `sha256:7e79d62b21b06d4a180141e67d4fca11589dcd6455dc04c0fb2d7f6c8389f4fd`、`index.html` 不变 `1d4af960…`，备份 `backups/round-fix-20261126-220621`、回退标签 `ruima/survey-test:pre-20261126-220621`；237 随后部署同 JAR、镜像 `sha256:4a5adcc543c0b80ef5923f5fc541a96ba688416f11c965d72177db42d1a20fee`、回退标签 `ruima/survey:pre-notify-20261126-140911`，同步 100 人 matched=100、pendingConfirm=1、unmatched=0，确认后 confirmed=100（石博文已创建 Casdoor/本地账户并加入市场部）；代码未提交、未推送。
