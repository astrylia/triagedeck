# TriageDeck 项目计划

> 面向 B2B SaaS 公司的客户支持工单系统。目标：一个能放进简历、能现场演示、能讲清楚设计取舍的作品。

## 1. 一句话定位

一家 SaaS 公司（租户）用 TriageDeck 接收它的客户提交的问题，客服团队按优先级和 SLA 分派、处理、关闭工单。

## 2. MVP 范围

| 模块 | 内容 | 面试时能讲的点 |
|---|---|---|
| 多租户组织 | 每个组织数据完全隔离；用户可属于多个组织 | 租户隔离方案（`orgId` 行级隔离 vs. Postgres RLS） |
| 角色权限 | Owner / Admin / Agent / Customer 四种角色 | RBAC 设计、服务端统一鉴权 |
| 工单 | 标题、描述、状态、优先级、标签、请求人、处理人 | 数据建模、索引 |
| 状态流转 | Open → In Progress → Waiting on Customer → Resolved → Closed | 状态机，非法流转在服务端拒绝 |
| 优先级 | Low / Medium / High / Urgent | 与 SLA 挂钩 |
| SLA | 按优先级设定首次响应时间、解决时间；显示倒计时与"已违约" | 时间计算、"等待客户"时暂停计时、后台任务 |
| 分派 | 手动分派 + 简单自动分派（轮询或最少负载） | 并发安全（事务/行锁） |
| 评论 | 公开回复 vs. 内部备注（客户看不到） | 字段级权限 |
| 活动日志 | 谁在什么时候改了什么 | 审计日志、事件表 |
| 列表与筛选 | 按状态/优先级/处理人/SLA 风险筛选、搜索、分页 | 游标分页、查询优化 |

**MVP 不做**：邮件收件、实时聊天、计费、AI 分类（放到后面的加分阶段）。

## 3. 技术栈（Java 后端；前端交给 AI 编码工具）

| 层 | 选择 | 理由 |
|---|---|---|
| 语言 / 框架 | Java 21 (LTS) + Spring Boot 4.1 | 新项目选当前仍有免费维护的最新版本（4.1 的 OSS 支持到 2027-07）；本机已装 JDK 21，也是目前企业里用得最多的 LTS，record、虚拟线程可以作为亮点 |
| 构建 | Maven + Maven Wrapper（mvnw） | 本机不用单独装 Maven，CI 和本地用同一个版本 |
| 数据库 | PostgreSQL 18 | 事务、索引、行锁都是面试重点 |
| 持久层 | Spring Data JPA（Hibernate） | 最常见；能讲 N+1、懒加载、乐观锁 |
| 迁移 | Flyway | 数据库结构有版本，可重复部署 |
| 认证 / 权限 | Spring Security + JWT（access + refresh） | 自己配一遍过滤器链和方法级权限，面试必问 |
| 校验 | Jakarta Bean Validation + 全局异常处理（`@RestControllerAdvice`，RFC 9457 错误格式） | 统一、规范的错误响应 |
| API 文档 | springdoc-openapi（Swagger UI） | 前端 AI 工具直接按它生成客户端 |
| 异步 / 定时 | Spring `@Scheduled` 起步，之后升级为 Redis + 延迟队列或 RabbitMQ | SLA 违约检测、通知发送 |
| 缓存 | Redis | 热点数据、限流 |
| 测试 | JUnit 5 + Mockito（单元），Testcontainers（真实 Postgres 集成测试），MockMvc | 后端岗位非常看重 |
| 可观测性 | Spring Boot Actuator、结构化日志 | 健康检查、指标 |
| 本地环境 | Docker Compose（Postgres + Redis + Mailpit 收开发邮件） | 一条命令启动依赖 |
| CI/CD | GitHub Actions（构建 + 测试）；Docker 镜像部署到 Railway / Fly.io / 云服务器 | 有线上演示链接 |
| 前端 | 由你用 Claude Code / Codex 根据 OpenAPI 生成 | 带教只覆盖后端 |

分层结构：`controller → service → repository`，按业务模块分包（`org`、`auth`、`ticket`、`sla`、`comment`），DTO 和实体分离。

## 4. 数据模型草图

```
Organization (id, name, slug)
User (id, email, name)
Membership (userId, orgId, role)            -- 多租户 + 角色
Ticket (id, orgId, number, title, description,
        status, priority, requesterId, assigneeId,
        firstResponseDueAt, resolutionDueAt,
        firstRespondedAt, resolvedAt, createdAt, updatedAt)
Comment (id, ticketId, authorId, body, isInternal, createdAt)
SlaPolicy (id, orgId, priority, firstResponseMins, resolutionMins)
TicketEvent (id, ticketId, actorId, type, payload JSON, createdAt)  -- 审计
Tag / TicketTag
```

## 5. 分阶段路线图

每个阶段结束都是一个可以写进简历、可以演示的里程碑。

### 阶段 1：地基（项目骨架 + 认证 + 多租户）✅ 2026-10-10 完成，认证部分不再加功能
- GitHub 仓库，Spring Boot 4.1 + Flyway + Docker Compose（Postgres、Redis、Mailpit）
- Spotless 统一代码格式，GitHub Actions 跑构建和测试
- 登录：没有密码。填邮箱，系统发一次性登录链接（15 分钟有效），点开就登录；邮箱还没有账号时第一次登录自动建一个，所以注册和登录是同一个流程，每个账号的邮箱都验证过
- 登录后：JWT access token（15 分钟）+ refresh token（14 天，每次使用都轮换，重复使用视为被盗、吊销该用户全部 refresh token）
- 限流（Redis，最初计划放到阶段 4，实际在阶段 1 就加了）：按 IP 限制发登录链接的次数，同一个邮箱 60 秒内只发一封
- 创建组织；邀请成员：系统把一次性邀请链接发到被邀请人的邮箱（7 天有效，数据库只存 token 的 SHA-256），打开链接就加入组织并登录，没有账号时自动建一个
- 租户隔离：组织 id 在 URL 里，Service 层显式调用 `requireMembership` / `requireRole`（不是成员 404，角色不够 403），见 `docs/adr/0002`。最初计划的 `@PreAuthorize` + `TenantContext` 没有采用
- `TenantIsolationTest`：另一个组织的 OWNER 调用每个组织下的接口都得到 404；新增 `{orgId}` 接口没登记进测试会让 CI 失败
- 统一错误格式：RFC 9457 ProblemDetail + `code`，包括 Spring Security 的 401
- Swagger 文档自动生成（`/swagger-ui.html`）
- **里程碑**：API 能登录续期、建组织、邀请成员、切换组织（`GET /api/orgs` + URL 里的 orgId），58 个测试，CI 是绿的
- **暂不做**：二次验证、第三方登录（GitHub/Google）。用邮件链接登录后没有密码可猜，也没有密码可泄露，之前担心的猜密码、撞库都不存在了；代价是账号安全取决于用户邮箱的安全

### 阶段 2：工单核心
- 工单 CRUD、状态机、优先级、标签
- 工单列表：筛选、搜索、分页；工单详情页
- 评论（公开/内部）、活动日志
- **里程碑**：一个完整可用的工单流程

### 阶段 3：分派与 SLA
- 手动分派、自动分派（最少负载），事务保证不重复分派
- SLA 策略配置、截止时间计算、"等待客户"时暂停
- 定时任务 / 延迟队列在截止时间检测违约，发事件；列表上显示倒计时和风险标识
- **里程碑**：后台能看出"哪些工单快违约了"

### 阶段 4：质量与上线
- 补齐单元测试（状态机、SLA 计算、权限）和 API 集成测试
- 前端交给 AI 编码工具，基于 OpenAPI 文档生成（工单列表、详情、分派）
- 结构化日志、Actuator 健康检查（Redis 限流已在阶段 1 完成）
- 部署 API 和前端，托管 Postgres + Redis，准备种子演示数据
- README：架构图、截图、设计取舍、演示账号
- **里程碑**：有线上链接和测试覆盖的作品，可以直接放简历

### 阶段 5（加分，选做）
- 客户门户（客户只看自己的工单）
- 仪表盘：工单量、平均响应时间、SLA 达成率
- 邮件通知 / 实时更新（SSE 或 WebSocket）
- 用 Claude API 做工单自动分类和回复建议
- Postgres RLS 做数据库层租户隔离

## 6. 带教方式

- 代码由 Claude 写（2026-10-08 起），每次交付说明改了哪些文件、各自做什么、为什么这样设计；你决定读多深
- 每个阶段结束一起做一次代码回顾，并整理一段可以写进简历的描述和面试问答
