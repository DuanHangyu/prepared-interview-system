# 部署、验证与回滚手册

> 新版预置面试边界：下文是复制保留的旧模式部署手册，不是 `prepared-interview` 的正式部署方案。其 `prod` profile、旧环境变量、数据库与镜像命名、WebSocket 及旧答辩验证步骤不得直接用于新版；也不要据此连接或修改旧系统。新版独立初始化与本地验证不等于正式部署、备份恢复或百人容量验收。新版运行阶段、独立配置及尚未满足的上线条件以 [预置面试 PRD](features/prepared-interview/PRD.md) 第 9.22、15.4 节和 [PROGRESS](features/prepared-interview/PROGRESS.md) 的 M14 为准；新版部署适配与实际灾备验证完成前不执行本文的旧模式发布／回滚命令。

本手册以仓库根目录的 `compose.yaml` 为准。应用镜像固定基础镜像版本并以非 root 用户运行；根文件系统只读，MySQL/Redis 位于隔离的内部网络，前端通过 Nginx 同源代理 REST 与 WebSocket。

## 部署前准备

1. 创建只允许当前用户读取的配置文件，并替换全部占位值：

   ```bash
   cp .env.compose.example .env.compose
   chmod 600 .env.compose
   ```

2. `PUBLIC_ORIGIN` 必须填写用户真实访问的 HTTPS 源，例如 `https://interview.example.com`，并保持 `AUTH_COOKIE_SECURE=true`。认证 Cookie 使用 `HttpOnly`、`Secure` 和 `SameSite=Strict`；只有纯 HTTP 的本地开发环境才可临时设为 `false`。TLS 应在宿主机反向代理或负载均衡器终止；只允许它访问绑定在 `127.0.0.1` 的前端端口。不要把 MySQL、Redis 或后端端口直接暴露到公网。

   Compose 固定后端使用 `prod` profile，接口文档默认关闭。保持 `SPRINGDOC_ENABLED=false`；若在受控网络内临时开启，排障完成后立即恢复为 `false`。

3. 使用不可复用的 `APP_IMAGE_TAG`，推荐 12 位 Git 提交号。发布前保存同标签的后端和前端镜像到受控镜像仓库；生产回滚不能依赖当前工作区重新构建。

## 发布前数据库备份

首次全新部署可跳过数据备份，但仍要确认库名。已有环境执行：

```bash
mkdir -p backups
BACKUP_FILE="backups/defense-$(date +%Y%m%d-%H%M%S).sql"
docker compose --env-file .env.compose exec -T mysql sh -c \
  'exec mysqldump --single-transaction --routines --triggers -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"' \
  > "$BACKUP_FILE"
test -s "$BACKUP_FILE"
shasum -a 256 "$BACKUP_FILE"
```

把备份文件和校验值复制到容器宿主机之外的受控存储。

## 首次部署或日常发布

1. 解析配置并构建镜像：

   ```bash
   docker compose --env-file .env.compose config --quiet
   docker compose --env-file .env.compose build backend frontend
   ```

2. 独立执行 Flyway。核心操作是 `docker compose run --rm migrate`；实际命令显式加载环境文件：

   ```bash
   docker compose --env-file .env.compose run --rm migrate
   docker compose --env-file .env.compose run --rm migrate validate
   ```

   迁移失败时不要启动新应用。若 V2 因历史重复数据阻断，修复提示的数据后可直接重跑；迁移脚本会先清理上次失败留下的预检过程。

3. 启动服务。`backend` 会等待 `migrate` 成功退出，前端会等待后端健康：

   ```bash
   docker compose --env-file .env.compose up -d --no-build
   ```

4. 验证容器和探针：

   ```bash
   curl --fail http://127.0.0.1:8081/actuator/health/liveness
   curl --fail http://127.0.0.1:8081/actuator/health/readiness
   curl --fail http://127.0.0.1:3004/healthz
   curl --fail http://127.0.0.1:3004/api/actuator/health/readiness
   docker compose --env-file .env.compose ps
   ```

   存活探针只判断应用进程；就绪探针同时检查 MySQL 和 Redis。还要用非生产测试账号完成一次登录并访问受保护接口，验证 Sa-Token 会话和登录限流确实写入 Redis：

   ```bash
   ORIGIN='https://interview.example.com'
   COOKIE_JAR="$(mktemp)"
   chmod 600 "$COOKIE_JAR"
   trap 'rm -f "$COOKIE_JAR"' EXIT
   curl --fail -c "$COOKIE_JAR" -H "Origin: $ORIGIN" \
     -H 'Content-Type: application/json' \
     --data '{"account":"<test-account>","password":"<test-password>"}' \
     http://127.0.0.1:3004/api/login
   curl --fail -b "$COOKIE_JAR" http://127.0.0.1:3004/api/user-detail
   docker compose --env-file .env.compose exec -T redis sh -c \
     'redis-cli --no-auth-warning -a "$REDIS_PASSWORD" DBSIZE'
   ```

   登录后 Redis 键数应大于零，`/user-detail` 应返回测试账号。OSS 签名 URL 的 `@Cached` 策略出于反序列化安全考虑明确使用本地缓存，不能拿它验证 Redis。

5. 验证运行时数据库账号没有 DDL 权限：

   ```bash
   docker compose --env-file .env.compose exec -T mysql sh -c \
     'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "SHOW GRANTS FOR '\''$MYSQL_USER'\''@'\''%'\''"'
   ```

应用日志同时写入受限大小的 Docker 日志和 `backend-logs` 卷；删除失败容器前先导出：

```bash
docker compose --env-file .env.compose logs --no-color backend frontend > deployment-failure.log
```

## 应用回滚

数据库迁移必须采用“扩展—迁移—收缩”方式并保持上一版本兼容。应用健康检查失败、但数据库仍兼容时，只回滚应用镜像，不修改 Flyway 历史。

```bash
PREVIOUS_TAG='<previous-commit>'
docker image inspect "ai-interview-backend:${PREVIOUS_TAG}" >/dev/null
docker image inspect "ai-interview-frontend:${PREVIOUS_TAG}" >/dev/null
APP_IMAGE_TAG="$PREVIOUS_TAG" docker compose --env-file .env.compose \
  up -d --no-deps --no-build backend frontend
curl --fail http://127.0.0.1:8081/actuator/health/readiness
curl --fail http://127.0.0.1:3004/healthz
```

`docker image inspect` 或 `--no-build` 失败就停止回滚，先从镜像仓库拉取确切标签或 digest。禁止从当前工作区重建后冒充旧版本。

## 数据库恢复

只有迁移造成不可兼容的数据破坏、且应用回滚无法恢复服务时，才恢复数据库。以下示例恢复到新库，保留故障现场：

```bash
BACKUP_FILE='<verified-backup.sql>'
test -s "$BACKUP_FILE"
docker compose --env-file .env.compose stop backend
docker compose --env-file .env.compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot -e "CREATE DATABASE IF NOT EXISTS defense_restore CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"'
docker compose --env-file .env.compose exec -T mysql sh -c \
  'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" mysql -uroot defense_restore' < "$BACKUP_FILE"
docker compose --env-file .env.compose exec -T -e MYSQL_DATABASE=defense_restore mysql \
  bash /docker-entrypoint-initdb.d/10-runtime-grants.sh
docker compose --env-file .env.compose run --rm \
  -e 'DB_JDBC_URL=jdbc:mysql://mysql:3306/defense_restore?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=Asia%2FShanghai&allowPublicKeyRetrieval=true' \
  migrate validate
```

复制 `.env.compose` 为 `.env.restore`，把 `MYSQL_DATABASE` 改为 `defense_restore`、`APP_IMAGE_TAG` 改为已验证的上一版本，再执行：

```bash
docker image inspect 'ai-interview-backend:<previous-commit>' >/dev/null
docker compose --env-file .env.restore up -d --no-build backend frontend
curl --fail http://127.0.0.1:8081/actuator/health/readiness
curl --fail http://127.0.0.1:3004/api/actuator/health/readiness
```

核对关键记录数量、登录、预约、答辩和评分读取后再切流。不要在原库手写“反向 ALTER”，不要修改 `flyway_schema_history` 的校验和；这些操作既难以保证数据可逆，也会破坏迁移事实。
