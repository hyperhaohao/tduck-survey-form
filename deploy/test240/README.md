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
