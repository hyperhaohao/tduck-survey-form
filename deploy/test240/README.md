# 240 独立测试实例部署说明

**瑞玛需求征集已在 http://192.168.1.240:18000 部署，沿用原填鸭界面风格，默认登录填写、可选匿名。** 本目录保留无凭据部署模板；实际配置由服务器受限运行目录维护，不复制到仓库。

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
- 真实 Key 联调（2026-11-15 23:40）：管理员配置的内网 New API 网关 Base URL 原为 `http://192.168.1.236:3000//v1`（多一个斜杠），网关返回 HTML，测试连接解析失败、流式请求超时；保留 Key 不变，仅把 Base URL 规范为 `/v1` 并保存即时生效。测试连接 453ms 成功；创建流程澄清无需提问、方案 5 题、生成 5 题；设计器合成表单方案 3 题、确认生成并应用成功，最终题目为多选“您常用的沟通方式”、多行文本“您对远程办公的建议”、数字“每周远程办公天数”，未改动的多行文本保留原 `formItemId`；合成表单与题目按 ID 清理，剩余 0 行。
- 网关当前只提供 `deepseek-flash`、`deepseek-v4-flash` 等模型，没有独立 Pro/Reasoner；深入思考开关使用配置的 `reasoningModelName`，当前与普通模型相同，不会切换到更强模型；网关返回的 `reasoning_content` 仍会在面板展示。若需更强推理，请在网关新增模型后修改 AI 配置。
- 方案卡出现时自动滚动到卡片顶部，保证「确认并生成」可见；该滚动修复已静态替换，最终 `index.html` SHA256 `4fd3cbca60190cb1d75ba7911e4669524cd572dd658601599a1ef8c63835beb0`，备份 `backups/ai-static-scroll-20261115-pre/`，未重启应用、未改 Nginx。尚未推送 GitHub。
