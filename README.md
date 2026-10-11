# Prepared Interview System

这是从原版复制后独立建设的“预置题目面试与可靠录音提交”项目，不修改原版仓库、服务或数据。产品规则和真实完成状态以 [PRD](docs/features/prepared-interview/PRD.md) 与 [PROGRESS](docs/features/prepared-interview/PROGRESS.md) 为准。目前手工备题闭环已完成；真实云端录音、模型、物理设备、百人容量与最终 QA 尚未验收，不能作为正式考试系统使用。

接手开发请先完整阅读 [交接.md](交接.md)，其中记录功能与代码地图、已知阻断、验证边界、环境准备及建议接手顺序。公开仓库：[DuanHangyu/prepared-interview-system](https://github.com/DuanHangyu/prepared-interview-system)。本仓库是未完成开发的交接快照，不是正式发布版本；不包含本机凭据、数据库、私有测试证据及无关商务报价材料。

## 新版本地运行入口

只使用本项目内的 `.env.local`，模板为 `.env.example`。配置文件需仅本人可读；不要复制旧项目密钥或数据库配置。使用 `SPRING_PROFILE=prepared-local`、`PREPARED_DB_JDBC_URL`、`PREPARED_DB_USERNAME`、`PREPARED_DB_PASSWORD` 和 `PREPARED_REDIS_*`。默认是独立 MySQL `127.0.0.1:13317/prepared_interview`、Redis `127.0.0.1:16389`、后端 `13014`、前端 `13015`。

`scripts/start-local-mysql.sh` 仅识别或前台启动**已初始化**的本项目 `.runtime/mysql`，不会初始化空目录、回退旧全局数据目录、创建演示账号或停止任何监听者。目录、进程、端口和数据库身份无法核验时明确拒绝。新库初始化、管理员／运行凭据隔离及完整灾备仍须单独验收，不以此入口代替。

在独立数据库与 Redis 已就绪、配置齐备后，分别运行 `scripts/start-backend.sh` 和 `scripts/start-frontend.sh`。本机前端为 [http://127.0.0.1:13015](http://127.0.0.1:13015)，后端健康检查为 [http://127.0.0.1:13014/actuator/health](http://127.0.0.1:13014/actuator/health)。`restart-local.sh` 在端口被占用时拒绝运行，不自动杀进程；需先核验并显式停止对应新版服务。

付费模型、私有存储、正式学生写入与自动清理默认关闭；不得以旧实时模式或替身替代预置流程验收。本轮模型测试上限 100 元，约 80 元前暂停核账，未核验独立身份与最高成本前不得开启调用。

## 兼容原版参考（不是新版本地运行／部署指南）

以下保留的原版功能、WebSocket、追问、端口、环境变量和部署说明仅供了解复制来源及兼容历史；不得据此启动或接入旧库，也不表示新版已完成这些真实集成。新版入口以本节以上及功能文档为准。

<p align="center">
  <img alt="Vue 3" src="https://img.shields.io/badge/Vue-3-42b883?logo=vue.js&logoColor=white" />
  <img alt="Spring Boot" src="https://img.shields.io/badge/Spring%20Boot-3.5-6db33f?logo=springboot&logoColor=white" />
  <img alt="Java" src="https://img.shields.io/badge/Java-21-f89820?logo=openjdk&logoColor=white" />
  <img alt="MySQL" src="https://img.shields.io/badge/MySQL-8+-4479a1?logo=mysql&logoColor=white" />
  <img alt="Redis" src="https://img.shields.io/badge/Redis-6+-dc382d?logo=redis&logoColor=white" />
  <img alt="License" src="https://img.shields.io/badge/License-MIT-lightgrey" />
</p>

<p align="center">
  <a href="#软件预览">软件预览</a> ·
  <a href="#核心能力">核心能力</a> ·
  <a href="#系统架构">系统架构</a> ·
  <a href="#本地运行">本地运行</a> ·
  <a href="#常见问题">常见问题</a>
</p>

面向高校课程答辩、项目验收和能力测评场景的 AI 面试系统。系统支持教师创建考核、学生在线预约与答辩、实时语音问答、追问生成、PDF 材料上传、自动评分与结果分析，帮助教学团队把分散的答辩流程沉淀为可追踪、可复盘、可量化的数字化工作流。

> 本项目为前后端分离架构，前端位于 `defense-system`，后端位于 `defense-assessment`。

## 软件预览

| 登录页 | 学生端考核列表 |
| --- | --- |
| ![登录页](docs/images/login.png) | ![学生端考核列表](docs/images/student-dashboard.png) |

管理端学生名单截图未随公开交接发布，以避免公开无法确认是否为虚构的姓名与账号信息；管理功能源码保留。

## 核心能力

- **多角色工作台**：支持学生、教师、管理员三类角色，按角色动态加载工作台与权限路由。
- **考核配置管理**：教师可创建答辩主题、设置考核时长、题目数量、评分规则、预约安排和参与学生。
- **学生在线预约**：学生可查看待预约、待完成、评估中、已结束的考核任务。
- **实时语音面试**：通过 WebSocket 接入实时语音能力，支持学生语音输入、题目语音播报和实时答题状态同步。
- **AI 题目与追问**：系统根据考核设置、答辩材料和学生回答动态生成问题与追问。
- **PDF 材料上传与预览**：答辩材料通过对象存储保存，前端可在线预览。
- **自动评分与分析**：面试结束后生成总分、维度得分、答题明细、追问记录和改进建议。
- **过程缓存与恢复**：使用 Redis 缓存实时题目、追问和会话过程数据，降低重复生成和链路抖动影响。

## 技术栈

### 前端

- Vue 3
- Vue Router 4
- Pinia
- Ant Design Vue
- ECharts
- Tailwind CSS
- Axios
- Markdown-it
- PCM Player

### 后端

- Java 21
- Spring Boot 3.5
- Spring Web / WebSocket
- Sa-Token
- MyBatis-Plus
- HikariCP
- Redis / Caffeine（JetCache 本地缓存）
- Aliyun OSS SDK
- OkHttp
- SpringDoc OpenAPI

### 基础设施

- **MySQL**：结构化业务数据，例如用户、考核设置、预约、答题记录和评分结果。
- **Redis**：实时面试过程缓存，例如当前题目、追问、临时会话状态和部分登录态。
- **对象存储 OSS**：保存 PDF、图片、音频等非结构化文件。
- **DashScope / Qwen**：提供文本生成、实时语音、多模态理解等 AI 能力。

## 系统架构

```mermaid
flowchart LR
  student["学生浏览器"] --> frontend["Vue 前端"]
  teacher["教师 / 管理员浏览器"] --> frontend

  frontend --> rest["REST API"]
  frontend --> ws["WebSocket 实时通道"]

  rest --> backend["Spring Boot 后端"]
  ws --> backend

  backend --> mysql[("MySQL")]
  backend --> redis[("Redis")]
  backend --> oss["对象存储 OSS"]
  backend --> qwen["DashScope / Qwen 模型"]

  oss --> preview["PDF / 文件预览"]
  qwen --> realtime["实时语音识别、问答、播报"]
```

## 目录结构

```text
.
├── defense-assessment/        # Spring Boot 后端
│   ├── src/main/java/          # 业务代码、接口、服务、配置
│   └── src/main/resources/     # application 配置
├── defense-system/             # Vue 3 前端
│   ├── src/api/                # 前端 API 封装
│   ├── src/views/              # 页面
│   ├── src/components/         # 通用组件
│   └── src/router/             # 路由与权限
├── scripts/                    # 本地启动脚本
├── docs/images/                # README 截图资源
├── .env.example                # 本地环境变量模板
└── README.md
```

## 本地运行

### 环境要求

- Node.js ^22.22.2 || ^24.15.0 || >=26.0.0
- npm 9+
- Java 21
- MySQL 8+
- Redis 6+
- 可用的 DashScope API Key
- 可用的对象存储服务，例如 Aliyun OSS

### 1. 克隆项目

```bash
git clone <your-repository-url>
cd AI_Interview_system
```

### 2. 配置环境变量

复制环境变量模板：

```bash
cp .env.example .env.local
```

按需填写以下配置：

```bash
SPRING_PROFILE='local'
BACKEND_PORT='8081'
FRONTEND_PORT='3004'
AUTH_COOKIE_SECURE='false'

DASHSCOPE_API_KEY='<your-dashscope-api-key>'
DASHSCOPE_REALTIME_MODEL='qwen3.5-omni-flash-realtime'
DASHSCOPE_REALTIME_VOICE='Ethan'

DB_JDBC_URL='jdbc:mysql://127.0.0.1:13307/defense_test?useUnicode=true&characterEncoding=UTF-8&useSSL=false&serverTimezone=GMT%2B8'
DB_USERNAME='<your-db-user>'
DB_PASSWORD='<your-db-password>'

DB_TUNNEL_ENABLED='true'
DB_TUNNEL_WATCH='true'
DB_TUNNEL_CHECK_INTERVAL='5'
DB_TUNNEL_LOCAL_HOST='127.0.0.1'
DB_TUNNEL_LOCAL_PORT='13307'
DB_TUNNEL_REMOTE_HOST='127.0.0.1'
DB_TUNNEL_REMOTE_PORT='3306'
DB_TUNNEL_SSH_HOST='<your-server-host>'
DB_TUNNEL_SSH_USER='<your-server-user>'
DB_TUNNEL_SSH_PASSWORD='<your-server-password>'

REDIS_HOST='127.0.0.1'
REDIS_PORT='6379'
REDIS_PASSWORD=''

ALIYUN_OSS_ENDPOINT='<your-oss-endpoint>'
ALIYUN_OSS_ACCESS_KEY_ID='<your-oss-access-key-id>'
ALIYUN_OSS_ACCESS_KEY_SECRET='<your-oss-access-key-secret>'
ALIYUN_OSS_BUCKET='<your-oss-bucket>'
```

> `.env.local` 包含 API Key、数据库密码、对象存储密钥等敏感信息，已经被 Git 忽略，请勿提交。

### 3. 准备数据库与 Redis

请准备一个 MySQL 数据库，例如：

```sql
CREATE DATABASE defense_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

后端启动时会自动执行 `defense-assessment/src/main/resources/db/migration` 下的 Flyway 迁移。空库会从基线创建全部表；已有且尚未接入 Flyway 的数据库会标记为版本 1，再执行后续完整性升级。升级前请备份数据库；如果历史数据存在重复业务键，迁移会停止并保留原始数据，需先人工核对和修复。

本地演示数据可在首次迁移成功后手动执行 `scripts/local-db-seed.sql`。仓库不内置生产数据，也不要在生产环境加载演示账号。

Redis 默认连接：

```text
redis://127.0.0.1:6379/0
```

如需密码或远程 Redis，请设置 `REDIS_HOST`、`REDIS_PORT` 和 `REDIS_PASSWORD`。

### 4. 启动后端

```bash
./scripts/start-backend.sh
```

后端默认地址：

```text
http://localhost:8081
```

OpenAPI 文档地址：

```text
http://localhost:8081/swagger-ui.html
```

本地 profile 默认提供接口文档；Compose 使用独立的 `prod` profile，并默认设置
`SPRINGDOC_ENABLED=false`，避免生产环境公开接口结构。如确需临时排障，只应在受控网络内显式开启并在完成后立即关闭。

### 5. 启动前端

```bash
npm --prefix defense-system install
./scripts/start-frontend.sh
```

前端默认地址：

```text
http://localhost:3004
```

### 6. 一键重启

本地开发时可以使用：

```bash
./scripts/restart-local.sh
```

脚本会读取根目录 `.env.local`，并启动前端与后端服务。不要直接在 `defense-assessment` 目录里手动运行 `./mvnw spring-boot:run`，除非已经显式加载 `.env.local`；否则后端可能缺少 `DB_PASSWORD` 等环境变量，表现为端口可用但登录接口返回数据库连接失败。

## 配置说明

### 登录会话

浏览器登录态由后端通过 `HttpOnly` Cookie 管理，前端不会读取或保存会话令牌。本地 HTTP 开发使用 `AUTH_COOKIE_SECURE=false`；生产 HTTPS 部署必须使用 `AUTH_COOKIE_SECURE=true`，并将 `PUBLIC_ORIGIN` 配置为实际访问源，以同时保护 REST 写请求和 WebSocket 握手。

### AI 模型

后端通过 `dashscope.*` 配置接入模型服务：

| 配置项 | 说明 |
| --- | --- |
| `DASHSCOPE_API_KEY` | DashScope API Key |
| `DASHSCOPE_REALTIME_ENABLED` | 是否启用实时语音链路 |
| `DASHSCOPE_REALTIME_URL` | 实时模型 WebSocket 地址 |
| `DASHSCOPE_REALTIME_MODEL` | 实时语音模型，默认 `qwen3.5-omni-flash-realtime` |
| `DASHSCOPE_REALTIME_VOICE` | 题目播报音色 |
| `DASHSCOPE_REALTIME_MAX_EVENT_CHARS` | 单条上游实时事件字符上限，默认 1 MiB |
| `DASHSCOPE_REALTIME_MAX_TRANSCRIPT_CHARS` | 单轮输入或输出转写字符上限，默认 20000 |
| `DASHSCOPE_REALTIME_MAX_RESPONSE_AUDIO_BYTES` | 单轮上游响应音频累计上限，默认 16 MiB |
| `FILE_DOWNLOAD_MAX_BYTES` | OSS 文件单次下载上限，默认 70 MiB；未知长度响应也会流式计数并拒绝超限内容 |
| `DASHSCOPE_ENABLE_THINKING` | 是否启用模型思考能力 |

### 数据库

本项目使用 MySQL 保存长期业务数据，后端通过 HikariCP 管理数据库连接池。

| 配置项 | 说明 |
| --- | --- |
| `DB_JDBC_URL` | MySQL JDBC 连接地址 |
| `DB_USERNAME` | 数据库用户名 |
| `DB_PASSWORD` | 数据库密码 |

本地开发如果需要通过 SSH Tunnel 访问远程 MySQL，可启用以下配置。当前本地开发推荐让 `DB_JDBC_URL` 指向 `127.0.0.1:13307`，由脚本负责把该端口转发到服务器 MySQL：

```bash
DB_TUNNEL_ENABLED='true'
DB_TUNNEL_WATCH='true'
DB_TUNNEL_CHECK_INTERVAL='5'
DB_TUNNEL_LOCAL_HOST='127.0.0.1'
DB_TUNNEL_LOCAL_PORT='13307'
DB_TUNNEL_REMOTE_HOST='127.0.0.1'
DB_TUNNEL_REMOTE_PORT='3306'
DB_TUNNEL_SSH_HOST='<your-server-host>'
DB_TUNNEL_SSH_USER='<your-server-user>'
DB_TUNNEL_SSH_PASSWORD='<your-server-password>'
```

生产部署不建议依赖本地 SSH Tunnel。推荐将后端部署到与 MySQL 相同的私有网络，使用内网地址连接数据库。

本地 `local` profile 会要求 `DB_USERNAME` 和 `DB_PASSWORD` 必须存在；如果缺失，后端应当启动失败，而不是等到登录页才抛数据库连接错误。

### Redis

Redis 用于缓存实时面试过程数据、模型生成结果和部分会话状态。相关代码主要位于：

- `StudentAssessmentService`
- `RedisConstants`
- `CacheConfig`

### 对象存储

系统通过 OSS 保存 PDF 材料和其他文件。数据库中通常只保存文件路径或 URL，实际文件由对象存储承载。

| 配置项 | 说明 |
| --- | --- |
| `ALIYUN_OSS_ENDPOINT` | OSS Endpoint |
| `ALIYUN_OSS_ACCESS_KEY_ID` | Access Key ID |
| `ALIYUN_OSS_ACCESS_KEY_SECRET` | Access Key Secret |
| `ALIYUN_OSS_BUCKET` | Bucket 名称 |

### 隐私与数据留存

控制器日志只记录方法名、参数类型、返回类型和耗时，不记录请求/响应正文。模型消费审计只保存模型名、字符数、音频字节数、Token 数和耗时，不保存提示词、学生原文、模型原文或音频内容。

后台每天默认在 03:30 执行分批清理，并使用 MySQL 互斥锁保证多实例只运行一次：

| 配置项 | 默认值 | 清理责任 |
| --- | --- | --- |
| `TOKEN_AUDIT_RETENTION` | `P30D` | 删除过期模型消费审计 |
| `INTERVIEW_MEDIA_RETENTION` | `P180D` | 删除已完成面试的 PDF/语音对象并清空数据库引用 |
| `DELETED_ASSESSMENT_MATERIAL_RETENTION` | `P30D` | 删除考核后保留材料 30 天，再删除 OSS 对象 |
| `BUSINESS_RECORD_RETENTION` | `P730D` | 业务结果保留期，仅在下项开启后生效 |
| `PURGE_BUSINESS_RECORDS` | `false` | 显式开启后才永久删除过期答题与评分记录 |
| `RETENTION_CLEANUP_BATCH_SIZE` | `100` | 单批处理数量 |
| `RETENTION_MAX_BATCHES_PER_RUN` | `10` | 单次任务最大批数 |

`DATA_RETENTION_ENABLED=false` 可暂停自动清理。删除学生或考核时，相关答辩文件和语音会在删除数据库记录前同步清理；OSS 临时失败会阻止引用被移除，下一次可以安全重试。日志默认保留 14 天，单文件 50MB，总量上限 1GB，可由 `LOG_MAX_FILE_SIZE`、`LOG_MAX_HISTORY_DAYS` 和 `LOG_TOTAL_SIZE_CAP` 调整。

## 面试流程

```mermaid
sequenceDiagram
  participant T as 教师
  participant S as 学生
  participant FE as 前端
  participant BE as 后端
  participant AI as Qwen 模型
  participant DB as MySQL
  participant R as Redis
  participant OSS as OSS

  T->>BE: 创建考核与评分规则
  BE->>DB: 保存考核配置
  S->>FE: 预约并进入面试
  FE->>BE: 获取考核详情
  S->>OSS: 上传 PDF 材料
  BE->>AI: 生成题目 / 追问
  BE->>R: 缓存实时题目与追问
  FE->>BE: WebSocket 传输语音与答题事件
  BE->>AI: 实时转写、理解与评分
  BE->>DB: 保存答题记录和评分结果
  FE->>S: 展示结果分析
```

## 常见问题

### 1. 登录后接口提示数据库连接失败

优先检查：

```bash
nc -vz -w 3 <mysql-host> <mysql-port>
```

如果本地通过 SSH Tunnel 连接远程 MySQL，请确认隧道进程存在、`DB_JDBC_URL` 指向本地转发端口，并且后端是在最新环境变量下启动。

### 2. Redis 连接失败

检查 Redis 是否启动：

```bash
redis-cli ping
```

期望返回：

```text
PONG
```

如果使用远程 Redis，请检查 `REDIS_HOST`、`REDIS_PORT` 和 `REDIS_PASSWORD`。

### 3. PDF 无法上传或预览

检查 OSS 配置是否完整，并确认 Bucket 权限、跨域配置、预签名 URL 有效期与服务端时间一致。

### 4. 实时语音不可用

请确认：

- 浏览器允许麦克风权限。
- WebSocket 地址配置正确。
- DashScope API Key 有实时模型调用权限。
- 所选模型所在地域与 API Key 地域一致。

## 开发建议

- 不要提交 `.env.local`、数据库密码、API Key、OSS 密钥或 SSH 密码。
- 面试实时链路建议部署在稳定服务器环境，不要让正式学生流程依赖本地 SSH Tunnel。
- 发布数据库结构变更时必须新增 Flyway 增量迁移，并在全新库与已有库两种路径上验证。
- 生产环境建议将前端静态资源部署到 CDN，并将后端、MySQL、Redis 放在同一私有网络或低延迟网络内。
- 日志中避免打印学生语音原文、密码、Token、API Key 等敏感信息。

## 许可证

本项目基于 MIT License 开源，详见 [LICENSE](LICENSE)。
