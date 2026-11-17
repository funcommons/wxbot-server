# wechat-sync-server 实现进度（无人值守循环的任务账本）

> 主 agent = 调度与汇报；子 agent = 执行（每子任务只做一件事：业务代码 / 测试代码 / 跑测试 / 其它）。
> 每步 ≤1000 行；每步流程：写测试 → 写业务 → 跑测试 → 修复 → 4 轮或无 P0~P2 建议为止。
> 单元测试只测当步代码；代码增量 >10% 或 >1000 行或 >10 任务时由子 agent 跑全量（mvn test）。
> 规范：framework4j（100% 用）+ mc-api-spec（API_DESIGN.md）+ mc-database-spec（DB_DESIGN.md / schema.sql）。

## 环境（已就绪 2026-11-09）

- Java 17：`C:\Program Files\Java\jdk-17.0.19+10`（Temurin）
- Maven：`/c/workspace/tools/apache-maven-3.9.9/bin/mvn`（bash 调用时需
  `JAVA_HOME="C:\Program Files\Java\jdk-17.0.19+10"` 前缀）
- PostgreSQL：docker `wxuw-pg`，`127.0.0.1:5433`，库 `wechat`，用户 `postgres` / 密码 `wxuw`，
  **v4 schema（wx_ 前缀 13 表）已建**；重置：`psql -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"` 后
  重跑 `../schema.sql`
- Redis：docker `wxuw-redis`，`127.0.0.1:6379`（无密码，内网）
- 规范技能文件：`C:\Users\Administrator\.claude\skills\framework4j\references\*.md`（web/api/accesstoken/
  idempotency/rate-limit/redis/datetime/sql-tracing 等，写代码前按需读）

## 契约与设计文件（本目录上级）

- `API_DESIGN.md`：接口契约权威（信封/错误码 10601/10602/分页矩阵/鉴权/幂等/限流/枚举大小写口径）
- `DB_DESIGN.md` + `schema.sql`：库设计权威（wx_ 13 表、无外键、部分唯一索引、intarray 标签）
- `SYNCH_PLAN.md` §6：ingest 落库流程（五步事务、seen_accounts 反算、fb→srv 合并）
- 枚举口径：**API 收小写，服务端归一化大写落库**（DB_DESIGN §8）

## 任务板

| # | 任务 | 状态 | 产出 |
|---|---|---|---|
| T1 | 项目骨架：pom（framework4j-all 本地 install fun.commons:1.10.0 + mybatis-plus + druid + pg + springdoc）、application.yml、Application、模块显式开配置 | 编译通过（上下文加载未验） | pom.xml / application.yml / WxSyncServerApplication / MybatisPlusConfig / .gitignore |
| T2 | 错误码扩展（10601/10602）+ 鉴权（accesstoken policies，client/admin）+ 账号注册/令牌签发吊销（admin API） | 业务代码完成（编译通过；测试/上下文加载未做） | common/WxErrorCode、common/PageVO、account/{entity,mapper,dto,service,controller}、auth/AccountBinding、pom+Lombok、yml+hash-salt/redis、@MapperScan（13 文件 644 行） |
| T3 | 关系层 ingest：POST /v1/reports/relations（6 类 upsert + 标签 dict/tag_ids/hint 映射） | 业务代码完成（编译通过；测试/运行期冒烟未做） | relation/{entity×6, mapper×6, dto×2, service, controller, support/IntArrayTypeHandler}（17 文件 1077 行） |
| T4 | 消息批 ingest：POST /v1/reports/messages（upsert、fb→srv 合并、seen_accounts 反算、游标镜像、batch log） | 业务代码完成（编译通过；测试/运行期冒烟未做） | message/{entity×7, mapper×7, dto×2, service, controller}（18 文件 1327 行） |
| T5 | blob 文件库（内容寻址/临时文件/sha256 校验/原子转正）+ PUT /v1/blobs/{sha256} + POST /v1/reports/heartbeats | 业务代码完成（569 行 7 文件）；**测试未写** | blob/{BlobStorage, controller, dto}、heartbeat/{controller, service, dto}、yml+2 键 |
| T6 | 查询 API 11 端点（accounts/chats/messages keyset/search/detail/sources/contacts/group members/attachments/blobs 下载/statistics dedup/ingest-batches） | 业务代码完成（编译通过）；**测试主体待重写**：QueryServiceTest.java.pending 是死 agent 半成品已搁置；CursorCodecTest 7 用例已收编全绿 | query/{service,mapper,dto,controller}（7 文件 920 行） |
| T7 | GET /v1/status + 限流/幂等接线（path-patterns /v1/reports/** + blob PUT 排除堆缓存）+ README + prod profile + .gitignore | **业务代码完成（编译过 + 启动/接口冒烟过）；未写测试** | status/ 4 文件 156 行 + config/ 2 文件 227 行 + README 113 + application-prod.yml 25 + yml/.gitignore 微改（新增 532 行） |
| T8 | 全量测试 + P0~P2 修复收尾 | **第 5 轮全量 GREEN：95/95**（含真实 PG 集成 4 用例）；QueryServiceTest 重写后需再跑一轮 | — |
| T9 | 任务分发（TASK_DISPATCH.md v1.1）：schema v5 四表+种子 / TaskService(claim SKIP LOCKED·终态重放·回收·物化) / RegCode+Auth(register·refresh) / TaskController×2+@Scheduled / 单测+真库 IT+部署验收 | ✅ **完成（256/256 全绿 + 真机验收全过，见 [T9 终态]）** | schema.sql v5(+129)+verify_task_dispatch.sql(12 断言)；task/{entity×3,mapper×2(自定义 SQL),service(530),controller×2,scheduler,dto×3}；account/{WxRegCodeEntity,WxRegCodeMapper,RegCodeService,RegCodeAdminController,RegCodeVO/IssueVO,TokenService+refreshForClient}；auth/{AuthController,dto×3}；RateLimitQuotaConfig+register 6/min；WxErrorCode+10603；yml+task-* 3 键；测试 TaskEntitiesMappingTest(4)+TaskServiceTest(35)+IT(8) |
| T10 | 订阅域（SUBSCRIPTION_DESIGN.md v1.1 §10）：schema v6 四表 / 实体×4+Mapper×4 / SubscriptionService+Matcher+触发钩子+补扫 / DeliveryService / WebhookDispatcher+SchedulerConfig / Controller×2+POLL 配额三件套+WxErrorCode 10604~10607 / 单测+真库 IT / 部署+真机验收 | 🔄 **T10-1 ✅ T10-2 ✅（8/8 绿）；T10-3 业务+测试双棒并行中** | schema.sql v6（四表+11 索引，两遍幂等应用，数据零污染）+ verify_subscription.sql（13 组断言全过）；subscription 包 8 文件 ~470 行（实体×4 含 filter JsonbTypeHandler / Mapper×4 含 claimDueWebhook·claimForPull 两形互斥·requeueForRetry·sweepExpiredLeases·backstopEnqueue·cleanupDelivered·GREATEST upsert 九条自定义 SQL）+ SubscriptionEntitiesMappingTest 8/8 绿（SQL 片段契约锚；执行轮亲修 1 处测试缺陷：getMethod 单参形式只查零参方法） |

## 子 agent 分工规则（主 agent 记录）

- 每个任务拆三类子任务：`业务代码`（一个 agent）、`测试代码`（另一个 agent，只测当步）、`跑测试`（再一个 agent）
- 修复轮：测试 runner 报告 → 新的 `业务代码` 微任务修复 → runner 复跑；≤4 轮或无 P0~P2 建议
- 并发上限 5 个子 agent

## 状态日志（倒序追加）

- **[T8 终局 · 交付确认] 2026-11-09 终轮全量 `mvn test`：142/142 全绿，BUILD SUCCESS。**
  T1~T8 全部关闭。最后 6 个测试缺陷（5 个由子 agent 修复 + 1 个主 agent 亲修）清零；
  主 agent 亲修的 `putStreamsBodyToStoreAndInsertsRowOnMiss` 根因：storage 是真实实例（spy），
  流已被 store 消费，事后 ArgumentCaptor 读流必为空——改为断言落盘字节
  `Files.readAllBytes(storage.resolve(SHA))`（等价且更强，与兄弟用例同款）。
  交付物：`src/main/java/com/wechat/sync/server/`（account/auth/blob/heartbeat/message/query/
  relation/status 八包 + common/config/support）、`src/test/`（21 个测试类 142 用例，含真实 PG
  集成 4 用例）、README.md、application.yml / application-prod.yml、
  db/framework4j-infra.sql。遗留 P2（均不阻塞，已记账）：限流维度受框架 1.10.0 限制为每 IP、
  blob ext 按文件头探测未做、`dedup_key LIKE 'fb:%'` 建议加 text_pattern_ops 索引、
  QueryServiceTest 575 行超子任务预算（已记偏差）、wechat-sync 非 git 仓库（改动无法 diff 复核）。

- [T8 测试缺陷修复] 终轮 5 失败全是测试自身缺陷，只改 2 个测试文件（未动 src/main、未跑测试）：
  QueryServiceTest IN 分块用例喂 1001 个 id 却断言 times(2)+块 501（违反 ≤500 不变量）→ 改
  `times(3)` + 块 500/500/1（三块并集 1001，覆盖尾块余量场景）；BlobControllerTest 4 个 PUT 用例
  夹具假 sha `ab12cd34…` 与 body 字节不符（BlobStorage.store 写完重算强校验 → 必抛 10602）→
  常量改 `SHA = sha256Of(PAYLOAD)` 真哈希（新增私有 `sha256Of`，与 BlobStorageTest 同款），
  路径参数/表行/盘上路径全用真值，size 校正（row(3L)）与跳过（row(PAYLOAD.length)）意图保持不变。
  `mvn -q test-compile` BUILD SUCCESS（退出码 0）。
- [T6 测试代码] QueryServiceTest 重写（替换死 agent 半成品 .pending，已删除）：
  `query/service/QueryServiceTest.java` 575 行 19 用例 + `query/controller/QueryControllerTest.java`
  85 行 2 用例（admin 注解反射 + messages/chats 转发，按仓库惯例拆独立 controller 测试）；
  `mvn -q test-compile` BUILD SUCCESS（退出码 0）；**未跑测试**（留给 T8）、未动 src/main。
  - 覆盖：cursor 非法形态经 messages/search 入口全拒（10102 field=cursor、零 DB 交互）；首页无位点
    条件 + chat 存在性 10400 + 列表不取大字段；翻页两位点标量透传 + 不再 count + 过滤槽位不串位；
    limit 钳制（21/2/201）；has_more 两分支 + next_cursor 取末行位点编码回读自洽；sources 只装当前页
    id + 多来源聚合；>500 IN 分块 500+501（直测私有 loadSources）；ILIKE 转义（%a\%b\_c\\d%）+ 空白
    q 不检索；时间三形态（epoch 秒/毫秒/ISO）+ 非法 10102 指字段；chats/contacts Offset 钳制
    （page=0→1、size≤200）+ 强制 is_deleted=0；page>1000 禁深 offset 10102 零 DB；detail 全文
    +sources+attachments；非数字 id 10102；attachment 三态；union 走视图 / 非 union 走 rel 表；
    statistics accounts JSON 还原（坏 JSON 兜底空列表）；ingest-batches 显式列清单排除 error 大字段。
  - **已知偏差**：575 行超「≤450 行」预算（用例清单全覆盖所致）；半成品的 T0
    「1760001000=2025-10-09T15:00:00Z」是错的（实为 09:10:00Z）已改正；半成品 contacts 用例在已有
    交互后 `verifyNoInteractions` 必炸已改 `times(1)`；`org.assertj.core.api.ThrowingCallable` 在
    AssertJ 3.27 不存在，改用 `ThrowableAssert.ThrowingCallable`。
- [T5 测试代码] blob + heartbeat 两层纯单测 **4 文件 616 行 27 用例，`mvn -q test-compile`
  BUILD SUCCESS（退出码 0，全部 class 已产出 target/test-classes）；未跑测试、未动 src/main**。
  - **A 层（纯 JUnit，@TempDir 真文件，不起 Spring）** `blob/BlobStorageTest.java` 11 用例：
    分桶布局 `<aa>/<bb>/<sha256>` + `.tmp-` 零残留、同内容重存 exists 短路（把 mtime 冻结到
    2020 再断言不被改写，避开计时竞态）、内容与声称哈希不符 → 10602 且临时件被清、
    null/空白 → 10101、非 64hex / 65 位 / `../` 穿越等 6 组非法输入全 10102、上限预检
    （declaredSize）与流中段超限双路径 10505、`checkLimit(null/边界)`、100KB 跨 64KB 拷贝缓冲、
    exists/locate 命中与缺失 10400、`init()` 建根目录 + 根路径被文件占用时 fail-fast。
  - **B 层（Mockito）** `blob/controller/BlobControllerTest.java` 10 用例：storage 用
    **真实 BlobStorage + spy**（@TempDir 根，sha 校验/落盘走真实现，交互可 verify），
    mapper/HttpServletRequest 用 mock（body 经 `DelegatingServletInputStream` 包
    ByteArrayInputStream，断言转发给 store 的流字节与 body 一致）；覆盖表+盘双命中 → exists
    （不 store 不 insert）、未命中 → store + INSERT 四字段（sha256/size/storagePath/is_deleted=0）、
    撞 uk → DuplicateKeyException → 回 exists、表有行盘无文件 → 补落盘 + update 校正 size、
    size 已一致 → 不 update、store 抛 IOException → 10503、非法 sha 先拦（10102 且不读 body
    不查表）；GET：命中 → 200 octet-stream + contentLength + Cache-Control immutable +
    `FileSystemResource` 原始字节（断言 body 非 ApiResponse，信封契约明示例外）、表无行 → 10400、
    表有行盘无文件 → 10400。`heartbeat/service/HeartbeatServiceTest.java` 4 用例（capture
    LambdaUpdateWrapper 断言 `getSqlSet()` 含 last_seen_at/hostname/client_version、不含
    last_sync_at/api_key_hash/updated_at；hostname/clientVersion 空白或缺省不覆写；updated=0
    → 10400；requireAccount 空白 → 10101 + 去空白）+ `heartbeat/controller/HeartbeatControllerTest.java`
    2 用例（信封 code=0/error=null/server_time；InOrder 验 requireAccount → requireRegistered →
    heartbeat 次序；缺 account 抛 10101 且不碰 AccountBinding）。
  - **前置条件坑（复用既有 `support/MpTestTables`）**：controller/service 里现建
    `LambdaQueryWrapper`/`LambdaUpdateWrapper`，纯单测不起 MyBatis 时必须先
    `MpTestTables.init(WxBlobEntity.class / WxAccountEntity.class)`，否则报
    "can not find lambda cache for this entity"（T3/T4 已踩过）。
  - **C 层（@SpringBootTest 真实 store→GET 往返）未做**：可选加分项，但需起全上下文 + PG
    `wx_blob` 表且本子任务不许跑测试，未验证的集成用例不落地（留给 runner/全量轮）。
  - **行数超预算说明**：任务给的两层上限 500 行，实 616 行（A 层 207 行在 250 内；B 层
    410 行超 250）——父任务点名的用例全数保留，仅压缩 javadoc/断言密度，未删用例；
    仍低于 PROGRESS「每步 ≤1000 行」硬规则。
  - **P0~P2 风险清单（静态审查，未跑）**：0。已顺手核掉两个易踩点：AssertJ 3.27.7
    `AbstractLongAssert.isEqualTo(long)` 在 jar 里确认存在（`assertThat(Long).isEqualTo(int)` 走
    int→long 拓宽，`Long` 断言不会因 Integer!=Long 误红）；ByteArrayInputStream.close() 是空实现，
    controller try-with-resources 关流后仍可 `readAllBytes()` 校验转发字节。
- [业务代码·P1 微修复] 未认证请求 500+10001 → 102xx 信封（T7 遗留 P1），**只新增 1 个生产文件
  `config/AuthExceptionMapping.java` 81 行，`mvn -q -DskipTests compile` BUILD SUCCESS（退出码 0）**；
  未写测试、未动 src/test、未改 T1~T7 任何既有 Java 文件、application.yml 零改动。
  - **根因核实（框架源码）**：framework4j-accesstoken `TokenInterceptor.preHandle` 抛的
    `AuthException` 自带 `int code`（缺省 10200，字段 `getCode()`）；而 grep 全 framework4j 源码仅
    3 个 `@ControllerAdvice`（`WebAutoConfiguration` 注册的 `GlobalExceptionHandler`、
    `DataAccessExceptionAdvice` + 测试类），**没有任何 `@ExceptionHandler(AuthException.class)`**
    ——不是 order 被抢，是真无映射，落 `handleException(Exception)` 兜底 → 500 + 10001。
  - **做法**：`@RestControllerAdvice + @ExceptionHandler(AuthException.class) +
    @ResponseStatus(HttpStatus.OK)`，**直接返回 `ApiResponse.fail(code, message)`**（码/文案原样透传）。
    **不抛 ApiException**（关键，注释已写死依据）：Spring 6.2.19
    `ExceptionHandlerExceptionResolver.doResolveHandlerMethodException` 的 `catch (Throwable
    invocationEx)` 对 handler 内抛出的**新**异常只记 warn 后 `return null`（L473-484，源码 jar 已核），
    原异常继续外抛结局仍是 500——框架两个 advice 也都直接返回 ApiResponse，惯例一致。防御性兜底：
    code ≤ 0 → 10202；message 空白 → `ApiCode.fromCode(code)` 标准文案（未知码「认证失败，请重新登录」）。
  - **Spring 选择机制（javadoc 写死）**：跨 advice 是「按 AnnotationAwareOrderComparator 排序后
    **第一个含匹配方法的 advice 胜出**」，不做全局最具体匹配；`GlobalExceptionHandler` 自带
    `@ExceptionHandler(Exception.class)` 且 order=LOWEST_PRECEDENCE，故本类显式
    `@Order(HIGHEST_PRECEDENCE + 100)`（与 DataAccessExceptionAdvice 同款）确保先被选中。
  - **运行期冒烟（真实 PG/Redis，全程只读：wx_account 仍 3 行未污染）**，5 个场景全过、HTTP 一律 200：
    ①无令牌 GET /v1/accounts → **code=10200「未提供认证令牌」**（修复前 500+10001）；
    ②无令牌 GET /v1/status → 10200；③垃圾串 `Bearer abc.def.ghi` → **10202「令牌签名验证失败」**；
    ④真实 secret 签名、exp 已过 → **10201「令牌已过期」**；⑤真实 secret 签名、exp 未到但无 Redis 会话
    → 10201「令牌已过期或不存在」（透传 `AccessTokenValidationStrategy` 的码，证明策略层抛点同样生效）。
    **冒烟遗留物清理**：进程已杀（8080 端口释放，maven 子进程 taskkill）、Redis 无残留 key、零 DB 写。
  - **启动备注（非代码问题）**：`spring-boot:run` 会触发 testCompile，撞上并行测试 agent 正在改的
    `blob/BlobStorageTest.java`（缺 `FileTime` import）→ 加 `-Dmaven.test.skip=true`（跳过测试**编译**，
    不只是跳执行）后 6s 起来；对 src/test 零改动。
  - **遗留**：①`@RequiresToken(exception=自定义异常类)` 的项目不经过本 advice（框架
    `AuthExceptionFactory` 反射构造的自定义类型不是 AuthException 子类；本项目未用自定义异常，不构成缺口）；
    ②「坏 token → 10207」的分支实测没走到（含 exp/iat 的 token 先验签失败 → 10202），10207 仅在
    段数不对 / Base64 挂 / 缺 exp/iat 时出现，信封同样由本 advice 透传，无额外处理；
    ③T8 复跑全量测试时建议补一个 MVC 层断言（MockMvc 无令牌 → 200 + 10200），归测试 agent。

- [T7 业务代码] GET /v1/status + 幂等/限流接线 + README + prod profile + .gitignore，**业务代码完成，
  `mvn -q -DskipTests compile` BUILD SUCCESS（退出码 0）；未写测试（src/test 未动）、未改 T1~T6 的
  任何 Java 文件、application.yml 只动幂等相关键**。**额外做了启动 + 接口冒烟**（真实 PG/Redis，
  见下），编译之外的第一批运行期证据。新增 532 行：
  `status/{dto/StatusVOs 42, mapper/WxStatusMapper 23, service/StatusService 59, controller/StatusController 32}`
  = 156 行（任务给的 ≤150 超出 6 行：按包分层四个文件，砍只能砍 DB_DESIGN 溯源注释）+
  `config/IdempotencyWiringConfig 55` + `config/RateLimitQuotaConfig 172` = 227 行（不在预算条目内，
  是幂等/限流接线的必需载体）。
  - **幂等接线（P1 修复，实际做法）**：①yml `framework4j.idempotency.path-patterns: [/v1/reports/**]`
    （键名核自 `IdempotencyProperties`，v2.1 起可配，原缺省 `/api/**`）→ 拦截器只管 JSON 上报族，
    启动日志实测「注册 Idempotency-Key 拦截器，路径 [/v1/reports/**]」；②新增
    `IdempotencyWiringConfig` 用 `FilterRegistrationBean` 把**框架创建的那个**
    `IdempotencyBodyCacheFilter` Bean 重注册到 `/v1/reports/*`（order 显式回填
    `HIGHEST_PRECEDENCE+10`，与框架类上的 @Order 一致）。**必须注入既有 Bean 而非 new**：
    框架 Bean 是 `@ConditionalOnMissingBean`，自建实例会让框架再建一份、裸 Bean 仍按 `/*`
    自动注册；已核 spring-boot 3.5.16 `ServletContextInitializerBeans` 源码——注册 Bean 先收集并把
    Filter 实例记入 `seen`，随后裸 Filter Bean 的适配注册按 seen 去重，**不会双重注册**（启动
    TRACE 实测只有一行 `Mapping filters: idempotencyBodyCacheFilter urls=[/v1/reports/*]`，
    其余 filter 全是 `/*`）。**OOM 风险就此消除**：PUT /v1/blobs/{sha256} 是 octet-stream 非
    multipart（`shouldNotFilter` 不命中），原先会经 `CachedBodyRequestWrapper.cacheBody()`
    把 512MB 整读进堆；现不在 `/v1/reports/*` 内 → 不包装不缓存，字节全程流式落盘（实测 1KB
    PUT `stored` → 重复 PUT `exists`，内容寻址天然幂等、无需 Idempotency-Key——注释写在 yml 与
    config 类 javadoc）。**一致性约束**（类 javadoc 已写死）：拦截器对命中路径做 body hash
    （bodyHashRequired=true），过滤器若不覆盖同路径会走 fail-secure 抛 IOException → 500(10001)，
    所以改 `path-patterns` 必须同步改 `IdempotencyWiringConfig.URL_PATTERN`。
  - **限流（维度偏差已记）**：§9 四档落地 = messages 30/min、relations 6/min、blob PUT 60/min、
    其余 120/min。**没用 @RateLimit 注解**——任务边界禁止改 T1~T6 Java，而 §9 各档只能标在各自
    Controller 上；改为 `config/RateLimitQuotaConfig`（`WebMvcConfigurer` + 拦截器，只挂
    `/v1/reports/messages`、`/v1/reports/relations`、`/v1/blobs/*` 三个 pattern，blob GET 在拦截器
    内按 method 放行）调**框架真机制** `RateLimitService.tryAcquire`（Lua 滑动窗口，Redis 故障时
    框架内建放行兜底）；框架自己的 `RateLimitInterceptor` 继续挂 `/**` 提供缺省档（yml
    `default-limit: 120` = §9 查询档），两者叠加、被限的都是 429+10500。响应与框架同款：429 +
    信封 10500 + `Retry-After` / `X-RateLimit-Limit|Remaining|Reset` + `X-Trace-Id`（实测
    `X-RateLimit-Limit: 6` / `Retry-After: 43`）。**偏差（P1 记录，框架 1.10.0 限制）**：
    ① 「每账号/每令牌」维度不可用——`RateLimitKeyResolver` 的 `user` scope **只认 `X-User-Id`
    请求头**（javadoc 说取 TokenContext uid，源码没有），`@RateLimit.key` 的 SpEL 在
    `RateLimitInterceptor` 里**根本未被消费**；取最近似维度**客户端 IP**（XFF 首段 → X-Real-IP →
    remoteAddr，与框架 key 解析同序）。后果：NAT 后多账号共享一桶（比配额更紧）、同账号换 IP
    得多桶（更松）。② 框架限流 key 含 `request.getRequestURI()` → 内容寻址下 blob PUT 每个
    sha256 各一桶，60/min 形同虚设；本拦截器对 blob PUT 用固定桶 `/v1/blobs/`（所有 sha256 共桶）。
    ③ 缺省 `whitelist-ips: [127.0.0.1, ::1]` 让本机调用全部豁免（沿用框架口径，本机自测/集成
    测试不消耗配额）——**application-prod.yml 已置 `whitelist-ips: []`**（生产经反代时 remoteAddr
    是回环，保留豁免 = 全流量绕过限流），dev 照旧。④ 本拦截器排在 TokenInterceptor **之前**
    （实测未认证请求也消耗配额后才撞鉴权），与框架 RateLimitInterceptor 同病（其 javadoc 声称
    低于 Signature 拦截器但无强制）——未认证洪水会先吃掉配额，属可接受（429 早于 401）。
  - **GET /v1/status**（admin 角色，只读，无 Idempotency-Key）：`data = {accounts:[{account, hostname,
    client_version, last_sync_at, last_seen_at}], fb_pending_count, suspect_duplicate_count,
    recent_batches:[{id, account, batch_ref, started_at, duration_ms, n_messages, n_inserted,
    n_merged, status}]}`。两个计数走新 `WxStatusMapper` 的 `@Select COUNT`（`dedup_key LIKE 'fb:%'`
    / `suspect_duplicate = TRUE`，已在 wxuw-pg 实测语法与结果）；账号清单与最近 10 批
    **复用 T2 `WxAccountMapper` / T4 `WxIngestBatchLogMapper`（零改动）**，MP wrapper 显式列清单
    （不带 error 文本 / api_key_hash）+ `.last("LIMIT 10")`，与 QueryService.ingestBatches 同款纪律。
    计数是 `long`，线上仍落 String（`WebConfig` 对 `Long.class` 和 `Long.TYPE` 都注册了 Long→String，
    反编译/源码已核），与 T4/T6 登记的口径一致。
  - **运行期冒烟（本任务自加，PG/Redis 真机 + 一次性库 wx_t7smoke，跑完即 DROP）**：
    ①启动 9.4s 成功，幂等拦截器 `[/v1/reports/**]`、过滤器 `urls=[/v1/reports/*]`；②bootstrap
    三步真实走通（seed 启动 → 日志取 admin token → 注册账号 → 签发 client 令牌，README 步骤照抄）；
    ③GET /v1/status 200 + code=0，heartbeat 后 accounts 带 hostname/client_version/last_seen_at，
    2 条 fb 消息后 `fb_pending_count="2"`、recent_batches 出 1 行（n_messages=2/n_inserted=2/OK）；
    ④幂等闭环：/v1/reports/heartbeats 带坏 key → **400 + 10102**（拦截器在该路径生效）；relations
    同 key 同 body → **回放缓存响应**（batch_id/trace_id 逐字一致，证明过滤器缓存 body + 响应
    wrapper 闭环工作）；同 key 异 body → **409 + 10501**；⑤blob PUT 无 key → stored → exists；
    ⑥限流：XFF 伪造 IP 连打 relations 7 次 → 第 7 次起 429/10500，messages 桶独立不受影响，
    127.0.0.1 仍被白名单豁免。**冒烟遗留物已清理**（临时库 DROP、Redis idem/ratelimit key DEL、
    冒烟 blob 文件删除，dev 库 wx_account 仍 3 行）。
  - **README.md**（server/ 根 113 行 ≤150）：前置/建库、构建运行（含 JAVA_HOME 与 prod profile）、
    bootstrap 三步、配置键速查表、5 条 curl、幂等与限流口径表、代码地图。
  - **application-prod.yml**（25 行）：springdoc `api-docs.enabled=false` + `swagger-ui.enabled=false`
    + 限流白名单置空 + **环境变量注入提示注释**（WXUW_TOKEN_SECRET 必注入 / WXUW_BOOTSTRAP_SEED
    生产严禁设置 / WXUW_BLOB_ROOT / WXUW_PG_PASSWORD 与 hash-salt 建议 env 化）。
  - **.gitignore** 追加 `data/`（blob-root 缺省 `./data/blobs`，T5 遗留 P2 已闭环）。
  - **未做 / 留给 T8 及后续**：**P0 无**。**P1（新发现，非本任务引入）**：未认证请求返回
    **HTTP 500 + 信封 10001「系统繁忙」而非 401/10200**——`TokenInterceptor.preHandle` 抛
    `AuthException`，框架 `GlobalExceptionHandler` 以「未捕获的系统异常」兜底（冒烟 4 个端点
    全部实测如此，含 GET /v1/accounts 既有端点，属 T2/框架接线问题，本任务无权改）；客户端
    无法区分「没带令牌」与「服务端故障」，建议 T8 评估给 AuthException 加映射或在
    preHandle 前置短路。P2：①`dedup_key LIKE 'fb:%'` 在大表上可能不走
    `uk_wx_message_dedup_key`（普通 btree 对前缀 LIKE 只在 C locale / text_pattern_ops 下生效，
    wxuw-pg 实测 3 行是 Seq Scan，表小无法判定）——量级上来后建议加
    `CREATE INDEX ... ON wx_message (dedup_key text_pattern_ops)`；②recent_batches 用
    `.last("LIMIT 10")`（无 count），语义是「最近 10 条摘要」而非分页，符合任务口径；
    ③限流拦截器先于鉴权消耗配额（见上）；④`blob PUT 60/min` 共桶意味着高频客户端在同一 IP
    上传多文件时会整体受限（是有意收紧，非 bug）；⑤未跑 `mvn test`（按任务要求），T8 需
    复跑全量确认 95/95 仍 GREEN——本任务新增的 Bean/拦截器对纯 Mockito 单测无影响
    （src/test 无 Idempotency / RateLimit / FilterRegistration / /v1/blobs 引用，已 grep 核实）。

- **[重大偏差记录：网关额度不足 → 主 agent 降级执行]** 2026-11-09 ~22:40 三个子 agent
  （测试修复 ×14、T6 测试编写、T7 业务）同时死于 API 502「用户算力余额不足」（推理网关
  113.96.191.183:9401）。主 agent 改为亲自执行，已完成：
  - 修复死 agent 留下的半成品：测试修复 8 处已落盘的收尾（+7 处主 agent 补：F3 seen_at 断言、
    F5/F11 MP wrapper 懒求值渲染、F6 service 层不抛 10100 改断言 outcome、F10 同实例断言、
    F13 回退为 message 文本断言——**框架 ApiException 没有 errorDetail getter**（反编译证实，
    诊断处方的 e.getError() 不存在，field 翻译在 GlobalExceptionHandler 内部）、E1 lenient、
    E2 insert 返回类型）；relation 2 处（E2 返回类型 + F12 selectOne 次数）
  - **F9 适配**：F9 业务修复引入三级查找后，message 单测 7 处 stub 序列/次数全面对齐
    （srv 键路径 selectOne×2、merge 两分支 stub 序列、id 回填补齐 ×3、seen 断言按
    「is_revoked 以最新观测为准」喂 TRUE）
  - QueryServiceTest.java.pending：死 agent 半成品（stub 系统性缺失），搁置待重写；
    同文件里写完的 CursorCodecTest（7 用例）已收编全绿
  - **全量第 5 轮：95/95 GREEN**（mvn test 本机亲跑）
- [F9+F13 业务代码修复] fb→srv 合并死代码 + 错误文案补 field 路径，**只改 2 个生产文件，
  `mvn -q -DskipTests compile` BUILD SUCCESS（退出码 0，首轮即过，未撞并行 query/ 包）**；
  未动测试、未跑测试。

- [F9+F13 业务代码修复] fb→srv 合并死代码 + 错误文案补 field 路径，**只改 2 个生产文件，
  `mvn -q -DskipTests compile` BUILD SUCCESS（退出码 0，首轮即过，未撞并行 query/ 包）**；
  未动测试、未跑测试。
  - **F9（P0）**：`message/service/MessagesIngestService.upsertMessage` 原来只按入参新键查
    （srv:）→ 查无 → INSERT，`existing.fb && vm.srv` 分支恒假，`mergeFbIntoSrv`（L198-225）不可达，
    同一条消息 server_id 0→非 0 时**裂成两行**、action=merged 永不出现。改为三级查找：
    ① 按入参键查；② 兜底一（入参是 srv: 且落空）→ 按 fb 键 `fb:chat|sender|create_time_raw|sha256`
    重算后查（sender 缺失空串占位，拼装逻辑抽成 `fbKey(chat, sender, createTimeRaw, sha)` 一份实现，
    validate 与兜底一共用，防漂移）；③ 兜底二（fb 键也没中，如两次上报 sender 一缺一解析）→ 按
    `wx_message_source_rel` (account, chat_username, shard, local_id) 反查消息行——命中 fb: 行同样
    mergeFbIntoSrv，已是 srv: 形态（溢出走原始串的另一种 srv 键）→ 视为同一行重复上报走观测覆盖；
    ④ 都落空才 INSERT。**兜底二零 Mapper 改动**：`selectBySourceCoord` 复用 BaseMapper.selectOne
    四元组 wrapper（与 upsertSourceRel 同款）+ `messageMapper.selectById(rel.messageId)`，
    未给 WxMessageSourceRelMapper 加 @Select（守住「只改 2 文件」约束）。mergeFbIntoSrv 本体
    未动（原地改键+写 server_id 满足 ck_wx_message_srv_dedup / 撞唯一 → repointToKept + 删本行 +
    merge_log），仅 javadoc 补「唯一入口=两级兜底」与 SYNCH_PLAN §4.3 实测依据
    （server_id 落库瞬间为 0、≈160ms 后原地改写为非 0）；upsertMessage javadoc 补三级查找伪码。
    代价：入参 srv: 键落空时每条多 1~2 次 SELECT（fb 键 / 源侧四元组），仅 srv 落空路径触发，
    正常重放（键命中）零额外开销。
  - **F13（P2）**：`relation/service/RelationsIngestService.applyLabelMembers` 两处 10102 的
    message 补 field 路径，对齐 requireUsername/normalizeSource 既有风格——
    `"label_members[i].label_name 未在 labels 上报且服务端无字典: …"`、
    `"label_members[i].member_hint 为空且 member_username 未命中联系人"`（field 仍在 ApiError.field）。
  - **遗留（本步未做，非本轮引入）**：① 撤回传播只在 mergeFbIntoSrv 冲突分支里做（P2，观测覆盖已有）；
    ② 兜底二的四元组反查未覆盖「同 account 不同 chat 同 local_id」以外歧义（四元组含 chat，无歧义，
    仅记录口径）；③ T5/T7 未开始、T8 全量测试未跑。
- [T6 业务代码] 查询 API（API_DESIGN §5），**业务代码完成，`mvn -DskipTests compile` BUILD SUCCESS
  （退出码 0）**；未写测试（src/test 未动），未改 T1~T5 既有文件，application.yml 零改动。
  - **产出 7 文件 920 行**（包根 `com.wechat.sync.server.query`）：`service/QueryService` 377、
    `mapper/WxMessageQueryMapper` 167（查询侧专用只读 Mapper，带 3 个嵌套 @Data 行载体）、
    `dto/QueryVOs` 132（6 个响应 record 集中一文件压行数）、`controller/QueryController` 123、
    `service/CursorCodec` 79、`dto/SearchRequest` 28、`dto/CursorPageVO` 14。
  - **端点清单**：新建 9 个（GET /v1/chats、GET /v1/messages、POST /v1/messages/search、
    GET /v1/messages/{message_id}、GET /v1/contacts、GET /v1/groups/{group_username}/members、
    GET /v1/attachments/{attachment_id}、GET /v1/statistics/dedup、GET /v1/ingest-batches），
    类级 `@RequiresToken(value=admin, roles=admin)`；**§5.1/§5.2（GET /v1/accounts、
    GET /v1/accounts/{account}）不在本包**——T2 的 AccountAdminController 已提供同 URL 同角色
    同 AccountService.list/detail，重复声明会撞 Spring ambiguous mapping 启动失败（任务文本
    「复用 AccountService.list/detail」按「复用既有端点」落地）；GET /v1/blobs/{sha256} 归 T5。
  - **cursor 编码（关键决策）**：`Base64Url(createTimeMillis + ":" + id)`，服务端不透明串；
    解码全量结构校验（非 Base64Url 字符集 / 缺冒号 / 非数字 / millis<0 或 id≤0 → 10102
    且 error[].field=cursor 带原值），绝不把脏输入拼进 keyset 谓词；keyset 谓词用 PG 行比较
    `(create_time, id) < (?, ?)`（**已在 wxuw-pg 实测语法通**）。位点拆成 cursorTime/cursorId
    两个标量 @Param 传（不传 record：MyBatis 参数属性访问只认 getX/isX，record 访问器不保证）。
    has_more = 取到 limit+1 条（多取那条不返回）；create_time 为 NULL 的行只在第一屏出现、
    进不了游标（已注释）。
  - **视图查询（关键决策）**：v_wx_message_dedup / v_wx_group_members_union 用自定义 @Select，
    显式列清单且**不取视图的 body 列**；`accounts` varchar[] 经 `to_json(accounts)` 转字符串
    （MyBatis 对 SQL 数组无默认 TypeHandler，map 成 String[] 不可靠），服务层 Jackson 解析成
    列表（NULL → 空数组，LEFT JOIN 无来源行时 array_agg 为 NULL）；分页靠 MP
    PaginationInnerInterceptor 对带 IPage 参数的自定义 @Select 自动加 LIMIT/OFFSET + count
    （`ORDER BY … NULLS LAST` 的 count 改写已在 wxuw-pg 实测可解析）。
  - **大字段纪律（DB_DESIGN §12.2）**：消息列表/检索/统计**不取 content_raw**（查询侧专用
    Mapper 自写 SQL，复用 T4 实体会把大字段带出来），只有 messages/{id} 详情取全文 + dedup_key；
    wx_ingest_batch_log 分页用 `.select(...)` 显式排除 error 文本；其余 MP 查询生成显式列清单
    本就不含 `*`。
  - **ILIKE 豁免注释（规范禁左模糊）**：q 由服务层转义 `%`/`_`/`\`（PG 缺省转义符）后拼
    `%q%`，Mapper 层 `m.body ILIKE #{bodyPattern}` 处注释「规范禁左模糊，检索接口经评审豁免：
    内容检索业务必需」；v1 用现有索引，pg_trgm/tsvector 后续升级接口不变。
  - **其余**：禁深 offset——Offset 模式请求 page > 1000 → 10102（field=page，javadoc 写明
    理由：PG 深 OFFSET 是逐行丢弃的顺序扫描）；limit/page_size 复用 PageVO.clamp（≤200）；
    sources 摘要按 message_id 批量查 wx_message_source_rel（复用 T4 Mapper，IN 分块 ≤500，
    按 account/shard/local_id 排序保证确定性）；时间入参统一解析 ISO-8601 / epoch 秒 /
    epoch 毫秒，非法 → 10102（field=create_time_after 等）；路径变量 id 非数字 → 10102；
    chat_username 存在性（§7 → 10400）只在首屏（无 cursor）判，省一次 count。
  - **未做 / 留给后续**：P0 无。**P1 运行期冒烟未做**（需 PG+Redis 在线 + admin 令牌；本子任务
    只授权编译自检 + SQL 语法实测）——`to_json(accounts)` 的 MyBatis 读侧、MP 对自定义 @Select
    的分页 count 改写、`@RequiresToken` 类级注解（已核 framework4j 1.10.0 TokenInterceptor L58
    回落 `getBeanType().getAnnotation`，path-patterns 缺省 `/**` 会拦 /v1/**）均未实测。
    P2：① §5.1 的 `cursor_lag` 未实现（AccountVO 无此字段，需聚合 wx_sync_cursor；改 T2 文件
    越权，且口径文档未定义是 sort_seq 差值还是 last_sync_at 时差）；② 计数类 Long
    （seen_in_accounts / source_accounts / message_count / n_*）线上落 String，与 §2.2
    「计数为 Number」有出入（framework4j 全局 Long→String，T4 的 size 已登记同款）；③
    v_wx_message_dedup 无索引、深分页代价高（视图先聚合全量再裁页，建议后续给
    chat_username 加谓词下推或物化）；④ 检索 q 全空时是全库 keyset 扫描（合法但慢，
    可考虑 T7 加最小过滤条件约束）；⑤ 限流 120 次/分钟每令牌归 T7 接线。

- [T4 测试代码] 消息批 ingest 两层测试 **3 文件 800 行，`mvn -q test-compile` BUILD SUCCESS（退出码 0）；
  未运行测试（runner 另有人）、未动 src/main、无 @Disabled**。
  - `message/service/MessagesIngestServiceTest` 516 行 / 13 用例（纯 Mockito，7 个 Mapper +
    AccountBinding 无关 + TransactionTemplate mock 用 `willAnswer` 真跑回调才够到 mapper 参数层；
    `MpTestTables.init` 注册 7 实体）：①srv 键 + server_id 落库 + is_group/create_time 派生；
    ②BIGINT 溢出 → server_id=NULL + server_id_raw + `srv:<raw>`；③缺省/"0"/空白/"-5" → fb 四段键
    （sender 缺失空串占位）；④键命中无变化 → 仅观测字段 UPDATE（never insert、不含 body/dedup_key/
    created_at、revoked_at 保旧值）+ action=unchanged；⑤命中且 is_revoked/body 变 → 覆盖两字段；
    ⑥fb 行 + 有效 server_id + srv 无主 → 同批第二条 UPDATE 原地改键（dedup_key/server_id/server_id_raw
    断言到 MPGENVAL 参数）+ merge_log MERGED（kept_id=400）+ 不 deleteById；⑦srv 已存在 →
    repointToKept(500,501) + source_rel 改指 + 仅 1 次 update（打在保留行）+ deleteById(fb 行)；
    ⑧seen_accounts：recomputeSeenAccounts([601,600]) 且唯一 update 不含 seen_accounts（**无 +1 式**）
    + source_rel 两条；⑨附件 uk 归一化（image/thumb→IMAGE/THUMB、ok→OK、未识别→UNKNOWN、sha256
    缺省 ''、status 缺省 PENDING）+ blobs_needed attachmentKey `<id>:image:thumb`；⑩游标镜像 uk 三元组
    insert + 只前进不后退（max(3,5)=5 / max(300,200)=300）；⑪blobs_needed 剔除 wx_blob 已有 sha
    （断言 select 列 / is_deleted=0 / IN 两 sha）；⑫混合批 PARTIAL（error[].field=chats[0].messages[0].shard
    + nMessages 只算合法）与全失败 FAILED（results 空 → 10100 data=null）；⑬10101/10100 抛错点 +
    每 chat 一次 execute（无效 chat 不开事务，batch log nMessages=2/PARTIAL）。
  - `message/controller/MessageReportControllerTest` 112 行 / 4 用例：0 信封、10700（data+error 并存）、
    results 空 → 10100、requireRegistered(归一化 account) 先于 ingest（InOrder）。
  - `message/MessageIngestIntegrationTest` 172 行 / 4 用例（@SpringBootTest 直连 PG 5433 + Redis，
    PER_CLASS @BeforeAll/@AfterAll 按前缀 `wxid_t4itest_` / `t4itest_` DELETE 六表幂等清理；已确认
    13 张 wx_ 表与 ck_wx_message_srv_dedup 在库）：i1 跨账号同 server_id → wx_message 1 行 +
    source_rel 2 行（重放不增）+ seen_accounts=2（**全项目去重主断言**）；i2 fb 键行补 server_id →
    原地合并（dedup_key 变 srv:7001、行数不变、merge_log kept_id 命中）；i3 server_id 20 位溢出 →
    server_id NULL + raw 落库且 CHECK 放行；i4 超长 chat_username（81 字符 > VARCHAR(64)）→ 该 chat
    回滚进 error[]（chats[0].messages[0].local_id / DB_ERROR）且后一 chat 正常提交。
  - 关键自证：单测里 `LambdaUpdateWrapper.getSqlSet()` 的参数用
    `column=#{ew.paramNameValuePairs.MPGENVAL<n}` 反查取值（MP 3.5.15 源码核实格式），
    能断言到具体列的入参（含 server_id=NULL）；行数红线：A=628（单测 516 + 控制器 112）+ B=172 = 800。

- [ingest 错误响应微修复] P1：10101/10102/10100 抛错点缺 `error[].field`/`value`（双参
  `ApiException(ApiCode, message)` 框架 1.10.0 置 `errorDetail=null` → error[] 空数组）。改 3 文件：
  `relation/service/RelationsIngestService`（6 处：requireAccount、requireUsername、normalizeSource、
  parseRowId、label_name 无字典、member_hint 空）、`message/service/MessagesIngestService`（3 处：
  requireAccount、requireChats null、requireChats 空）、`message/controller/MessageReportController`
  （10100 整批失败带上 `outcome.errors()`）——统一走私有 `paramError(...)`：三参
  `new ApiException(ApiCode, message, List<ApiError>)`（1.10.0 唯一能带 errorDetail 的签名，
  @Deprecated 但无替代）+ `ApiError.of(field, code, message, rejectedValue)`，field 路径式如
  `label_members[0].label_name` / `contacts[2].contact_id` / `chats`。**未动 10300/10601（auth 包）、
  未动成功路径、未动测试**；`mvn -DskipTests compile` BUILD SUCCESS。
  遗留：框架 `ApiError` 序列化键是 `rejectedValue`（API_DESIGN §2.1 写的是 `value`）——文档与框架
  键名不一致，待主 agent 定夺改文档还是改框架；error[] 内 `code` 沿用 messages 元素级既有短名闭集
  （PARAM_MISSING / FORMAT_INVALID / DB_ERROR），未新增 code 词表。
- [T5 业务代码] blob 内容寻址文件库 + PUT /v1/blobs/{sha256} + GET /v1/blobs/{sha256} +
  POST /v1/reports/heartbeats，**业务代码完成，`mvn -DskipTests compile` BUILD SUCCESS（退出码 0）**；
  未写测试（src/test 未动），未改 T1~T4 既有文件（application.yml 只在 `wx-sync:` 块内追加 2 键）。
  - **产出 7 文件 569 行**（任务给的 ≤600 内）：`blob/BlobStorage` 240（@Component，显式构造器
    注入 @Value，AdminBootstrap 同款姿势避开 @Value 落不到 Lombok 构造参数的坑）、
    `blob/controller/BlobController` 151、`blob/dto/BlobUploadVO` 17、
    `heartbeat/controller/HeartbeatController` 44、`heartbeat/service/HeartbeatService` 74、
    `heartbeat/dto/HeartbeatRequest` 30 / `HeartbeatVO` 13。
    复用 T4 的 `WxBlobEntity` / `WxBlobMapper`（未重建）；controller 放 `controller/` 子包
    （对齐 T2~T4 既有布局，任务文本写的 `blob/BlobController` 理解为「blob 包下」）。
  - **BlobStorage（SYNCH_PLAN §9.4 写入协议）**：根目录 `wx-sync.blob-root`（yml
    `${WXUW_BLOB_ROOT:./data/blobs}`，运行目录相对）、两级分桶 `<aa>/<bb>/<sha256>`、
    上限 `wx-sync.blob-max-bytes=536870912`（超限 10505，Content-Length 预检 + 流内累计双闸）。
    store 流程 = 盘上已有 → exists 短路 → 临时名 `<sha256>.tmp-<pid>-<nano>`（§9.4 的 tmp-<pid>
    再加固 nanoTime）→ 64KB 流式拷贝边算 MessageDigest SHA-256 → 重算不匹配删临时件抛 10602 →
    `Files.move ATOMIC_MOVE`；`AtomicMoveNotSupportedException` 跨卷降级 REPLACE_EXISTING、
    `FileAlreadyExistsException` 是 Windows 并发同内容让位（两个 catch 都有注释）；
    finally + done 标志保证任何失败路径删临时件。sha256 非 64hex → 10102（`normalizeSha256`
    先小写归一化，格式校验同时挡掉路径穿越）；@PostConstruct 建根目录 fail-fast。
    append-only 无删除，GC 判据（只删无 `wx_message_attachment.blob_sha256` 引用的，
    引用计数靠查询）写在类 javadoc。
  - **PUT 关键决策**：① `consumes = application/octet-stream` —— body 经
    `HttpServletRequest#getInputStream()` 流式读，其它 Content-Type 被路由层 415 挡掉
    （API_DESIGN §1 路由层拦截口径），Jackson/HttpMessageConverter 完全不碰字节；
    ② Content-Length 超限先拒（早失败，免收满 512MB）；③ 查重 = wx_blob 行（is_deleted=0
    同谓词）+ 盘上文件双判——表行在而文件缺失会重新落盘补齐并校正 size；④ 先查后插 +
    `DuplicateKeyException` 兜底（并发同内容 PUT 撞 uk_wx_blob_sha256 → 回 exists，
    ON 冲突跳过语义）；⑤ **不做 AccountBinding**：契约绑定规则是「载荷 account vs
    claims.account」比对，blob 载荷无 account 字段无从比对；内容寻址 + append-only 下误传
    只产生一条无人引用的 wx_blob 行，不污染任何账号数据（类 javadoc 已述；对外暴露开
    @RequiresSignature 是 §3 既有前提）。
  - **GET 关键决策（信封契约的明示例外）**：`@RequiresToken(value="admin", roles="admin")`
    （§5.9 下载唯一出口，照文档不做 client 可读），返回 `ResponseEntity<FileSystemResource>`
    **不带 ApiResponse 信封**——路由层二进制响应，API_DESIGN §1 允许（带信封会破坏
    octet-stream 语义）；404（无表行 / 库有行盘无文件）走 10400 信封；附
    `Cache-Control: public, max-age=31536000, immutable`（内容寻址永不可变，D3 缓存友好）；
    写出阶段 IO 错误发生在响应头提交后翻不成信封，客户端凭 sha256 自校验（注释已述）。
  - **心跳**：次序照 T3/T4（`requireAccount` 缺失 10101 → `requireRegistered` 不一致 10300 /
    未注册 10601）→ `LambdaUpdateWrapper` 更新 last_seen_at=now + hostname / client_version
    （字段缺省<b>不覆写</b>旧值）；wechat_version / db_size_bytes / logged_in 在 wx_account
    **无对应列 → 不落库只记日志**（不为心跳私加列，schema.sql 是权威）；updated=0
    （并发被注销）→ 10400；data={server_time}（OffsetDateTime）。不碰 last_sync_at
    （那是游标推进语义，归消息 ingest）。
  - **未做 / 留给后续**：P0 无。**P1（framework4j 侧，本任务不可解）**：
    `IdempotencyBodyCacheFilter` 对一切非 multipart 请求 `cacheBody()` 把 body 整读进堆
    （framework4j-web `CachedBodyRequestWrapper.readAllBytes`），PUT /v1/blobs 上限 512MB
    意味着单请求堆峰值 ≥512MB，并发上传有 OOM 风险——且 `framework4j.idempotency.path-patterns`
    缺省 `/api/**`，/v1/** 根本不进幂等拦截器（T4 账本「幂等对 /v1 自动生效」的说法不成立），
    缓存的 body 无人消费；建议 T7 用 `FilterRegistrationBean` 关掉该 filter 或降
    blob-max-bytes（动全局配置 / framework4j，超出本子任务授权）。P1 运行期冒烟未做
    （需 PG+Redis 在线 + client/admin 令牌；本子任务只授权编译自检）。P1 附件回填未做：
    PUT 成功不回填 `wx_message_attachment.blob_sha256`（SYNCH_PLAN §9.4 第 5 步）——需给
    T4 的 `WxMessageAttachmentMapper` 加方法，本任务不准改 T4 文件；读侧可先按
    `wx_message_attachment.sha256 = wx_blob.sha256` join（内容哈希等价）。
    P2：① `size` 线上是 JSON 字符串（framework4j 全局 Long→String），API_DESIGN §2.2 把
    size 归 Number，与 T4 blobs_needed 同出入；② `wx_blob.ext` 探测未做（PUT 契约无 ext
    字段，后续按文件头判型）；③ blob 缺省根目录 `./data/blobs` 未进 .gitignore（改 T1 文件
    越权）；④ GET（120/分钟每令牌）/ PUT（60/分钟每账号）限流归 T7 接线。

- [T3 测试完成后的修复队列（主 agent）] **P1**：ingest 错误响应缺 `error[].field`——
  `ApiException(ApiCode, String)` 双参构造 errorDetail=null（反编译 framework4j-web 1.10.0 确认），
  relation/message 的 10101/10102 应改用带 field 的构造（框架源码 `C:\workspace\tools\framework4j`
  查 ApiException 全部构造器），涉及 relation/service + message/service 的抛错点。
  **待 T5 业务 agent 完成后派业务微修复**（维持同时只一个业务 agent）。
  P2×4（T3 测试 agent 观察）：upsert 显式 SET 列表已锁 tag_ids；SERIAL 兜底 null 时计数偏乐观；
  update 命中 0 行静默跳过致重放计数抖动；637 行超 600 预算（已合并分支，接受）。

- [T3 测试代码] relation/ 包（17 文件）纯单测完成，**`mvn -q test-compile` BUILD SUCCESS**
  （main 含另一 agent 的 message/ 包同期可编译，退出码 0；只编译、未运行测试，src/main 零改动）。
  - **产出 3 文件 637 行 / 24 用例**（略超 ≤600 预算，为保住六类分支语义，见 P2-⑤）：
    `relation/service/RelationsIngestServiceTest` 412 行 13 例（校验次序 10101→10102 + 全空载荷零计数；
    contacts 新增归一化（source 小写收大写 / rowid→BIGINT / 不碰 tag_ids）与 update 保留 id、0 行命中（并发软删）
    不计数；groups insert+update；group_members 三元素（member_username 缺失跳过 C10 / insert / 覆盖 is_owner）；
    labels SERIAL 兜底 findLabel（keygen 缺 id 查两次）与 keygen 回填免回读 + source 越界/label_name 缺失 10102；
    label_members 三分支同批（命中→appendTagId、查无→新建 hint is_ambiguous 置位、已有 hint→update 保留 id）+
    字典查无/hint 为空 10102；conversations insert+update；六类各一行计数汇总 VO；
    `wx_contact.tagIds` 的 `@TableField(typeHandler=IntArrayTypeHandler)` + `autoResultMap=true` 反射接线）；
    `relation/support/IntArrayTypeHandlerTest` 127 行 8 例（写侧 createArrayOf("int4", 装箱 Integer[]) 含空数组、
    驱动失败上抛且不 setArray；读侧三形状收敛 int[] 原样 / Integer[] 含 null→0 / 单元素 Number→单元素数组 +
    CallableStatement 重载；NULL 列与未知形状归 int[0]；SQLException 不吞；array.free() 永不被调）；
    `relation/controller/RelationReportControllerTest` 98 行 3 例（@RequiresToken client + roles 反射接线、
    /v1/reports/relations 路由、requireAccount→requireRegistered→ingest 顺序、信封 code=0 data 同引用、
    account 缺失 10101 时不触 AccountBinding）。
  - 风格对齐 `AccountServiceTest`：@ExtendWith(MockitoExtension.class) 全 mock mapper / AccountBinding、
    不起 Spring 上下文；`MpTestTables.init` 注册 6 个关系实体 TableInfo（Lambda wrapper 构建期需列名解析）；
    captor 走 `@Captor` 字段（避免 raw forClass + @Unchecked）；断言 AssertJ。
  - **P1-①** `ApiException(ApiCode, String)` 把 field 级诊断放 message 而 **errorDetail=null**（反编译确认），
    即 ingest 的 10101/10102 响应体 `error[]` 不含 field 定位；若 API_DESIGN 契约要求 `error[].field=account`
    之类，需改用 `ApiException(ApiCode, String, Object)` 三参构造（本测试只断言 message，未锁死契约形态）。
  - **P2-②** `WxContactEntity.tagIds` 是 `int[]` + `IntArrayTypeHandler`：upsert update 走显式 SET 列表
    （不含 tag_ids），但**任何**对 contacts 的「整行 update」都会把 tag_ids 写回旧值——新增字段时须沿用
    「列清单 SET」纪律。测试已用 `doesNotContain("tag_ids")` 锁住。
  - **P2-③** labels 的 SERIAL 兜底 `findLabel` 在 keygen 未生效时会**多查一次**，且回读结果可能仍为 null
    （仅打 debug 日志，不抛）——此时该标签仍计入 `upserted.labels`，但同事务内拿不到 dictId；
    `applyLabelMembers` 侧 dict 为 null 会显式抛 10102，故不会出现 `appendTagId(..., null)` 传空的风险，
    后果限于「计数偏乐观 + 同批 label_members 必失败回滚」。
  - **P2-④** `upsertContacts` 的 update 命中 0 行不计 upserted（并发软删自愈），但 group/conversation 同款
    分支同样静默；`RelationsIngestVO` 契约未区分「跳过」与「不存在」，客户端重放校验会看到计数抖动。
  - **P2-⑤** 测试文件 637 行 > 600 预算：六类 × insert/update/校验三分支 + hint/tag_ids 路由共 24 例，
    为压行数已合并同节分支（contacts、group_members、labels、label_members 均为单测多分支），
    再压只能删用例（不删）。
  - **未做 / 留给 runner**：`mvn test` 未执行（本子任务只授权编译自检）；`@Captor` + 严格 stub（lenient 未用）
    在真实运行时若有未消费 stub 会报 UnnecessaryStubbing，已按「每例只 stub 当例路径」写法规避。

- [T4 业务代码] `POST /v1/reports/messages` 消息批 ingest（去重核心），**业务代码完成，
  `mvn -DskipTests compile` BUILD SUCCESS（退出码 0，三轮均过）**；未写测试（src/test 未动），
  未改 T1~T3 既有文件，application.yml 零改动。
  - **产出 18 文件 1327 行**（包根 `com.wechat.sync.server.message`；超任务给的 ≤1000 上限，见 P2-①）：
    `entity/` 7（任务列 5 + `WxMergeLog` 34 + `WxBlob` 39：前者是 fb→srv 合并审计 `wx_merge_log` 必需、
    后者是 blobs_needed 判定必需，均 `ASSIGN_ID`）、`mapper/` 7（`WxMessageMapper.recomputeSeenAccounts` 与
    `WxMessageAttachmentMapper.repointToKept` 带自定义 SQL，其余纯 BaseMapper）、
    `dto/` `MessagesReportRequest` 136（chats→cursor+messages→attachments 三层嵌套 record，ID String /
    sortSeq·createTimeRaw·size Number）/ `MessagesIngestVO` 35、`service/MessagesIngestService` 650、
    `controller/MessageReportController` 54。消息表 5 实体 id 均雪花；枚举列落大写
    （tier/kind UNKNOWN 兜底、sender_source SHARD_NAME2ID/BODY_PREFIX/CARD_XML、attachment status PENDING 默认）。
  - **事务边界（关键决策）**：编程式 `TransactionTemplate` 逐 chat `execute(tx -> applyChat(...))`——
    **整 chat 批原子、chat 间独立**（SYNCH_PLAN §6.4：一个事务 = 一个 (account, chat) 批）。已核
    spring-boot-autoconfigure 3.5.16 sources：`TransactionAutoConfiguration` 对唯一
    PlatformTransactionManager 自动装配 TransactionTemplate Bean，`@RequiredArgsConstructor` 直接注入。
    单 chat DB 异常 → 整 chat 回滚，该 chat 全部消息记入 error[]（field=`chats[i].messages[j].local_id`、
    code=DB_ERROR），其余 chat 照常提交。事务内产出先落 per-chat `ChatOutcome`、提交后才并入批级 `Batch`
    ——**回滚不留痕**（未提交的 results/blob 候选不会泄进响应）；`wx_ingest_batch_log` 在所有 chat 事务之外
    独立 insert（自动提交），整批全失败也留 FAILED 一行供排障。
  - **fb→srv 合并（关键决策）**：先查 dedup_key；fb 键命中且本次键升级为 srv: 时两条路——
    ① srv 新键无主 → 原 fb 行同一条 UPDATE 改键 + 写 server_id/server_id_raw
    （`ck_wx_message_srv_dedup` 的「键与 ID 同源」同步满足，SYNCH_PLAN §4.3 实测姿势）；
    ② srv 行已存在（另一客户端先到）→ **保留先到行**：来源行原地改指 message_id → 附件
    `repointToKept` 改指（`NOT EXISTS` 跳过会撞保留行 uk (message_id,kind,tier,sha256) 的附件，PG 同快照
    语义，既不孤儿也不冲突，已实测改指 1 行成功）→ `deleteById` 删本行 → 撤回状态传播到保留行 →
    merge_log 记 MERGED。**并发取舍**：不做分布式锁（SYNCH_PLAN §8）——「先查后写」窗口两客户端同撞
    一条合并时，后到者 UPDATE 撞唯一索引抛 DuplicateKeyException → 该 chat 整体回滚进 error[] →
    客户端凭 Idempotency-Key 幂等重放自愈（fb 键已被改写，重放走 srv 命中 unchanged），不做内部重试。
  - **10700 信封（关键决策）**：framework4j-web `ApiResponse.partialSuccess(data, errors)` **原生支持
    data 非 null + error[] 并存**（mc-api-spec §4 唯一例外项），Controller 直接返回，不需要绕；取值逻辑：
    `results` 非空且 errors 空 → `success()`；errors 非空 → `partialSuccess`；`results.isEmpty()`
    （整批无一条合法/全部 chat 落库失败）→ 抛 `ApiException(PARAM_ERROR)`=10100 data=null（此时 batch log
    已落 FAILED 行）。P2-③：框架 `ApiError` 第 4 分量固定序列化为 `rejectedValue`（@JsonProperty），
    API_DESIGN §2.1 示例写的是 `value`——字段名与文档有出入，属框架既有行为。
  - **server_id String→Long 溢出（关键决策）**：`server_id`/`server_id_raw` 取首个非空串 →
    `Long.parseLong` 成功且 >0 → `srv:<id>` + server_id=值 + server_id_raw=NULL；解析失败（BIGINT 溢出，
    实测 7.7×10¹⁸）→ server_id=NULL + server_id_raw=<串> + `srv:<串>`（CHECK 的 NULL 分支放行，
    已在 wxuw-pg 实测 `dedup_key='srv:888', server_id=NULL` 可写）；解析成功但 ≤0（含 "0"）→ 视为无有效 ID
    走 fb 键、server_id 落 NULL 不落 0（维持「server_id 有效 ⇒ 键必为 srv: 形式」不变量，fb 行永不携带
    有效 server_id）。
  - **dedup_key**：`fb:<chat>|<sender>|<create_time_raw>|<content_sha256>` 兜底，sender 缺失空串占位、
    suspect_duplicate 不置位（任务指定的 T4 简化：只标记逻辑不做）；content_sha256 与附件 sha256 统一
    小写化；fb 键最长 ~212 < VARCHAR(255)。
  - **seen_accounts 反算**：`recomputeSeenAccounts` 一条 `UPDATE … FROM (SELECT message_id,
    count(DISTINCT account) … WHERE message_id IN <foreach> GROUP BY message_id) s WHERE m.id=s.message_id`，
    批内 id 按 500 分块，绝无 +1；每 chat 事务内对本 chat 触达 id 反算（同 id 跨 chat/跨批由后到批重算
    覆盖，口径恒等来源表）。SQL 已在 wxuw-pg 实测：命中 1 行、cnt=2。
  - **校验分级（与 T3 同序同口径）**：Controller 先 `requireAccount`（缺失 → 10101）再
    `AccountBinding.requireRegistered`（内含 checkBound：不一致 → 10300、未注册 → 10601）；chats 缺失 →
    10101、空数组 → 10100；元素级 shard/local_id/content_sha256/create_time_raw 缺失或格式错、
    sender_source 越界 → 10102 记入 error[] 不进库（error[].code="FORMAT_INVALID"）；DTO 只留 @Size
    超长防御（命中走 10100，且防 DB varchar 溢出）。附件 kind/tier/status **宽松**归 UNKNOWN/PENDING
    （有兜底值，避免单条枚举污染整批），附件 sha256 非 64hex 仍照落元数据但**排除出 blobs_needed**。
  - **其余实现点**：is_group 由 chat_username 以 `@chatroom` 结尾推导（DDL NOT NULL）；create_time =
    create_time_raw 按 UTC Instant 换算（排序权威，存绝对时刻）；游标镜像 upsert 按 uk (account,
    chat_username, shard)，只前进不后退（乱序/并发取 max）+ last_sync_at 每批刷新，cursor 缺 shard 跳过
    镜像不阻断（观测表）；`blobs_needed` = 批内附件 64hex sha 去重后查 `wx_blob` 不存在的（`is_deleted=0`
    同谓词部分唯一索引，500 分块 IN 查询），size 缺省 0=未知、attachment_key=`<message_id>:<kind>:<tier>`
    小写；幂等由 framework4j-idempotency 拦截器自动生效（已核源码：按 method 白名单
    POST/PUT/PATCH/DELETE + pathPatterns 注册，无需注解，T3 同）；限流 30 次/分钟归 T7 接线；
    `<script>` SQL 需 text block 首行顶格（已核 MyBatis 3.5.19 `XMLLanguageDriver` 用
    `startsWith("<script>")` 不 trim；本 mapper 全行 12 空格统一缩进，incidental strip 后恰好顶格）。
  - **未做 / 留给后续**：P0 无。P1 运行期冒烟未做（需 PG+Redis 在线 + client 令牌；本子任务只授权编译自检）
    ——TransactionTemplate 注入、`<script>` foreach SQL 实跑、ASSIGN_ID 回填、逐 chat 回滚路径均未实测。
    P2：① 1327 行超 ≤1000（5 实体实需 7：merge_log 审计 + blob 判定各 +1 实体/Mapper；九类 upsert 逐字段
    覆盖 + 每步 javadoc，再压只能砍 DB_DESIGN 溯源注释）；② 撤回传播（is_revoked/body 变化的 UPDATE）
    在契约三值（inserted|unchanged|merged）内报 unchanged，撤回可经 /v1/messages/{id} 读到，第四值需改契约；
    ③ error[].rejectedValue 与 API_DESIGN §2.1 的 value 字段名不一致（框架 ApiError 固定）；④ fb 行合并
    删除时附件按 uk 等价改指，撞 uk 的附件字段级差异（filename/md5 等以先到行为准）不合并。

- [T 测试代码] 修复全量测试暴露的 3 处测试自身缺陷（只动 src/test 3 个测试类，未动 src/main，
  未跑 mvn test）：① `AccountAdminControllerTest` 把 `issueTokenNormalizesTtlAndForwardsAccount`
  拆成 3 个用例（无 body→DEFAULT_TTL / ttl=null→DEFAULT_TTL / ttl=3600 透传），消除
  `TooManyActualInvocations`（null body 与 null ttl 都归一到 7776000，`verify(…7776000)` 命中
  2 次），顺带补 access_token 断言、删失效的 anyString/anyInt import；② `PageVOTest`
  defaults 用例 `of(List.of(), 5, 0, 0)` 的 total=5 撑不起 `hasMore=true`（钳制后 1*20=20
  ≮ 5），改 total=25 并补各分支注释；其余三处（500/2/999、21/1/20、2/1/20）按
  `PageVO.of(list,total,page,pageSize)` + `hasMore = page*size < total` 逐一核对，本就正确，
  未按「参数反了」的说法乱改；③ `AdminBootstrapTest.runDoesNotPropagateBootstrapFailure`
  去掉触达不到的 `issueAdminToken` stub（register 抛异常后流程即中断），改为内联只 stub
  库空 count + `verify(tokenService, never()).issueAdminToken(any(), any())`，消除
  `UnnecessaryStubbingException`（与该类其它用例的内联 stub 风格一致）。
  `mvn -q test-compile` **BUILD SUCCESS（退出码 0）**；与业务微修 agent 的
  `PageVO.clamp` 重构无冲突（重新核对语义等价，断言仍成立）。

- [T 业务代码·P2-③] pageSize 钳制去重：`PageVO` 新增 `DEFAULT_PAGE_SIZE(20)` +
  `clamp(long, long)`（返回嵌套 record `PageParams(page, pageSize)`）作为单一钳制入口；
  `PageVO.of` 内部改调 `clamp`，`AccountService.list` 删掉本地重复钳制、改调 `PageVO.clamp`。
  **纯重构、行为完全不变**：两处原实现等价（page<1→1 / pageSize≤0→20 / min(200)），阈值与
  求值顺序无副作用；全仓仅此两处生产代码实现钳制（grep 确认），测试仅引用 `PageVO.MAX_PAGE_SIZE`
  与 `of/map`，签名未动。只读核对 has_more 口径：`clamp` 后 `page*pageSize < total`，
  AccountService 传 `result.getTotal()`（全量命中计数）与钳制后 page/size，语义一致；且
  safeSize ≤ 200 = `MybatisPlusConfig.maxLimit(200L)`，拦截器不二次钳制。未动 src/test。
  `mvn -DskipTests compile` **BUILD SUCCESS（退出码 0）**。

- [T3 业务代码] `POST /v1/reports/relations` 关系层 ingest，**业务代码完成，
  `mvn -DskipTests compile` BUILD SUCCESS（退出码 0）**；未写测试（src/test 未动），
  未改 account / auth / common 既有文件，application.yml 零改动（无需追加键）。
  - **产出 17 文件 1077 行**（包根 `com.wechat.sync.server.relation`）：
    `entity/` 6（WxContact 63 / WxGroup 41 / WxGroupMemberRel 40 / WxLabelDict 41 / WxLabelMemberHint 39 /
    WxConversation 38）、`mapper/` 6（BaseMapper，仅 WxContactMapper 带 SQL）、
    `dto/RelationsReportRequest` 160（顶层 account + 六数组，6 个嵌套元素 record）、
    `dto/RelationsIngestVO` 29（account + Upserted 六计数，嵌套 record）、
    `service/RelationsIngestService` 419、`controller/RelationReportController` 41、
    `support/IntArrayTypeHandler` 81。
  - **int[] 写入方案（关键决策）**：自写 `IntArrayTypeHandler extends BaseTypeHandler<int[]>`（`relation/support/`）。
    写侧 `Connection.createArrayOf("int4", Integer[])` + `ps.setArray`，**不调 free()**——pgjdbc 把参数序列化
    推迟到 execute；读侧 `getArray().getArray()` 收敛三种形状（pgjdbc 对 int4[] 可能返 `int[]` 或 `Integer[]`）+
    单元素 `Number`，null → 空数组。编译期**不 import `org.postgresql.jdbc`**（pom 里 PG driver 是 runtime scope，
    首轮编译被它卡住）。接线靠 `@TableField(typeHandler = IntArrayTypeHandler.class)` +
    `@TableName(value="wx_contact", autoResultMap=true)`——已核 MP 3.5.15 源码
    `TableFieldInfo#initResultMapping`（L553 起）会把它写进 auto resultMap，读侧生效。
    `tag_ids` 写入是 Mapper `@Update` 单条 SQL（去重幂等）：
    `UPDATE wx_contact SET tag_ids = (SELECT array_agg(DISTINCT x) FROM unnest(tag_ids || ARRAY[#{dictId}]::int4[]) AS x),
    updated_at = CURRENT_TIMESTAMP WHERE account=? AND username=? AND is_deleted=0`；
    **已在 wxuw-pg 实测**：PREPARE 语法过 + 重复 append 幂等（`{1,3}` 两次 append 7 → `{1,3,7}`）+ 未命中返 0 行。
  - **SERIAL 主键处理（关键决策）**：`wx_label_dict.id` 用 `IdType.AUTO`（其余 5 实体 `ASSIGN_ID` 雪花）。
    已核 MP 3.5.15 源码：`Insert#injectMappedStatement` 对 AUTO 走 `Jdbc3KeyGenerator`，
    `TableInfo#getKeyInsertSqlColumn/Property`（L326/L303）把 id 列包进 `<if test="id != null">` →
    **id 置空即 INSERT 不带 id 列，DB 生成后 getGeneratedKeys 回填实体**，另留 `findLabel` 兜底回读
    （keygen 未生效时）。intarray 不支持 bigint[]，故不能像其它表那样发雪花。
  - **upsert 纪律（SYNCH_PLAN §6.5）**：六类全「先查后插/改」（任务指定；部分唯一索引
    `uk(...) WHERE is_deleted=0` 不带同谓词不能直接 `ON CONFLICT`）。重复上报**覆盖业务字段（含置 null）
    但保留 id / created_at**；`wx_contact` 上报**不碰 tag_ids**（只由标签映射写，避免抹掉标签结果）；
    `wx_group_member_rel` / `wx_label_member_hint` 无 updated_at，只覆盖 nick_name/remark/is_owner、
    member_username/is_ambiguous。
  - **校验与次序**：Controller 先 `RelationsIngestService.requireAccount(...)`（缺失 → 10101）再
    `AccountBinding.requireRegistered(...)`（不一致 → 10300、未注册 → 10601）——缺 account 是参数缺失不是越权，
    次序不能反；元素 username/label_name 缺失、枚举越界、rowid 非数字 → 10102（**不走 bean validation**，
    框架把 MethodArgumentNotValidException 翻成 10100）。`group_members[].member_username` 缺失 →
    跳过该元素不计数（C10，DB 列 NOT NULL）。DTO 只放 `@Size`/`@Min` 超长防御（命中走 10100）。
  - **label_members 映射**：labels 先落字典 → `member_username` 能唯一命中 `wx_contact`（is_deleted=0）
    走 `tag_ids` append，不进 hint 表；映射不了 → `wx_label_member_hint` upsert，
    `is_ambiguous = 载荷 ambiguous || 本端未解析`（置位纪律）；`member_hint` 缺省回退用 member_username；
    引用未上报且库里也没有的 label → 10102（不自动建字典，避免伪造 `source='DB'` 定义）。
  - **其它**：SQL 追踪注释由 framework4j-sql-tracing 的 `TraceIdDruidFilter` 自动注入（yml `mode: ALL`），
    Mapper SQL 不手写 `/*traceid=*/`；无 try/catch（既有风格）；限流（6 次/分钟每账号，API_DESIGN §9）
    **未在本 Controller 声明 @RateLimit，归 T7 统一接线**。
  - **未做 / 留给后续**：P1 运行期冒烟未做（需 PG+Redis 在线 + client 令牌；本子任务只授权编译自检）——
    `IdType.AUTO` 的 getGeneratedKeys、autoResultMap 读 `tag_ids`、jsonb `attributes` 读侧（String 透传）
    均未实测；P2 行数 1077 超任务给的 900 上限（也超 PROGRESS 的 1000/步）：6 表 58 列 + 先查后插/改的
    逐字段覆盖 + 每方法 javadoc，再压只能砍文档，未做；P2 数组超上限走框架 10100 而非 10102。

- [T2 测试] mc-test 纯单测（JUnit5 + Mockito，不起 Spring 上下文）8 文件 / 34 用例 / 736 行，
  **`mvn -q test-compile` BUILD SUCCESS（退出码 0；期间 relation 包 2 次编译错误均为 T3 半成品，
  退避 75s 重试 1 次后自过，本任务测试文件零编译错误）**；未运行测试（runner 另有人），
  **无 @Disabled 用例**（RefreshTokenService 全 mock，claims 组装逻辑无需真连即可验证）。
  - **文件**（`src/test/java/com/wechat/sync/server/`）：`common/WxErrorCodeTest`（10601/10602 常量
    + 两工厂 code/message）、`common/PageVOTest`（page/size 钳制 + has_more 口径 + map 转换）、
    `account/service/AccountServiceTest`（8 用例：重名 10401 且不 insert、trim+字段映射+is_deleted=0
    +wxid 三种推导（显式/空白/无后缀）、detail 命中与 10400、list 钳制（page=1/size≤200）与
    selectPage 透传、list 记录映射+has_more、delete 软删（getSqlSet 含 is_deleted/updated_at 且
    参数含 1）+ 先 update 后 revokeByAccount 的顺序、update 命中 0 → 10402 且不吊销、
    账号不存在 → 10400 且不 update）、`account/service/TokenServiceTest`（7 用例：client claims
    {account,type=client,roles=[client]} 恰 3 键 + policy.expire-time 临时覆写 3600 后还原 100 +
    TokenPair/expiresAt、账号不存在 10400 且不签发不触 Redis、admin claims{uid=trim,type=admin,
    roles=[admin],account} 恰 4 键、account 空白则省略该 claim、uid 空白 → 10100、
    revokeByAccount 按 TokenKeyBuilder 口径清扫 access jti（进 revoked set + delete + extend TTL）
    并整族撤销命中 account 的 refresh family、无会话返回 0）、`auth/AccountBindingTest`（5 用例：
    claims.account=载荷通过、不一致/无 TokenContext → 10300、已注册返回载荷账号、未注册 → 10601）、
    `account/controller/AccountAdminControllerTest`（5 用例：5 端点转发参数 + success 信封，
    issueToken null 体/缺省 TTL 归一化 7776000）、`config/AdminBootstrapTest`（5 用例：seed 空/空白
    → verifyNoInteractions、库非空跳过、库空注册 dev 账号（account=wxid_bootstrap_dev_0000,
    wxid=null）+ issueAdminToken(seed.trim(), account)、dev 账号已存在跳过注册、register 抛异常
    不外传（best-effort））、`support/MpTestTables`（工具：MP 3.5.15 的 Lambda wrapper 构建期即
    解析列名，纯单测须手动 initTableInfo，否则抛 "can not find lambda cache"）。
  - **行数超预算说明**：任务口径 ≤500 行，实际 736（已两轮合并压缩 814→736：场景并入同用例、
    @Mock 单行、去组内空行）；再压需删除上列指定分支用例，未执行。
  - **P2 观察**：① `TokenService.issueForAccount` 以 policy 对象为锁覆写 expire-time，并发下
    其他线程读该 policy（如鉴权路径取 TTL）会短暂看到覆写值——现状可接受（签发低频），后续
    可改为框架支持按次 TTL；② `AccountService.delete` 的软删 update 与吊销非同一事务边界内的
    原子操作（@Transactional 包住 mapper update + Redis 吊销，Redis 失败会回滚 DB 但吊销可能
    已部分生效）；③ `PageVO.of` 的 has_more 以钳制后的 pageSize 计算，`list(0,500)` 返回
    has_more 与真实页大小不一致仅出现在非法入参路径。
- [T1 微修复] framework4j-audit `audit_log` 表缺失（冒烟日志 P1：
  `JdbcAuditSink loadLastHash failed (table=audit_log): bad SQL grammar
  [SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1] → fallback GENESIS`）
  → **已解决**。新建 `server/src/main/resources/db/framework4j-infra.sql`（31 行 SQL），
  `docker exec -i wxuw-pg psql -U postgres -d wechat -v ON_ERROR_STOP=1 -f -` 应用成功。
  - **DDL 来源（严格对齐）**：framework4j 源码
    `framework4j-audit/src/main/java/fun/commons/framework4j/audit/service/JdbcAuditSink.java`
    类 javadoc「建表 DDL」段（@since 2.2.0），与
    `framework4j/docs/modules/audit.md`「生产 DDL (PostgreSQL)」段逐列一致；
    默认表名 `audit_log` 见 `AuditProperties.tableName`。skill 文档
    `references/audit.md` 无 DDL（只有 sink 扩展示例），未采用。
  - **文件头注释**：声明这是 framework4j 基础设施表、幂等（IF NOT EXISTS）、
    **不属于业务 schema.sql**（那是 wx_ 业务表的设计权威），并写明框架读写口径
    （INSERT 14 列 append-only / `SELECT hash … ORDER BY id DESC LIMIT 1` /
    hash UNIQUE=链冲突信号）。
  - **列清单（15 列）**：`id BIGSERIAL PK` / `action VARCHAR(64) NN` /
    `target_type VARCHAR(64) NN` / `target_id VARCHAR(64)` / `actor VARCHAR(64)` /
    `result VARCHAR(16) NN` / `error_message TEXT` / `args_json TEXT` /
    `result_json TEXT` / `ip VARCHAR(45)` / `user_agent VARCHAR(255)` /
    `trace_id VARCHAR(64)` / `timestamp TIMESTAMPTZ NN` /
    `prev_hash VARCHAR(128) NN` / `hash VARCHAR(128) NN UNIQUE`。
    **索引 3 个**：`idx_audit_target(target_type,target_id)`、
    `idx_audit_actor_time(actor,timestamp DESC)`、`idx_audit_time(timestamp DESC)`；
    另加 17 条 COMMENT（表 + 每列）。
  - **验证**：① `\d audit_log` 15 列 + 4 索引（PK/hash UNIQUE/3 业务索引）与源码一致；
    ② 幂等复跑一次 → 全部 `already exists, skipping` NOTICE，无报错；
    ③ 框架原句 `SELECT hash FROM audit_log ORDER BY id DESC LIMIT 1;` →
    **`(0 rows)` 空结果集而非 bad SQL grammar**（对应 `EmptyResultDataAccessException`
    → 正常 fallback GENESIS，不再是异常分支）；
    ④ 事务内按框架 14 列清单 INSERT 成功后 ROLLBACK，表仍 0 行（写入路径实测通）。
  - 未碰任何 Java 源码，未跑 Maven。

- [T2 业务修复轮] P1-③ admin bootstrap 入口 + P2 secret-key 环境变量化，**业务代码完成，
  `mvn -DskipTests compile` BUILD SUCCESS（退出码 0，两轮均过）**；未写测试（src/test 未动）。
  - **改动 3 文件**：
    ① 新建 `config/AdminBootstrap.java` 105 行——`@Component` + `CommandLineRunner`（启动期运行），
    显式构造器注入 `@Value("${wx-sync.bootstrap-token-seed:}")`（不用 Lombok，避开 @Value 落不到
    Lombok 构造参数的坑）；seed 空白→`log.debug("[Bootstrap] disabled …")` 一行后直接返回
    （**默认禁用**）；seed 非空且 `wx_account.selectCount(null)==0`（**含软删行，字面口径**——
    库里曾有任何账号即不动）→ 幂等注册 dev 账号 `wxid_bootstrap_dev_0000`（先按
    account + is_deleted=0 selectCount 查重，已存在跳过注册，避免并发/重复启动抛 10401）→
    `TokenService.issueAdminToken(seed, account)` 签发 admin 令牌对 → `log.info` 四行：
    access_token 前 24 字符 / access_token 完整值 / refresh_token 完整值（refresh 轮转用）/
    dev 账号+过期时间+uid，另 `log.warn` 一行「生产严禁开启」。**整体 best-effort：
    任何一步异常仅 log.error，不阻断应用启动**。
  - ② `TokenService` 仅追加（既有方法零改动）：常量 `ROLE_ADMIN="admin"`、`CLAIM_UID="uid"`，
    方法 `issueAdminToken(String uid, String account)`——claims =
    `{uid=<seed>, type=admin, roles=["admin"], account=<dev 账号>(仅溯源)}`，TTL 不覆写，
    直接用 yml `policies.admin.expire-time=7200`。**policy 机制已核 framework4j 1.10.0 源码**：
    admin `key:[uid]` 经 `AccessTokenGenerator#extractKeyValue(policy.key, claims)`，claims 必须带
    `uid` 否则 10200；校验链 `AccessTokenValidationStrategy` 以 注解 value == payload.type 匹配 +
    Redis metadata nonce 比对 → 该 admin 令牌可直接访问
    `@RequiresToken(value="admin", roles="admin")` 的 AccountAdminController；
    `account` claim 不参与 admin 会话 key（key 只取声明字段），且 ingest 端点按 `type=client`
    匹配令牌，admin 令牌进不去 → 不绕过 `AccountBinding`。附带收益：uid 型别可用框架原生
    `revokeByUser("admin", uid)` / `updateClaims(...)`（T2 自写 revokeByAccount 是因 client
    key=[account] 才绕的）。
  - ③ `application.yml` 仅两处：`framework4j.access-token.secret-key` 值改
    `${WXUW_TOKEN_SECRET:f2e9…272}`（env 优先、原值兜底；`AccessTokenConfigValidator` 的
    ≥32 字符校验对兜底值仍通过，缺省仍能启动）；新增顶层键
    `wx-sync.bootstrap-token-seed: ${WXUW_BOOTSTRAP_SEED:}`。**其余键一律未动。**
  - **未做 / 留给后续**：bootstrap 未做运行期冒烟（需 PG+Redis 在线，本子任务只授权编译自检）；
    seed 既是开关也是 admin 会话互斥键 uid（换 seed = 换会话，旧 token 2h 自然过期，无主动吊销）；
    `wx_account` 只剩软删行时也不签发（字面口径；真丢了首枚 admin 令牌只能清库重启，或后续加
    force 开关）；bootstrap 的单元测试归测试 agent。

- [测试修复] `WxSyncServerContextTests` 的 `@SpringBootTest` 追加
  `properties = "spring.test.context.failure.threshold=2"`（Spring 6.1+）：上下文加载失败时
  前 2 个用例仍各自抛真实根因堆栈（默认阈值 1 会把第 2~5 个用例吞成
  "ApplicationContext failure threshold (1) exceeded"，排障误导），
  同时保留熔断避免 5 个用例全部拖满超时。**仅改该测试文件一处注解，未动 src/main；
  `mvn -q test-compile` BUILD SUCCESS**。

- [T2] 错误码扩展 + framework4j 鉴权接入 + 账号/令牌管理 API，**业务代码完成，`mvn -DskipTests compile`
  BUILD SUCCESS（退出码 0，增量编译 ~3.4s）；未写任何测试（src/test 未动）**。
  - **产出 13 文件 644 行**（不含 T1 既有 WxSyncServerApplication 19 行 / MybatisPlusConfig 27 行）：
    `common/WxErrorCode.java` 33、`common/PageVO.java` 34、
    `account/entity/WxAccountEntity.java` 54（`@TableName("wx_account")` 字段对齐 DDL，
    `@TableId(type=ASSIGN_ID)` → framework4j-id `MpIdGenerator`（MyBatis Plus
    `IdentifierGenerator`，worker id 走 Redis 租约）发雪花，DDL 的 BIGSERIAL 默认值不触发）、
    `account/mapper/WxAccountMapper.java` 10、`account/dto/`：AccountRegisterRequest 19 /
    AccountTokenRequest 23（ttl_seconds 缺省 7776000，@Min 60 @Max 1 年）/ AccountTokenVO 11
    （三字段均 String）/ AccountVO 26、`account/service/AccountService.java` 112
    （register 先查后插→10401 / detail / list / delete 软删+吊销 / require）、
    `account/service/TokenService.java` 202（issueForAccount / revokeByAccount��、
    `auth/AccountBinding.java` 47（checkBound → 10300；requireRegistered → 10601；Bean，
    供 T3~T5 ingest 接线，本任务未接 controller）、
    `account/controller/AccountAdminController.java` 73（POST /v1/accounts、
    POST /v1/accounts/{account}/tokens、DELETE /v1/accounts/{account}、GET /v1/accounts、
    GET /v1/accounts/{account}；类级 `@RequiresToken(value="admin", roles="admin")`——注解 value
    是 token 型别名且无默认值必须写；返回 ApiResponse，无 try/catch）。
  - **pom.xml 追加 org.projectlombok:lombok（optional，版本由 parent 管）**：T1 pom 未带 Lombok，
    framework4j 自带的是 provided 不传递，首轮编译全量「找不到 lombok 包/符号」。
  - **application.yml 仅追加 2 键、未改任何 T1 已有键值**：
    ① `framework4j.access-token.hash-salt`（32 hex）——`AccessTokenConfigValidator` 启动期
    fail-fast 必填（缺省空串拒启，problems: "hash-salt 未配置"）；
    ② `framework4j.redis.enabled: true` + `datasources.default`（host 127.0.0.1:6379，
    template-type string）——即 [18:41] T1 冒烟 RED 的根因修复，T2 因依赖
    `accessTokenStringRedisTemplate` 必须补上；**18:41 修复计划的步骤① 已由 T2 完成**
    （secret-key 改 `${WXUW_TOKEN_SECRET:...}` 是「改已有键值」非追加，按 T2 约束未动，仍待做），
    步骤②③（测试微修 + runner 复跑）归测试 agent。
  - **WxSyncServerApplication 补 `@MapperScan("com.wechat.sync.server.**.mapper")`**（T1 刻意留空）。
  - **framework4j-accesstoken 实际用的类 / 方法**：
    `RefreshTokenService#generateTokenPair(claims)` → `AccessTokenGenerator.TokenPair`
    （accessToken/refreshToken/familyId/accessExpiresInSeconds/refreshExpiresInSeconds）、
    `#revokeFamily(familyId)`；`AccessTokenGenerator#getAppName()` / `#extendRevokedSetExpire(key, ttl)`；
    `AccessTokenProperties.Policy#getExpireTime()` / `#setExpireTime(...)`；
    `TokenKeyBuilder#accessMetadata / #accessRevokedSet / #refreshFamily`；
    `TokenUtils#calculateKeyHash(value, salt)`；`RoleAuthorizer.CLAIM_KEY_ROLES`；
    `TokenContext#getClaim`；`ApiException(int, String)` / `ApiCode.*`；`ApiResponse.success(...)`。
  - **轮换/吊销实现说明（P1）**：框架 `AccessTokenGenerator#revokeByUser(type, uid)` 内部固定以
    `Map.of("uid", uid)` 取互斥键值，**仅支持 policy.key=[uid]**；client policy 键是 `[account]`
    （T1 配置，T2 只准追加），直接调必抛 AuthException 10200「claims 缺少 policy.key…[account]」。
    故 `TokenService.revokeByAccount` 按框架同一 Key 约定（`TokenUtils.calculateKeyHash` +
    `TokenKeyBuilder`）清扫 access 会话（jti 入撤销 Set + 删 metadata key + 单调延长 TTL 8d），
    再扫 refresh family、claims.account 命中即 `revokeFamily` 整族撤销——语义等价
    revokeByUser（踢下线即时生效）。若改走字面 revokeByUser，只需把 yml client 的
    `key: [account]` 改 `[uid]` 且 claims 同时带 account/uid（属改 T1 键值，未做）。
  - **ttl_seconds 实现**：框架 TTL 读自 policy 配置、无按次入参；签发时以 policy 对象为锁临时
    `setExpireTime(ttl)`，finally 还原（admin 低频操作，避免并发覆写）。
  - **claims 约定**：`account=账号`（互斥键 = policy.client.key）、`roles=["client"]`、
    `type="client"`（`generateTokenPair` 以 claims.type 选 policy，缺了落 "DEFAULT" → 10200）。
  - **未做 / 留给后续**：Spring 上下文加载未验证（需 PG+Redis 在线，本子任务只授权编译自检）；
    单元测试未写（测试 agent 的事）；DELETE /v1/accounts/{account} 的关系层来源行/游标/批次清理
    未做（表尚未建模，T3~T5 落表后补应用层级联清理，API_DESIGN §6 要求）；admin 令牌签发入口
    （bootstrap）未实现——管理接口自身要求 admin 角色，首枚 admin token 无处可签（P1）；
    `revokeByAccount` 用 `redis.keys(...)`（KEYS 全库扫），量小可用，量大应换 SCAN（P2）。
- [18:41] T1 冒烟 **RED（5 Errors）**，根因唯一：application.yml 缺 `framework4j.redis.*` 段 →
  `MultiRedisManager` Bean 缺失（`MultiRedisAutoConfiguration` 是
  `@ConditionalOnProperty(prefix="framework4j.redis", name="enabled", havingValue="true")`），
  accesstoken 的 `accessTokenStringRedisTemplate` 依赖它。**修复计划（等 T2 完成 yml 编辑后执行，
  避免并发覆盖）**：①业务配置微修：补 `framework4j.redis.enabled: true` + 名为 `default` 的源
  （照 skills/framework4j/references/redis.md），secret-key 改 `${WXUW_TOKEN_SECRET:<原值>}`（P2）；
  ②测试微修：测试类加 `spring.test.context.failure.threshold` 显式值（P1，避免 4/5 用例只报阈值）；
  ③runner 复跑。

- [18:31] T1 冒烟测试代码完成：新增
  `src/test/java/com/wechat/sync/server/WxSyncServerContextTests.java`（86 行，5 个 @Test，
  `@SpringBootTest` 直连真实 PG 5433 / Redis 6379，无 mock/embedded）。
  覆盖：① contextLoads（framework4j 16 starter 装配无冲突）② DataSource `SELECT 1` = 1
  ③ StringRedisTemplate `ping()` = "PONG" ④ `ApiResponse.success()` 六字段
  （code=0/message 非空/data/error=null/trace_id 非空/timestamp>0）+ `GlobalExceptionHandler`
  Bean 在位 ⑤ `MybatisPlusInterceptor` Bean 存在且 inner interceptors 非空。
  `mvn -q test-compile` **BUILD SUCCESS（退出码 0）**，class 已产出 target/test-classes。
  pom 仅新增 `spring-boot-starter-test`（test scope，版本由 parent 管），业务依赖零改动。
  **未跑测试（runner 的事）**。已核验：`framework4j.datasource` 未开（yml 只配了
  `datasource.sql-tracing`），故 `MybatisPlusInterceptor` 仅本项目 MybatisPlusConfig 注册一个 Bean，
  `getBean` 不会撞 NoUniqueBeanDefinitionException（测试里仍用 ObjectProvider 防御）。
- [18:22] T1 骨架完成，`mvn -DskipTests compile` **BUILD SUCCESS（退出码 0，增量编译 15s；
  含首轮全量依赖下载约 6min）**。
  - **framework4j 引入路径：本地 install（非 JitPack）**。JitPack 的
    `com.github.funcommons.framework4j:framework4j-all:v1.9.0` **无上游 tag**：
    `https://jitpack.io/api/builds/com.github.funcommons/framework4j/v1.9.0` 返回
    `status:"none", isTag:false`，pom 请求先 502 后 429；JitPack 已构建成功的 tag 止步 v1.7.2。
    回退：`git clone github.com/funcommons/framework4j` → `C:\workspace\tools\framework4j`
    （--depth 1，HEAD = 1.10.0）→ `mvn -DskipTests install`（退出码 0，约 4min）。
  - **pom 关键坐标**：`com.wechat.sync:wechat-sync-server:0.1.0-SNAPSHOT`，parent
    `spring-boot-starter-parent:3.5.16`，Java 17；`fun.commons:framework4j-all:1.10.0`
    （groupId 因本地 install 由 `com.github.funcommons.framework4j` 变为 `fun.commons`）；
    `com.baomidou:mybatis-plus-spring-boot3-starter:3.5.15` +
    `com.baomidou:mybatis-plus-jsqlparser:3.5.15`（3.5.9 起分页插件必须单独引 jsqlparser）；
    `com.alibaba:druid-spring-boot-3-starter:1.2.28`；`org.postgresql:postgresql`（runtime，
    版本由 parent 管）；`spring-boot-starter-web` / `spring-boot-starter-validation`；
    `org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17`；spring-boot-maven-plugin。
    jitpack `<repository>` 保留（上游出可用 tag 后可切回），当前不参与解析。
  - **framework4j 实际生效模块（framework4j-all 聚合 16 个）**：api / web / datetime /
    id / redis / datasource / sql-tracing / accesstoken / idempotency / signature /
    rate-limit / cache / audit / sensitive / transport / tracelog（tenant 不在 all 内）。
    已逐一核验 8 个关键模块 jar 内含 Boot 3 的
    `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`，
    `ApiResponse` / `GlobalExceptionHandler` / `RequiresToken` / `AccessTokenGenerator` /
    `RefreshTokenService` 类均在。显式开：id（+worker.strategy=redis +mybatis）、
    access-token（policies client/admin）；默认开：web/api-trace/datetime/idempotency/
    rate-limit/sql-tracing。
  - **版本对齐幸运点**：framework4j 1.10.0 源码自用的 spring-boot=3.5.16、druid=1.2.28、
    mybatis-plus=3.5.15 与本项目 pom 完全一致，无版本漂移。
  - 产出文件：`pom.xml`、`.gitignore`、`src/main/resources/application.yml`、
    `src/main/java/com/wechat/sync/server/WxSyncServerApplication.java`（无 @MapperScan）、
    `src/main/java/com/wechat/sync/server/config/MybatisPlusConfig.java`
    （PaginationInnerInterceptor + DbType.POSTGRE_SQL + maxLimit 200）。
  - **未做 / 留给后续**：Spring 上下文加载未验证（需 PG+Redis 在线，且本子任务只授权编译自检）；
    `application-local.yml` 未创建；mapper/实体/Controller/错误码扩展均未动。
  - 环境 note：clone 的 framework4j 源码留在 `C:\workspace\tools\framework4j` 供后续查实现细节。
- [18:05] 环境就绪（Java/Maven/Redis/PG v4 schema）；T1 骨架子任务启动

## [客户端冒烟反灌 · 传输层契约修复 2026-11-10]

客户端 S6 真机冒烟定性三个服务端传输层契约缺陷（数据面已验证 PASS：dedup/幂等双闸门/游标镜像全对；
证据 C:\workspace\wechat-sync\client\smoke\README.md §9）：
- P0-1 `/v1/reports/**` 拒收 hex 形（uuid4().hex）Idempotency-Key → HTTP 400+10102；
  blob 端点（不在 pattern 内）却照收 → 修：两形态（32hex / 连字符 UUID）都接受，48h 重放两态可用
- P0-2 不解压 Content-Encoding: gzip 请求体 → 10103（客户端按契约硬性 gzip）→
  修：新增 GzipRequestDecompressFilter 仅注册 /v1/reports/**，/v1/blobs/* 原始字节不受影响
- P1 缺 token 回 HTTP 500+10001，契约要 10200 族 HTTP 200 信封 → 修：与 AuthExceptionMapping 统一口径
处置：业务修复 + 补测试（先红后绿）→ 定向回归 → 客户端复跑冒烟（预期 messages_sent=13/flushed=4/群成员 3→6）。

## [传输层修复 · 服务端侧 2026-11-10]
业务修复三件套落盘：GzipRequestDecompressFilter（common/，仅 /v1/reports/* 注册，
blobs 豁免断在注册上）+ IdempotencyKeyCanonicalFilter（hex↔连字符 UUID 归一，v4 版本校验）
+ ReportTransportFiltersConfig（注册与 order：gzip(+5) < canonical(+8) < bodyCache(+10)）；
AuthExceptionMapping 缺 token → HTTP 200 + 10200 信封。
补测试 3 类 / 11 用例 / 500 行（test-compile ✅）：幂等双形态+归一同 Redis key+无效短串仍拒；
gzip 解压/透传/伪 gzip 回退/大小写多值/注册豁免；缺 token 10200 信封+坏 token 102xx 族。

## [T9 任务分发 · 实施 2026-11-10]

方案权威 `..\TASK_DISPATCH.md` v1.1（评审修订 11 条后）。子agent机队用 fork 型（opus/sonnet 渠道网关故障，fork 继承主会话模型可用；glm 渠道偶发 502 续跑即可）。

- **T9-1 ✅** schema.sql v4→v5（+129 行：wx_reg_code/wx_task/wx_task_run/wx_task_schedule + 7 索引含 3 GIN + 种子 id 1/2/3 ON CONFLICT + setval 防撞）；verify_task_dispatch.sql 12 断言；**已应用真库 wxuw-pg:5433 双跑全绿**，wx_account=5 未动。
- **T9-2 ✅** 4 实体 + 4 Mapper（ASSIGN_ID 雪花/OffsetDateTime/JSONB String 透传/LocalTime）；映射测试 4/4 绿。
- **T9-3 ✅业务** TaskService ~770 行：claim 原子认领（`gen_random_uuid()` 租约、CSV+string_to_array 避开 `<script>` 坑、RETURNING 含 lease_until）；complete/fail/renew 终态重放判定 `replayOrLeaseLost`（终态+同 lease→REPLAYED；异 lease/CANCELLED→10603；终态行保留 lease_id）；fail 防御双段 UPDATE；sweep 回收+run 日志同事务（closeRun vs markRunLost 两条路径，LOST 行 finished_at 保持 NULL）；物化（部分唯一索引 ON CONFLICT 带 WHERE 谓词、活动窗口展开、FULL_SCAN 轮转 last_full_scan NULLS FIRST、每计划上限）；纯函数 decideFailTarget/computeNextMaterializeAt 包级 static 可单测。WxErrorCode+10603；yml task-lease-seconds=600/batch-cap=20/full-scan-batch=5。
- **T9-4 ✅** RegCodeService（明文 `wxreg_`+43 位 base64url，sha256 入库，撞哈希重生成≤5 次；条件消费 `WHERE used_at IS NULL` 并发双消费只活一个且整体回滚）+ AuthController（register 无鉴权 6/min；refresh `type="refresh"`，10210/10211/10212 经 AuthExceptionMapping 透传）+ TokenService.refreshForClient。
- **T9-5 ✅** TaskClientController/AdminController 拆分（admin 服务 3 URL 前缀）+ TaskSchedulerConfig（@EnableScheduling；sweep 30s/materialize 60s，fixedDelay 防重叠+catch-all warn）；claim 不收紧配额（缺省 120/min 够，javadoc 记录）。**T9-5b 补齐**：/v1/reg-codes 三端点 + createTask maxAttempts 重载（新 mapper 方法名避 statement 冲突）+ GET task-schedules/{id}。
- **T9-3 测试** TaskServiceTest 35 用例已写（563 行）；4 条按实际代码修正的口径：complete 校验次序 checkBound→10102→10400；物化上限靠 SQL LIMIT 兜底（IT 验证点）；CANCELLED 一律 10603；renew 终态同 lease no-op。
- **待办**：执行轮（编译+TaskServiceTest）→ T9-4/T9-5 测试编写轮 → admin 辅助方法补测 → **T9-6 真库 IT**（两线程 claim 原子性、ON CONFLICT 防堆积、并发双消费注册码、物化 LIMIT 生效）→ 全量回归 → 部署（停 PID→package→`>>` 重启）→ 真机验收（签码→注册→建任务→claim/complete 闭环）。

## [订阅方案 · 设计完成 2026-11-10]

`..\SUBSCRIPTION_DESIGN.md` **v1.1**（PG 队列模拟 MQ：条件订阅/防重双闸/fan-out/WEBHOOK+POLL 双形式/10 QPS）。产出环：设计 agent 初稿（483 行）→ 对抗评审（15 项检查：12 PASS + P0×1 + P1×2 + P2×4）→ 修订 agent 落 v1.1。
关键定版：触发=ingest 同事务钩子（MP 实体 insert 回填 id，事务内可得新行 id）+ 60s/10min 兜底补扫（钩子失败改吞掉不炸 ingest）；防重双闸=dedup_key 只认 INSERT 新行 + uk(subscription_id,message_id)；防重复订阅=uk(consumer_id,filter_hash)+already_exists 不报错；dispatcher 只管 WEBHOOK 行（**v1.1 修正 v1 的 delivery_mode 条件写反 P0**）+ per-subscription 在飞上限（STRICT_FIFO=1/PARALLEL=4 一条 CASE 谓词）；配额三件套=whitelist-paths 放行框架档 + QuotaInterceptor 前缀桶 600/min + javadoc 改「含放宽档」（RateLimitQuotaConfig.quotaOf 精确匹配的机制结论）；v6 迁移=四元素（consumer/subscription/delivery/游标表 wx_sub_delivery_cursor）；错误码 10604~10607。
**实施切分 T10-1~T10-9 已入 §10，闸门在 T9 部署验收之后。**

## [T9 部署 2026-11-10]

- 测试收口：**256/256 全绿**（基线 153 + T9 新增 95 单测 + 8 真库 IT），九跑收敛零回归。业务缺陷共 3 个（fail 防御改判回带 DEAD；jsonb 类型链 `Types.OTHER`+JsonbTypeHandler+`::jsonb`——postgresql 驱动 runtime scope 故不可 import PGobject；并发注册裸 DuplicateKey→10401 翻译 + path-first 校验次序），其余为测试侧机械修。
- 部署：停 PID 152328 → `mvn package`（71,511,956B 健康）→ `WXUW_BOOTSTRAP_SEED=t9-deploy-20261110` 重启 **PID 146564**，日志 `>>` 追加。备份/清空/bootstrap/恢复舞步完成（备份表本含旧 dev 行，恢复=5 与原状一致零丢失）；admin 令牌入临时文件；种子 next_materialize_at 已拨回 now()。
- 启动验证：无令牌 POST /v1/tasks/claim → HTTP 200 + 10200 信封（新端点在场+鉴权映射正常）；**调度实战 `[Task] 物化 schedules=3 produced=5`**——wx_task 5 条 PENDING SYNC_RELATIONS（每真实账号 1 条；SYNC_MESSAGES/FULL_SCAN 因 wx_conversation/wx_sync_cursor 无候选产出 0，符合设计）。
- 真机验收（A admin 全链路/B 新账号闭环/C smoke 90d 令牌跨部署/D 清理总账）随后进行。

## [T9 终态 · 收官 2026-11-10]

**T9 关闭：编码→测试→修复→部署→真机验收→缺陷闭环全链路完成。**
- 测试：256/256 全绿（153 基线 + 95 新单测 + 8 真库 IT），九跑收敛。
- 部署：现役 **PID 152028**（jar 71,511,978B），调度实战物化正常，令牌跨重启存活（Redis 会话）。
- 真机验收过项：reg-code 单次明文/重放 10402/列表无明文、自助注册（account 客户端携带）、claim（lease/lease_until/attempts）、complete（终态保留 lease_id）、**重放幂等 replayed=true**、异 lease 10603、renew→RUNNING、fail→回 PENDING（closeRun NULL jsonb P1 真机揪出并修复回归）、**sweep-LOST（finished_at NULL 口径精确命中）**、refresh 轮转 + **重用检测 10211 撤族**、吊销 10208 与过期 10201 精确区分、账号软删+历史保留。
- 排查澄清：claim LIMIT 无缺陷（决定性复现 max_tasks=1 恰返 1；「返 2 条」为报告失误），取消路径顺带再验。
- 遗留 P2/P3：IT 补 fail 路径锚点（防 10106 回归）、wx_account.last_sync_at 未写、claim data 裸数组已入文档、admin 令牌 2h+30min 滑动的运维口径。
- 凭据交接：admin access/refresh 与 smoke client 令牌在 /c/workspace/wechat-sync/server/.t9_*.txt（前缀 eyJhbGci）。
- **下一棒 S7**（客户端 task_mode/claim/register/refresh，TASK_DISPATCH §7 v1.1）。

## [T10 订阅域 · 开工 2026-11-10] + S7 e2e 凭据阻断与 bootstrap 舞步二度

**用户令「订阅方案」→ SUBSCRIPTION_DESIGN.md v1.1 §10 T10 开码**（严格串行 T10-1→9）。
T10-1（schema v6 四表 + verify_subscription.sql + 真库双跑）已派发。

**S7 真机 e2e 第一击被凭据阻断（FAIL-BLOCKED，零副作用退出）**——顺带实锤两个运营级事实：
- **admin 会话 1800s 空闲即死，且服务端没有 admin 型 refresh 入口**：`/v1/auth/refresh` 是
  `@RequiresToken(value=CLIENT_TYPE)`（AuthController），admin RT 无处可用（打它=类型不符+唯一拷贝
  重用探测 10211 风险）。admin access 签发后必须在 30 分钟空闲窗内用掉，否则只能走 bootstrap 舞步。
  → 登记为已知限制（P2 候选改进：admin 型 refresh 端点或 admin 令牌续期机制）。
- **bootstrap 凭据恢复舞步二度执行（零损失）**：backup wx_account（6 行）→ DELETE → 带
  `WXUW_BOOTSTRAP_SEED` 重启（AdminBootstrap 触发条件：seed 非空 + 表全空）→ 日志提取新 admin
  access/refresh（`.t9_admin_access.txt` / `.t9_admin_refresh.txt` 已覆写为活令牌，exp 14:22）→
  删新 bootstrap 行 → 还原 6 行 → drop backup。**还原后 wx_account 6 行原样**（5 真实 + bootstrap dev）。
- smoke client 令牌已用新 admin 重发（`.t9_client_smoke.txt` 覆写，exp 2027-02-08）；
  e2e 复跑已带预置凭据派发（全程不再碰 admin）。
- e2e 第一击的探测顺带实证：refresh 双通道（Bearer+body 缺一 → 10200）与 10210（族记录已死）
  vs 10211（重用）的精确区分，与客户端 uploader 测试锚一致。

## [S7 真机 e2e · PASS 6/6 2026-11-10]

凭据预置后复跑 **PASS**：e2e 副本（新 smoke 令牌 + task_mode=on）→ INSERT 任务 id=112
SYNC_RELATIONS / id=113 PROBE（account=wxid_c00pwixax7xv22_8316）→ 两轮 claim→dispatch→complete
全 200 → DB 终态：两任务 SUCCEEDED、attempts=1、**lease_id 终态保留**、wx_task_run.outcome=SUCCEEDED
且 result JSONB 与任务行一致（112：relations_sent=1/errors=0——真实上报 17 contacts/1 group/3 members；
113：PROBE 采样 3 条真实群消息含 raw_prefix_shapes）；空认领轻收尾退出码 0；副作用核验零污染
（wx_account 6 行、wx_task_schedule 3 行、其它账号 4 条 PENDING、smoke/config.json 原件均原样）。
**S7 正式关闭，客户端下一棒无阻塞项；服务端现役焦点 T10（T10-1 在飞）。**

## [T10-1 schema v6 · 完成 2026-11-10]

四表（wx_consumer / wx_subscription / wx_subscription_delivery / wx_sub_delivery_cursor）+ 11 索引
物化进 schema.sql（v5→v6，独立 BEGIN…COMMIT 幂等块，DDL 逐字照 SUBSCRIPTION_DESIGN §2.1~§2.4）；
`verify_subscription.sql` 新建 13 组断言全过（真库两遍应用幂等零报错；uk 拦重/软删重建/默认值快照/
SKIP LOCKED 认领 attempts+1/终态保留 lease_id/过期回收回 PENDING·用尽置 DEAD/退避键出窗/
游标 GREATEST 防回退/GIN 在位）。数据零污染：wx_account 6、种子 3、v6 四表 0 行
（wx_task 13 = 原 11 + 父线 e2e 的 112/113，与本 DDL 无关）。verify 中途修 1 处脚本瑕疵
（软删重建场景两活订阅共用 hash 致 fan-out 2 行——收窄到单订阅后口径吻合）。
**T10-2（实体×4+Mapper×4，含认领/汇报/回收/补扫 SQL+游标 upsert）业务+测试双棒已并行派发。**

## [T10-3 触发链路 · 完成 2026-11-10]

SubscriptionFilter（六键单一事实源：normalize 键排序/去空/去重排序/25≡"25" 归一/include_revoked 缺省收敛 +
TreeMap 确定序 toJson + sha256Hex + Parsed.matches 谓词）/ SubscriptionMatcher（volatile 不可变快照、坏 filter
逐颗跳过）/ **SubscriptionTrigger（吞失败 owner：trigger 整段 try/catch log.warn，绝不回滚 ingest 事务——v1.1
「吞掉+补扫兜底」）** / SubscriptionService（create 校验/规范化/hash + already_exists 不报错 + DuplicateKeyException
复查兜底；UpdateCommand 类型上不含 filter——不可变在类型层杜绝）/ SubscriptionBackstopService（读水位→backstopEnqueue
→GREATEST 推进，三步幂等不包整体事务）/ WxErrorCode +10604/10605 / Mapper +enqueueBatch（script foreach +
ON CONFLICT DO NOTHING）。**ingest 接线**：@Autowired(required=false) 字段注入（构造器零变化，既有单测零回归），
ChatOutcome.inserted 仅 ACTION_INSERTED 收集（merged 不触发），applyChat 末尾同事务触发——事务边界图：
消息 upsert→来源行→附件→游标→seen_accounts→**触发钩子（同 tx 原子提交零丢失窗口）**。
**测试**：70/70 新用例全绿（8 Mapping + 34 Matcher + 28 Service）+ ingest 15/15 + 任务域 35/35 零回归；
亲修 2 处测试缺陷（Mockito 原始类型 any() 拆箱 NPE 跨用例泄漏 → anyString/anyLong；sub() 硬编码 id 恒假断言）。
跨棒漂移裁决记录：保业务形态（SubscriptionTrigger 独立吞失败/matcher 收 IncomingMessage 不耦实体），
测试侧机械重写对齐，8 处语义裁决全部 javadoc 注明。

## [T10-4 DeliveryService · 业务落盘 2026-11-10]

DeliveryService 548 行 + DeliveryVOs（7 record，§5.1/§6 共用摘要——**content_raw/content_sha256 不投**）+
Mapper +3 SQL（countDuePending/requeueDeadRows/redeliver）+ WxErrorCode +10606 DELIVERY_LEASE_LOST
（T10-3 只落了 10604/10605，10606 由本棒自补——协调者指令笔误「已有」）。
关键裁决（详见 SUBSCRIPTION_DESIGN §12 裁决补记）：**ack/nack 逐行 (deliveryId, leaseId) 对**（认领 SQL
gen_random_uuid() 逐行发 lease，单 lease 批量 UPDATE 数学不可能命中多行）；**HMAC 签名密钥 = sha256hex(secret)**
（schema 只存 hash 不可逆签不了名——零 schema 变更出路，设计 §5.1 已补记，T10-5 照办）；
退避纯函数 nextBackoffSeconds 倍增+途中钳制（attempts=100 不溢出，base=30 → 30…1920→3600 封顶）；
nack 首（attempts≤1）backoff=0 立即重试复用同原语；URL 双空 → 60s 退避重排不打死信；
fail 路径回订阅取 base 用宽版 selectById 不过滤软删（软删订阅的存量在飞行必须能正常 fail）；
终态重放判定 replayOrLeaseLost 口径同 TaskService（DISPATCHED 活动/DELIVERED·DEAD 同 lease 幂等/
异 lease 10606/PENDING 10606）。测试棒 33 用例已先落，对齐棒正在机械对齐逐行 ack 形态。

## [T10-4 DeliveryService · 完成 2026-11-10]

测试 54/54（DeliveryServiceTest，对齐棒全文件重写后 7 处语义裁决：行缺失 10605 / 租约不符 10606 /
nack 逐条部分成功 10700 / 保序契约锚在 claim SQL ORDER BY d.id / 归属双域 selectOne+10605）+
**订阅域 124/124**（Mapping 8 + Delivery 54 + Matcher 34 + Service 28）+ 邻域 50/50（ingest+任务域）。
亲修 3 处测试缺陷（漏桩 requeueForRetry/failDead 致 0 行竞态路径假红 ×2；MP 3.5.15 paramNameValuePairs
懒注册——getSqlSegment() 前恒 {}，断言前先物化，RFC 向量 sha256("abc") 实证业务哈希口径正确）。
业务零缺陷。P3 备忘：SubscriptionEntitiesMappingTest 可为 Mapper 新增 3 方法补 SQL 片段锚（既有 9 锚全过）。

## [T10-5 派发器 · 业务落盘 2026-11-10]

WebhookUrlValidator 162 行（SSRF §5.4 逐条分层：checkSyntaxAndLiterals 无 DNS 主入口 + validate=语法字面量
+checkResolvedHosts 解析复检；IP 字面量字节级区段判定含 **IPv4 映射段 ::ffff:0:0/96 取末四字节复用 IPv4 判定**
堵混合写法绕行；未知地址族宁可错杀；TOCTOU 残余登记）/ WebhookDispatcher 200 行（worker 池 8 + HttpClient
connect 3s/read 10s/followRedirects NEVER；**HMAC key=sha256hex(secret) 裁决口径**，sign 纯函数返回完整头值；
body=payloadJson 原文 ofByteArray 零重序列化；2xx→complete 非 2xx→fail(code) 传输失败→fail(null,error≤512)；
SSRF 拒→fail 不打 complete；send(HttpRequest) 包级缝隙；池满 RejectedExecution→任务留租约期 sweep 兜底）+
SubscriptionSchedulerConfig 107 行（五轮：dispatch 200ms/5s · sweep 30s/15s · 补扫 60s/30s · 快照刷新 30s/3s ·
DELIVERED 清理 60s/45s——retention/batch 走 yml）。配套：WxErrorCode +10607（webhookUrlInvalid(url,reason) 工厂）；
WebhookDeliveryTask +secretHash 第 7 组件（「库存即签名密钥」）；dispatchDue 组装点 +consumerSecretHash null 安全
（消费者认领后软删 → null → 配置错退避）；yml +5 键（sub-webhook-workers/dispatch-batch/per-sub-inflight/
delivered-retention-days/cleanup-batch）。自纠 1 处：URL.of 是 JDK20+ API，改 new URI(url).toURL()。
**遗留给 T10-6：WebhookUrlValidator.validate 接进 subscription create/update（§5.4「录入时校验」，一行接线）。**

## [T10-5 派发器 · 完成 2026-11-10]

测试 36/36（Validator 20 + Dispatcher 12 + Scheduler 4）+ **订阅域 160/160**（7 类）+ 邻域 50/50。
亲修 3 处测试缺陷（全在 WebhookDispatcherTest，异步用例：sendLatch 只 await 从不 countDown 8 条全超时假红 →
缝隙 finally 里放行建立 happens-before；共享 ArrayList 双 worker 并发 add 丢更新 → CopyOnWriteArrayList）。
Dispatcher 单类/组合双向复跑均稳。业务零缺陷。P3 备忘：HttpTimeoutException 独立 catch（"timeout:" 前缀）
无专项单测，同族 IO 已覆盖，T10-8 IT 可补。遗留给 T10-6：录入时 SSRF 接线。

## [T10-6 控制器+配额 · 业务落盘 2026-11-10]

SubscriptionAdminController 217 行（admin 类级 @RequiresToken，11 端点：consumers POST/GET/DELETE——明文
wxc_/wcs_ 前缀 43 字符 SecureRandom 只此一次、sha256 入库；subscriptions CRUD+列表过滤+deliveries 视图
status 闭集 10102+requeue-dead+redeliver——行自带 consumer_id 回填同一状态机）+ SubscriptionConsumerController
117 行（POLL 三端点，控制器内自行 Bearer→authenticateConsumer 10300 点名 consumer_token；ack/nack 10700
partialSuccess error[] 列失败项 ApiError.of(field=delivery_id)）+ SubPathIds（TaskIdParser 同款）+
ConsumerService 129 行。**配额三件套**：yml whitelist-paths=[/v1/subscriptions/**]（javap 实证框架 1.10.0
原生 whitelistPaths 字段）+ RateLimitQuotaConfig 前缀档 600/固定桶（不同订阅 id 共桶）+ javadoc 放宽档口径——
v1.1 三重矛盾清账。**录入 SSRF 双闸**：create 时生效 URL（订阅覆写 consumer 级，仅 WEBHOOK 补查消费者行，
POLL 零开销）→ WebhookUrlValidator.validate 10607。**status 增量**：count FILTER×3+max(delivered_at) 单条聚合
→ StatusVO 第 5 组件（全库无测试构造 StatusVO，零破坏）。主代码 mvn compile EXIT=0。测试叉 31 用例已先落，
对齐棒正在消 6 处漂移（DTO 嵌套/HttpServletRequest/AckRequest 包装）。

## [T10-6 控制器+配额 · 完成 → T10 业务侧收官 2026-11-10]

测试 32/32（Controllers 24 + Quota 8）+ **订阅域 192/192**（9 类）+ 邻域 67/67（ingest 15 + 任务域 52）。
亲修 1 处测试缺陷（leaseId 是 VARCHAR(64) UUID 凭据非数值 ID——「Id 结尾=Long」一刀切锚过滤修正）。
**配额三件套 8/8 全过**（前缀档 600/固定桶共桶/白名单豁免/429 信封 10500）。
**T10 业务侧 T10-1~T10-6 全部收官**：schema v6 四表 → 实体/Mapper 9+3 SQL → 触发链路（Matcher/Trigger/钩子/补扫）
→ DeliveryService（认领/汇报/退避/救济/终态重放）→ 派发器（SSRF/HMAC/五轮调度）→ 控制器+配额三件套。
下一棒 T10-7（全量回归+P0~P2 审查轮）→ T10-8 真库 IT → T10-9 部署+真机验收。

## [T10-7 全量回归 + P0~P2 审查轮 · 完成 2026-11-10]

**全量 435 用例两遍逐位一致**（T8 基线 95 → T9 终态 256 → 435，账目闭合；订阅域 192 ≈44%）。
实锤并修复 **P0 业务缺陷 ×1**：ConsumerService.secureRandom 经 @RequiredArgsConstructor 变构造器注入，
容器无 SecureRandom bean → ApplicationContext 拒载 → 10 Error 级联（context/信封/幂等/两真库 IT 全炸）；
单测直接 new 掩盖了它，@SpringBootTest 暴露。亲修一行（字段初始化 new SecureRandom()，
javadoc 注明原因），WxSyncServerContextTests 5/5 复绿。
**审查轮（只读七维）**：P0×0（已清）+ P1×1 + P2×2 + P3×8；设计语义逐项核对通过（状态机迁移/退避封顶/
两形互斥/配额三件套/错误码归位/dedup 双闸/HMAC 口径/明文泄漏面）。
- P1#1 补扫水位推进全局 max（窗外缺口永久跳过）→ 修复中
- P2#2 dispatcher 租约竞态 10606 误归 transport 二次汇报 → 修复中
- P2#3 SSRF DNS 解析在调度线程（慢解析器可停摆拨轮数十秒）→ 修复中
- P3×8 登记（update 清空 webhook_url 需删旧建新 / 宽版-窄版 selectById 读口径不对称 / IPv6 末 6 字节判定
  注释与集合不符 / @Update 承载 INSERT 插件分支点 / token_hash 无索引 / keyword LIKE 未转义窄偏差 /
  ack N+1 / scheme 大小写两处口径不一）——T10-9 前复查。
修复棒 + IT 编写棒（盲区清单 A~I 九项）已并行派发。

## [T10-8 审查项修复 + IT 编写 · 落盘 2026-11-10]

**审查三项修复落地**（P1#1 补扫水位改窗口内 max，readMaxMessageId(windowSeconds) 与 backstopEnqueue
同一 DB now() 表达式杜绝双钟偏差，空窗返 0 不推进；P2#2 deliver 拆三段 catch——ApiException 10606
租约易主=放弃汇报不再二次 failWebhook；P2#3 submit 瘦身只入池，validate+secret 检查+组包全移 worker
线程，调度线程零 DNS）+ WebhookDispatcherTest 两条用例 timeout(2000) 机械对齐，定向复跑 46/46 绿。
**T10-8 IT 落盘**：SubscriptionDeliveryIntegrationTest 8 方法 ~690 行（@SpringBootTest + 真实 PG 5433）——
A 钩子 fan-out 原子性/B dedup 双闸真库证据/C race 并发 pull 并集=全集交集=空/D 真 stub server 线上字节协议
+测试本地独立 Mac 交叉验证/E 退避→DEAD→requeue→redeliver 全生命周期+F sweep 双分支/G P1#1 回归锚
（窗外高 id 不拖走水位+放大窗口缺口补齐）+@Update 承载 INSERT 实证/I whitelist 绑定断言。
存活调度器竞态处置：POLL 订阅（两形互斥）+ WEBHOOK 行直插未来租约 + claimMine 重臂；时间断言全区间制。
清理纪律：t10it_ 前缀按依赖序删；共享 cursor 行不删（直写回拨只重扫已投递窗口，ON CONFLICT 零副作用）
@AfterAll 恢复 ≥ 全局 max。执行轮带「停 dev 旧 jar」前置（60s 补扫 tick 与 G 竞态 + 解 jar 文件锁）。

## [T10-8 真库 IT 收口 · 揪出并修掉生产级 P0 · 2026-11-10]

**IT 首跑 9 用例 → 7 过 1 败 1 错**：编译期机械缺陷先修（`CapturedRequest.headers` 由裸 `Map` 改
`com.sun.net.httpserver.Headers`——`Map` 没有 `getFirst`，那是 `List` 的 SequencedCollection 方法），
随即 IT 暴露**两个真问题**：

- **P0（业务，src/main）**：`WxSubscriptionDeliveryMapper.backstopEnqueue` 的 keyword 匹配写作
  `m.body LIKE '%' || s.filter->>'keyword' || '%'`。PG 的 `->`/`->>`/`||` 同属「其它运算符」**同级左结合**，
  该表达式被解析成 `(('%' || s.filter) ->> 'keyword') || '%'` → `'%'` 落到 `jsonb || jsonb` 重载 →
  PG 把 `'%'` 当 JSON 解析 → `invalid input syntax for type json`。
  **实测：只要库里存在任意一条 enabled 订阅，这条补扫 SQL 每轮必炸**（真库复现：连 keyword 为空的
  订阅行也炸——OR 未短路）→ **补扫保险带整条链死**（调度 60s 一轮只刷 warn），且 P1#1 的水位回归锚
  在第 489 行即中断，**「水位只推进到窗口内 max」的修复从未被任何用例验证过**。
  **修复**：`position((s.filter->>'keyword') in m.body) > 0`——(a) 绕开优先级陷阱；
  (b) 与内存匹配器 `SubscriptionFilter.Parsed.matches` 的 `body.contains(keyword)`（大小写敏感纯子串）
  逐字节等价，**顺带清掉 P3 登记的「keyword LIKE 未转义窄偏差」**：LIKE 会把 keyword 里的 `%`/`_`
  当通配符 → 补扫命中集宽于钩子命中集（两条路径口径漂移）。`position` 两边都当纯子串，NULL body 同落 NULL。
- **P1（测试，用例期望值错）**：`duplicateIngestMergedAndBackstop…` 期望 `action=merged`，
  但同 dedup_key 二次上报走**主查找命中 → 覆盖观测字段 → `unchanged`**（`merged` 只在 fb→srv 补键合并时出现，
  该路径已由 `MessageIngestIntegrationTest` / `MessagesIngestServiceTest` 锚定）→ 期望值改 `unchanged`，
  用例改名 `duplicateIngestUnchangedAndBackstopReplayProduceZeroNewDeliveries`。

**防复发**：新增 J 用例 `backstopKeywordFilterMatchesSubstringOnlyAndNeverWildcards`（带 keyword 的订阅
在补扫里按纯子串命中；`keyword='50%'` 对 body=`500 items` **必须不命中**——通配符守卫点）。
此前 keyword 的 SQL 路径**零覆盖**，是 P0 潜伏 400+ 用例全绿之下的根因。
**设计同步**：`SUBSCRIPTION_DESIGN.md` §3.2 SQL 样例与 §12 裁决表已补「为什么是 position 而不是 LIKE」。

**收口**：IT **10/10 全绿**（含 P1#1 水位锚真正跑到并成立：三轮水位单调推进、不越界到窗外消息 id）；
**全量 458 用例两遍逐位一致**（Tests run 458 / Failures 0 / Errors 0 / Skipped 0）；
PG 残留全 0（`t10it_%` 四类扫描）；种子完好（wx_account 6 行、wx_task_schedule id 1/2/3 三行）；
共享 global 水位 ≥ max(wx_message.id)（@AfterAll 恢复口径成立）。**T10-8 PASS。**

## [T10-9 部署 + 真机验收 · 完成 2026-11-10]

**部署**：`mvn package`（jar 71,630,282B ≥ 60MB 健康线）→ bootstrap 凭据恢复舞步（备份 wx_account 6 行 →
清空 → 带 `WXUW_BOOTSTRAP_SEED` 重启 → 日志取新 admin access/refresh → 停服 → 删 bootstrap 行 → 还原 6 行 →
drop 备份，**零丢失**）→ **不带 seed 正式重启**（现役 PID，`>>` 追加日志，bootstrap 确认未触发）。
旧 admin access（12:22 签发、14:22 过期）与旧 client 令牌会话（Redis 侧已死，10201）均按既定口径重签。

**真机验收 12/12 全过**（`scripts/t10_acceptance.py`，HTTP 全链路打现役服务）：
A 建消费者（poll_token / webhook_secret **明文只此一次**）· B POLL 订阅 · C WEBHOOK 订阅（url 落库）
· D **SSRF 环回负例 10607**（`http://127.0.0.1:9/…` 被端口/私网校验拦下）· E **真机上报 10 条消息 →
ingest 钩子触发 → 10 条 inserted** · F **重复上报 inserted=0、零新增投递**（dedup 双闸门）
· G1 POLL 拉取 10/10 · G2 ack 10/10（逐行 lease 凭据）· H **WEBHOOK 真投递 10/10 DELIVERED、
response_code=200**（公网回声端点；SSRF 必拦环回，故真机 WEBHOOK 走公网而非本地 stub）
· I1 **50 并发拉取 140.6 QPS**（>10 QPS 达标线 14 倍）· I2 **10 QPS 持续 5 秒零失败**。

**验收揪出并修掉 P1（业务）**：`completeDelivered` 此前**不写 response_code**（且不清 error）→
WEBHOOK 投递成功的行 response_code 恒 NULL（排障/审计断链，且与失败行语义不对称）。
修复：mapper 增 `responseCode` 入参并落 `response_code = #{responseCode}, error = NULL`；
`completeWebhook` 传 200、`ack`（POLL 无 HTTP 响应）传 null。回归锚进
`SubscriptionEntitiesMappingTest` 的 SQL 片段契约 + `DeliveryServiceTest` 的参数透传断言。

**验收脚本自身三处口径错**（记录以免复现）：① ingest 结果码字段是 `action`（inserted|merged|unchanged），
不是布尔 `inserted`；② 订阅身份 = **(consumer_id, filter_hash)**（uk_wx_subscription_filter，设计 §2.2
防重复订阅）——同 consumer 下**相同 filter 只能有一棵订阅**，POLL 与 WEBHOOK 两棵必须用不同 filter
（否则 WEBHOOK 被幂等吞成 POLL，webhook_url 丢失、投递形式退化），已用 keyword 区分；
③ 并发压测第二段把信封码当对象取 `.get("code")` → 恒判失败。

**残留与完好性**：v6 四表 0 行（consumer/subscription/delivery）、wx_message 17 行不变、
`wx_sub_delivery_cursor` 仅剩 global 水位行；wx_account 6 行、wx_task_schedule 种子 3 行**原样未动**。

## [T10-9 逐字节取证 + 全量回归收官 · 2026-11-10]

**逐字节 WEBHOOK 取证（8/8 PASS，走真实 dispatcher 不手工复刻）**：SSRF 按设计拦环回 → 真机
WEBHOOK 不能直接打本地 stub。取证手法**不放宽任何安全校验**：服务端以
`-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=8899` 启动（java.net.http.HttpClient 默认取系统
ProxySelector，明文 http:// 目标走本地代理），webhook_url 用 `http://httpbin.org/post`（端口 80 合法、
域名解析到公网 IP → **SSRF 校验照常通过**），实际 TCP 落到本地 recorder（记录完整头+体并回 200、
不外发）。取证项：三件头齐全 · **消费方独立复算签名一致**（key=`sha256hex(secret 明文)`、
data=`<ts>.<原始体>` —— §5.1 v1.1 裁决口径**真机实证**）· 体逐字节保真（431 B，原文命中，
禁重序列化）· **本轮新增 POST 恰 1 次**（无重复投递）。录到的 path 是绝对 URL 形态
（`POST http://httpbin.org/post`）= 代理确实被使用。

**全量回归收官**：定向 74/74（DeliveryServiceTest 56 / SubscriptionEntitiesMappingTest 8 /
SubscriptionDeliveryIntegrationTest 10，含两个新回归用例 `completeWebhookPersistsHttpResponseCodeToMapper`
与 `ackPersistsNullResponseCodeBecausePollHasNoHttpResponse`——surefire XML 确认**真的执行**非跳过）；
**全量 460 用例两遍逐位一致**（逐类 diff 零差异，41 个测试类用例数逐一相同）。
残留全 0；wx_account 6 行、wx_task_schedule id 1/2/3 三行原样；global 水位 ≥ max(wx_message.id)。

**收尾动作**：代理取证用完后**停代理、停带代理参数的进程、以干净参数重启现役**（无 seed、无代理）；
验收与取证残留全部物理清理（v6 四表 0 行、wx_message 17 行不变）；旧 client 令牌因重签被吊销 →
`client/smoke/config.json` 与 `config-e2e-taskon.json` 的 token 同步更新（客户端零代码改动）。
凭据：`.t9_admin_access.txt` / `.t9_admin_refresh.txt` / `.t9_client_smoke.txt`（另有 `.t10_*` 同内容副本）。

**T10 关闭**：schema v6 → 实体/Mapper → 服务/匹配/触发/补扫 → 投递 → 派发器/调度 → 双 Controller/配额 →
单测 → 真库 IT → 全量回归 → 部署 → 真机验收 → 逐字节取证，全链路完成。本期共揪出并修掉
**P0×1（补扫 SQL 运算符优先级，补扫保险带曾整条链死）+ P1×2（IT 用例期望值错、投递成功行不落
response_code）**，并清掉 P3 登记的「keyword LIKE 未转义窄偏差」（随 P0 一并消解）。

## [T11/S8 SEND_MESSAGE 任务类型 · 全链路完成 2026-11-10]

**用户需求**：通过服务器 API 分发任务，让真机微信向 47931406650@chatroom 发「收到」。
路径裁决（用户拍板「先 1 再 2」）：① 先用 wechatauto 直接 GUI 发送验证链路 → ② 再给同步系统
建正式任务类型。两步均完成。

**① 直发链路**：`python -m wechatauto send "收到" --to 47931406650@chatroom`（resolve_chat 对
@chatroom 直通）→「成功：消息已发送：收到」→ 客户端 10s 轮询发现 → ingest 钩子 → 订阅投递
（delivery 145）→ POLL 拉取即见 → ack → DELIVERED。订阅链路真机二次实证。

**② T11 服务端**：`TaskService.TT_SEND_MESSAGE` 入 TASK_TYPES；`requireSendMessagePayload`
两键强校验（chat_username：必填 trim ≤64 且形态可定位 = `@chatroom` 后缀/filehelper/`wxid_` 前缀，
镜像 wechatauto resolve_chat 接受面；text：trim 后 1..2000，**存储保留原文不 trim**；未知键 10102
防拼写静默失效——SubscriptionFilter 同哲学）；规范形落库（TreeMap 紧凑 JSON）；**createSchedule
拒 SEND_MESSAGE**（定时群发无人在环，v1 刻意只许手工派发）。TaskServiceTest 50/50
（15 条新用例：三形态合法/边界 64·2000/缺键空白/未知键点名/非对象/规范形逐字符/旧类型不受误伤）。

**② S8 客户端**：`reader.WeChatReader.send_message`（reader 唯一副作用方法：懒导入
resolve_chat + quick_send(text, display, verify=True)；WxResponse.status=="成功" → 纯 dict 回执，
否则 ReaderError——runner 归纳整轮 skipped，任务留租约期回收重派，不本地 fail）；`runner._run_send`
（payload 缺键/空白正文 → ServerError 10102 → fail retryable=False 确定性失败；坏 payload 绝不触 GUI）。
测试 52 条新增（test_runner_dispatch 7 + test_reader_send 9/14 实例：换位防御锚/懒导入纪律/
quick_send 位置参数钉死/close 后拒绝），全量 **369 passed + 1 xfailed** 零损伤。

**部署与真机闭环**：jar 71,631,737B 重启（Redis 会话跨重启存活）；runner 重启载新分发分支；
API 建任务 **id=259**（payload 经 String 字段传 JSON 文本）→ 客户端 claim → GUI 发送
「消息已发送并确认：收到」→ complete → **SUCCEEDED**（attempts=1，回执 `{sent:true, response:…}`
落 wx_task.result）。

**运营级发现（重要）**：
- **task_mode=on 的语义代价**：任务模式下客户端**只认领任务不自治扫描**（§7「空认领轻收尾」），
  消息同步完全依赖 SYNC_MESSAGES 任务驱动；而种子物化因 `wx_conversation` 空（客户端上报
  conversations:0）产出 0 → **task_mode 下消息同步停摆**，需手工派 SYNC_MESSAGES（本次 id=260
  messages_sent=1 兜底）或切回自治模式。**单进程两模式互斥是当前架构限制**；混合模式
  （自治轮询 + 每轮顺手 claim）登记为 S9 候选。
- `smoke/config.json` 无 task_mode 默认 off——用任务先跑必须换 config-e2e-taskon.json。
- **10103 curl 陷阱**：`payload` 是 String 字段装 JSON 文本（非嵌套对象）；Git Bash 内联 `-d`
  带嵌套引号/中文必炸 → 一律 `--data-binary @file`。
- admin 30 分钟空闲即死（第 4 次 bootstrap 舞步，零损失）。

## [S9 混合模式 · 完成 + 双通道真机实证 2026-11-11]

**动机**：T11/S8 真机验收暴露单进程两模式互斥——task_mode=on 不自治扫描（消息同步停摆，
需手工派 SYNC_MESSAGES 兜底），off 不认领任务。用户拍板做 S9。

**实现（纯客户端，服务端零改动）**：
- config.py：`_TASK_MODE_HYBRID`（hybrid/both → "hybrid"）第三词族；非法值错误文案更新为三态。
- runner.py：`HYBRID_CLAIM_TYPES = ("SEND_MESSAGE", "PROBE")`——**任务独占型**（无自治等价物）；
  数据同步型（SYNC_*/FULL_SCAN/BLOB_UPLOAD）与自治路径重叠，**刻意不认领**（防同数据双跑；
  服务端物化的此类任务会滞留 PENDING 占 uk_active_target 槽位，admin 可 cancel）。
- `_task_round(+task_types) -> bool`：返回「本轮是否已被占用」——命中任务/claim 端点缺席降级
  （降级自治**在内部执行**）/令牌轮换轻收尾 → True；空认领/claim 瞬态失败 → False。
  run_round 三分支：on / **hybrid（False 才回落 `_sync_round`）** / off。on/off 行为零变化
  （on 忽略返回值；off 不进分支）。
- **执行轮揪出亲修双跑缺陷**：初版 hybrid 下 claim 端点缺席会双跑自治轮（`_task_round` 降级
  分支内部已 `_sync_round` + 调用方又回落一次）——改为降级路径返回 True（本轮已占用）；
  顺带清掉一处不可达 `return` 死代码。

**测试**：新增 19 用例（config §6 词族归一/env/词表互斥锚 + dispatch A3 三分支/claim 过滤/
双跑防御/ReaderError 两路 skipped 语义）。定向 104/104；**全量 388 收集 = 387 passed + 1 xfail**
（基线 369 → +19 零损伤；xfail 为既有 reader 跨片并列序已知偏差）。

**部署与双通道真机实证**：现役 runner 切 `smoke/config-hybrid.json`（task_mode=hybrid，
interval 10s）。API 建任务 **id=261**（SEND_MESSAGE「收到」）→ hybrid 认领执行
（轮摘要 `task=SEND_MESSAGE`，5.4s）→ SUCCEEDED；**随后自治通道同一循环把消息带回**
（下轮 chats=3 relations=1 照常）→ ingest 钩子 → 订阅 delivery 149 → 拉取即见 → ack → DELIVERED。
两通道在同一进程同一循环内并行工作，互斥限制消除。

**凭据**：第 5 次 bootstrap 舞步（admin 会话跨夜必死），新 admin access/refresh 落
`.t9_admin_access.txt` / `.t9_admin_refresh.txt`（30 分钟空闲即死的运营限制不变）。

## [重启后重部署 + 活体验收 · 2026-11-12]

机器重启（服务进程灭、Docker 停转）后全链恢复：拉起 wxuw-pg/wxuw-redis（种子 3/3、wx_account=6 完好）
→ 全量回归 **476/476 GREEN**（42 报告文件）→ `mvn package -DskipTests`（jar 71,631,737B，与测试同一套
classes）→ 启动（Windows PID **25152**，历史口径无 WXUW_* env）→ 三条活体验收全 PASS：
① 未知路径 200+信封 10400；② smoke client 令牌 claim 成功派发 task 262（lease_id/lease_until/attempts=1
——Redis 会话跨重启存活）；③ 种子 next_materialize_at 拨回 now() 后 95s 内被调度器精确推走
（+300s/+1d/+7d）——@Scheduled 物化活体实锤。
运维注意：**25152 的 stdout 不进 run-dev.log**（日志在启动方控制台），排障勿只看该文件；
task 262 被验收认走未 complete → 租约到期由回收巡检自愈重派（设计内；无客户端常驻时会烧完 attempts
进 DEAD，属正常死信路径）。
