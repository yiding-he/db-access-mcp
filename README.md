# db-access-mcp

给 LLM Agent 使用的**只读**数据库访问 MCP 服务器。技术栈：Gradle + Kotlin + Spring Boot + Thymeleaf。
不引入任何 JDBC/Driver：查询通过本机的 `mysql` 与 `mongosh` 命令行工具执行。

> 本文件当前同时充当**设计定稿记录**。若任务中断，新会话按本文件即可继续，无需重走设计讨论。
> 「实现进度」章节随开发更新。

---

## 1. 项目定位与硬约束

| 约束 | 内容 |
|---|---|
| 目的 | 让 LLM Agent 安全地访问数据库（安全 = 只读闸门 + 连接凭据加密 + 凭据不外泄） |
| 数据库支持 | MySQL、MongoDB，仅通过 `mysql` / `mongosh` 命令执行，不用 Java 驱动库 |
| 配置文件 | `~/.config/db-access-mcp/config.data`，内容是**加密的 JSON** |
| 两个 HTTP 入口 | `/mcp`（MCP 服务）、`/admin/**`（管理界面） |
| 启动即锁定 | 管理员必须先进管理页输入配置密码、成功解密配置文件，MCP 才可用 |
| 首次启动 | 配置文件不存在 → 管理员在管理页设置新的配置密码 |
| 管理界面 | 数据库连接配置的增删改查，每个连接配置有唯一名字 |
| MCP 能力 | 一个查询 tool，两个参数：访问哪个连接配置、执行什么语句；语句放在 POST body 中 |

## 2. 技术栈与坐标（定稿）

- 单模块 Gradle 项目，不拆多模块。
- 坐标：`group=com.hyd`，`artifactId=dbmcp`，`version=0.0.1-SNAPSHOT`，根包 `com.hyd.dbmcp`。
- 包划分：`com.hyd.dbmcp.{crypto,config,exec,mcp,web}`。
- 版本：Kotlin 2.4.10、Spring Boot 4.1.1、Gradle 9.5.1、**JDK 21**。
- JDK 用 `java { toolchain { languageVersion = 21 } }` 声明，不写 `org.gradle.java.home`（那是机器路径，不进仓库）。toolchain 同时决定编译产物版本和 `bootRun`/`test` fork 出的子进程用哪个 JVM，因此跟调用者的 `JAVA_HOME` 指向无关。
- 依赖面（**除 Boot 的 web + thymeleaf 外零新增依赖**）：
  - `spring-boot-starter-web`（实测 Boot 4.1.1 已传递 `spring-boot-starter-jackson`，即 Jackson 3 `tools.jackson`，JSON 处理无需额外依赖）
  - `spring-boot-starter-thymeleaf`
  - `testImplementation`: `spring-boot-starter-test` + `kotlin("test")`（仅测试作用域）
  - 加密用 JDK 自带 `PBKDF2WithHmacSHA256` + `AES/GCM/NoPadding`
  - 前端不引任何 CSS/JS 框架，只用一个内联样式的 Thymeleaf 片段
  - 不引 actuator，不引 `jackson-module-kotlin`（JSON 一律用 Jackson 树 API 手工构造/解析，见 §5）
- `application.yml`：`server.port=17788`、`server.address=127.0.0.1`，参数前缀 `dbmcp.*`。
- **明确不做**：普通 HTTP 查询接口（只走 MCP）、闲置自动上锁、忘记密码恢复、健康检查端点。

## 3. 环境事实（本机与内网实测，非推断）

### 构建环境
- PATH 上 `java` = OpenJDK 25.0.1；本机 IDEA/终端默认 `JAVA_HOME` 指向 JDK 17（`C:\Users\qufei\.jdks\ms-17.0.18`）。本项目要求 JDK 21，本机路径 `C:\Users\qufei\.jdks\ms-21.0.12.1`。
- 实测坑：只写 `sourceCompatibility`/`targetCompatibility` + Kotlin `jvmTarget` 不够——那两只管字节码版本，`bootRun` fork 的 JVM 仍跟着跑 Gradle 守护进程的那个 JDK 走。`JAVA_HOME`=17 时表现为编译全部成功、`bootRun` 报 `UnsupportedClassVersionError: class file version 65.0 ... only recognizes ... up to 61.0`。项目里没有 `.java` 源文件，javac 任务空转，所以本该拦住这个错的那道关没上。改成 toolchain 后 Gradle 自动从 `~/.jdks` 探测到 21 并使用（实测 17 环境下 `./gradlew bootRun` 正常起服务）。
- PATH 上无 gradle。本机已缓存 Gradle 9.5.1：`~/.gradle/wrapper/dists/gradle-9.5.1-all/<hash>/gradle-9.5.1/bin/gradle`（实测 JDK 21 可跑）。
- Gradle 分发镜像（平铺布局，已实测可下）：`https://mirror.nju.edu.cn/gradle/gradle-9.5.1-bin.zip`
- Maven 镜像：`https://maven.aliyun.com/repository/public/`（直连 200）。**`repo1.maven.org` 对本机 IP 返回 403（Sonatype 限流封禁），不可用**。
- 可用版本实测：Spring Boot 最新 GA 4.1.1（4.2.0 仅 M1）；Kotlin plugin 最新正式版 2.4.10。

### mysql 客户端 8.4.8（`/c/Dev/mysql-8.4.8-winx64/bin`，在 PATH）
- **无 `--json` 输出选项**（`--verbose --help` 实测无该项）。
- `-B -t`（batch + table）得到干净 ASCII 表格；`-t` 会把值内的换行/制表转义，**每行数据严格一行**，因此按行计数可靠。
- **0 行结果时 stdout 完全为空（连表头都没有）** → 空结果判定 = 退出码 0 且 stdout 空。
- 语句可通过 **stdin** 传入（`echo "SELECT ..." | mysql -B -t`），不进 argv。
- 密码可通过环境变量 `MYSQL_PWD` 提供，不进 argv。
- 只读兜底实测有效：`--init-command="SET SESSION TRANSACTION READ ONLY"` 下 `CREATE TABLE`、`DROP TABLE 不存在的表`、`DELETE` 全部返回 `ERROR 1792 (25006): Cannot execute statement in a READ ONLY transaction.`，`SELECT` 正常。闸门在**服务端**生效。
- 其它可用项：`--connect-timeout=N`、`--local-infile=0`、`--defaults-extra-file=#`。
- **字符集必须显式指定**：中文 Windows 上客户端默认字符集跟随系统代码页，实测不指定时 `@@character_set_client = @@character_set_results = gbk`（而 `@@character_set_server = utf8mb4`），结果集以 GBK 字节回传；`CliRunner` 统一按 UTF-8 解码子进程输出，中文就变成一串 U+FFFD。加 `--default-character-set=utf8mb4` 后实测同一查询回传 UTF-8 字节（`e4 b8 ad e6 96 87 …`）。
- mongosh 侧无此问题（实测脚本文件按 UTF-8 读、stdout 出 UTF-8），Node 进程不受系统代码页影响。

### mongosh 2.6.0（`/c/Dev/mongodb-.../mongosh-2.6.0-win32-x64/bin`，在 PATH）
- `--nodb` 下 **`connect(uri)` 返回 Database 对象**（不是 client！），`new Mongo()` 也可用；`MongoClient` 未定义，`require('mongodb')` 不可用。
- `process.env.XXX` 在 mongosh 内可读（实测）。
- **stdin 方式不可用**：stdin 走 REPL，会回显每行表达式的值（`> test`、`> ...`），污染输出。
- **`--file <脚本>` 方式输出干净**：只有 `print()` 的内容。
- `--json[=relaxed|canonical]` 只能配 `--eval`，与 `--file`/stdin 互斥（实测报错）→ 我们自己用 `EJSON.stringify(doc)` 每行打印一个文档。
- **语句必须内联进脚本源码，不能走运行时 `eval()`**：mongosh 靠 async-rewriter 变换脚本来改写游标调用，
  `find()` 返回的对象是「内部初始化完成后才换上 Cursor 原型」的；同一个表达式里链式写法
  `db.c.find({}).limit(1)` 在源码中正常，放进 `eval()` 就报
  `TypeError: db.system.version.find(...).limit is not a function`（实测；`--file` 源码、`new Function` 源码里取 `.limit`
  均为 function，只有 `eval()` 路径拿不到）。因此 `MongoShellClient` 每次查询生成一个临时脚本：
  jar 内模板 + 把 `__DBMCP_STATEMENT__` 替换成 `(语句)`，执行完立即删除。
- `--file` 脚本不支持顶层 `await` / `for await`（SyntaxError：只允许在 async 函数与模块顶层），
  但游标的 `hasNext()` / `next()` / `toArray()` / `forEach()` 在语句之间是同步可用的（实测）。
- 临时脚本用 `Files.createTempFile` 创建（POSIX 下权限 600），连接串仍走环境变量，因此口令不会落到临时文件里。
- `system.version` 只在 `admin`/`config` 库里；连到 `test` 库查 `db.system.version.find({})` 是 0 行且无输出（正常行为，不是故障）。
- 本机有 `mongod.exe`（备用），3306 本机无监听。

### 内网测试库（用户提供，凭据不写入仓库文件）
- MongoDB：`192.168.1.99:27017`，无认证。实测只有 `admin/config/local` 三个库，无业务数据；真连验证用 `admin.system.version.find({})`（返回 `{"_id":"featureCompatibilityVersion","version":"7.0"}`）。
- MySQL：`192.168.1.99:3306`，`root` / 口令见用户（不入库）。实测可连，含 15 个库（有 `test` 库可用）。

## 4. 配置加密与文件（定稿）

### `config.data` 信封（外层是**明文 JSON**，仅 `ct` 是密文）
```json
{"v":1,"kdf":"PBKDF2-HMAC-SHA256","iter":600000,"salt":"<base64>","iv":"<base64>","ct":"<base64>"}
```
- KDF：JDK 内置 `PBKDF2WithHmacSHA256`，600,000 轮，派生 256-bit AES key（**不用 Argon2，避免引 BouncyCastle ≈6MB**）。
- 加密：`AES/GCM/NoPadding`，IV 12 字节随机，tag 128 bit。
- 明文 = 下面的配置 JSON（UTF-8）。
- **密码正确性由 GCM tag 校验天然判定**（`AEADBadTagException` = 密码错或文件损坏），不额外存验证器/哈希。
- 不做 Argon2、不做密钥派生缓存、不做「忘记密码恢复」：忘记密码 = 该文件废弃，只能手工删除后重设。

### 解密后的明文配置结构
```json
{
  "version": 1,
  "connections": [
    {
      "name": "order_ro",
      "kind": "mysql",
      "description": "订单库只读账号，order-intl 项目用",
      "host": "192.168.1.99",
      "port": 3306,
      "username": "root",
      "password": "……",
      "database": "order_intl",
      "authSource": ""
    },
    {
      "name": "mongo_test",
      "kind": "mongodb",
      "description": "测试环境 Mongo",
      "host": "192.168.1.99",
      "port": 27017,
      "username": "",
      "password": "",
      "database": "admin",
      "authSource": ""
    }
  ]
}
```
- 单一扁平模型 `DbConnection`（不用 sealed 层次），`kind` ∈ `mysql|mongodb`，不适用的字段留空串。
- MySQL 的 `database` 可选（空则不传 `-D`，Agent 只能写全限定表名）；MongoDB 的 `database` 必填；MongoDB 的 `username/password/authSource` 全部可选（空则连接串不带认证）。
- 字段校验：`name` 匹配 `[A-Za-z0-9_-]{1,32}`、全局唯一、**创建后不可改名**（改名会破坏 Agent 提示词里的引用，要改就删了重建）；`description` **必填、不限长度**；`host` 非空；`port` ∈ 1..65535。
- 写回策略：每次 CRUD / 改密 → 全量重加密 → 写临时文件 + rename 原子替换；写失败则整个操作报错、内存态不变。
- 内存中的解密明文只驻留内存，不落盘、不进日志、不进浏览器（见 §8）。

## 5. JSON 处理约定

不引入 `jackson-module-kotlin`（否则是新增依赖）。所有 JSON 用 Jackson 3 **树 API** 手工构造/读取：
`tools.jackson.databind.json.JsonMapper`、`tools.jackson.databind.node.JsonNodeFactory`。
领域模型与 JSON 之间的映射写在各自的 `toJson()/fromJson(node)` 里，一处一份，不反射。

## 6. 启动状态机与解锁（定稿）

```
config.data 不存在          → SETUP     管理页显示「设置新的配置密码」（两次输入），成功后写出空配置 → UNLOCKED
config.data 存在且未解密     → LOCKED    管理页只显示密码框；MCP 一律拒绝
解密成功                    → UNLOCKED  管理页可用；MCP 可用
解密失败（密码错/文件损坏）   → 仍 LOCKED  解锁页显示原因
```
- 解锁凭据（派生出的密钥 + 解密后的配置）只存内存；**进程重启必须重新输入**。
- 不自动上锁、无闲置超时、配置密码无生命周期。
- 改密时解锁态保持（会话不失效）。
- 会话：解锁成功在 `HttpSession` 记一个 `unlockGeneration`（全局计数器），后续请求比对；全局 generation 变化（重新解锁/改密重写）即让旧会话失效。
- MCP 在非 UNLOCKED 时：HTTP **423** + JSON-RPC error `{"code":-32002,"message":"mcp server not ready"}`（含 `initialize` 在内所有方法，措辞按用户要求，便于 Agent 明确处境）。

## 7. 管理界面（Thymeleaf，定稿）

| 路由 | 方法 | 说明 |
|---|---|---|
| `/` | GET | 重定向到 `/admin/connections` |
| `/admin/unlock` | GET/POST | 解锁页（LOCKED 时唯一可访问页），显示当前状态与错误原因 |
| `/admin/setup` | GET/POST | 首次设置配置密码 |
| `/admin/connections` | GET | 列表：名字、类型、描述、host:port、database，行内操作（编辑/删除/测试） |
| `/admin/connections/new` | GET/POST | 新增连接 |
| `/admin/connections/{name}/edit` | GET/POST | 编辑连接（**名字只读**、密码**永不回显**、留空＝不修改） |
| `/admin/connections/{name}/delete` | POST | 删除连接 |
| `/admin/connections/{name}/test` | POST | 列表页测一个**已保存**的连接（重定向回列表 + flash 消息） |
| `/admin/connections/test` | POST | 表单页测**当前表单值**（新增/编辑未落库的草稿）：只回 JSON `{ok, message}`，**绝不重渲染表单**；先跑一遍与保存同源的校验，校验不过就把错误文案回给页面，不落库 |
| `/admin/password` | GET/POST | 修改配置密码（旧密码 + 新密码两次）；校验旧密码＝用旧密码再解密一次磁盘信封；成功后用新密码整体重加密并重写文件 |

- 页面用 flash 属性回传成功/错误消息；所有写操作用 POST。
- **硬原则：点「测试连接」不得改动页面上表单的任何内容**（不管成败）——测试必须用表单当前值，测完用户接着点保存时提交的还是那些值。因此表单页的测试是 AJAX（`fetch` + urlencoded，`Content-Type` 显式带 `charset=UTF-8`），结果只填进输入框下方的结果区域；失败时也不重渲染（否则口令会被抹空，见 §14）。
- 「测试连接」实际执行：MySQL `SELECT 1`，MongoDB `db.runCommand({ping:1})`，走与 MCP 相同的执行通道（但不经过只读白名单，因为是内部固定语句）。
- 结果页展示：耗时、错误信息原文（CLI stderr 摘要）。

## 8. 凭据不外泄的传参方式（定稿，实测通过）

**原则：密钥只出现在子进程的私有环境变量里，不出现在 argv，不出现在临时文件，不出现在日志/浏览器。**

- 每次执行用 `ProcessBuilder` 的**子进程私有 env 副本**注入（天然不存在不同连接互相覆盖；不修改父进程环境）。
- MySQL：env `MYSQL_PWD=<密码>`；argv 只有 `-h/-P/-u/-D`；语句走 stdin。
- MongoDB：argv 只有 `--nodb --quiet --norc --file <每次查询生成的临时脚本>`；env 只有 `DBMCP_MONGO_URI=<mongodb://user:pass@host:port/db?authSource=..>`；
  临时脚本 = jar 内静态模板 + 内联已过闸门的语句，**模板不含凭据，脚本里也不写入连接串**（`Files.createTempFile` 创建，仅属主可读，执行完立即删除）。
  语句进临时脚本源码而不进 argv，也不走 `eval()`（原因见第 3 章 mongosh 实测）。
- 残余风险（已告知用户并接受）：同机同用户进程可读子进程环境变量。
- HTTP 响应/日志中，密码字段一律不出现；编辑页不回显密码。

### 命令模板（定稿）
```
mysql -h <host> -P <port> -u <user> [-D <db>] \
      --connect-timeout=10 --default-character-set=utf8mb4 --local-infile=0 \
      --init-command="SET SESSION TRANSACTION READ ONLY" \
      -B -t
  env:  MYSQL_PWD=<password>
  stdin: <statement>\n

mongosh --nodb --quiet --norc --file <tmp>/db-access-mcp-mongo-<random>.js
  env:  DBMCP_MONGO_URI=<connection string>
  脚本: /scripts/mongo_run.js 模板把 __DBMCP_STATEMENT__ 替换成 (statement)，用完删除
```

## 9. 只读闸门（定稿）

**方式选 (a)：关键字白名单 + MySQL 服务端 READ ONLY 兜底。**（不做黑名单方案、不做「只靠 DB 账号权限」）

### 通用
- 去掉首尾空白与**末尾单个分号**；**不按 `;` 切分语句、也不因含 `;` 而拒绝**（原定的「多语句一律拒绝」已撤销）。
  理由：`;` 可以合法出现在字符串常量里（`SELECT ';'`、`db.c.find({code:'A;B'})`），纯字符扫描无法区分。
  撤销后的兜底：
  - MySQL —— `--init-command="SET SESSION TRANSACTION READ ONLY"` 在服务端拦住 `;` 之后的写入，实测回
    `ERROR 1792 (25006) at line 1: Cannot execute statement in a READ ONLY transaction.`；
    禁用词扫描是对**整串**做的，所以 `SELECT 1; SELECT ... INTO OUTFILE 'x'` 这类仍会被闸门先拒掉。
  - **已接受的残余风险（用户判定：本 MCP 不需要防这一手）**：这条兜底不是不可逆开关，同批次里再来一条
    `SET`/`START TRANSACTION` 就能把会话只读撤掉。实测 4 条绕法全部生效（`-D mysql` + 不存在的表，1146 即已过只读关）：
    `; SET SESSION TRANSACTION READ WRITE` / `; SET TRANSACTION READ WRITE` / `; START TRANSACTION READ WRITE`
    / `; SET autocommit=0; START TRANSACTION READ WRITE`，基线单独 `DELETE` 则是 `ERROR 1792`。
    `mysql` 命令行层拿不到不可撤销的只读参数（8.4.8 实测：`--read-only`、`--skip-multi-statements`、
    `--transaction-access-mode` 全是 unknown option/variable；`--safe-updates` 只管 `--select_limit`/`--max_join_size`
    与不带 WHERE 的 UPDATE/DELETE，加不加它上面的绕法结果一致）。真要撤不掉的防线，只有给只读账号（DB 侧授权）。
  - MongoDB —— 结构解析要求整条语句是**单个**从 `db` 开始的只读表达式，`;` 后再接表达式天然解析失败被拒；
    字符串常量内的 `;` 由扫描器按字面量跳过，不受影响。
- 拒绝空语句。

### MySQL
1. 首关键字 ∈ `SELECT`、`WITH`、`SHOW`、`DESC`、`DESCRIBE`、`EXPLAIN`（大小写不敏感）。
   - 允许首关键字前置任意空白与左括号，以支持 `(SELECT …) UNION (SELECT …)`、`((SELECT …) UNION (SELECT …)) ORDER BY …` 这类合法只读写法；
     括号不能用来绕过白名单 —— `(DELETE FROM t)`、`((DROP TABLE t))` 仍取到括号后的首关键字并拒绝。
2. 全文（正则、忽略大小写与多余空白）禁止：`into\s+outfile`、`into\s+dumpfile`、`load_file\s*\(`、`load\s+data`、`for\s+update`、`lock\s+in\s+share\s+mode`。
   - 说明：首关键字白名单已挡住写入语句本体，这里只需堵文件读写与加锁这几个真实口子，避免 `WHERE name='set'` 这类误杀。
3. `EXPLAIN/DESCRIBE` 后接任意语句允许（不真执行），且有服务端只读兜底。
4. 拒绝 `USE/SET/LOCK/CALL/HANDLER/GET DIAGNOSTICS/KILL/DO/PREPARE` 等（由首关键字白名单直接挡掉）。
5. 显式 `--local-infile=0`。
6. 显式 `--default-character-set=utf8mb4`：否则中文结果按系统代码页（gbk）回传，我们按 UTF-8 解码就得到 U+FFFD。

### MongoDB（白名单是唯一闸门：该实例无认证，没有服务端只读兜底 → README 要求管理员配只读账号）
- 允许 `db.<coll>.<method>(...)`，method ∈ `find`、`findOne`、`countDocuments`、`estimatedDocumentCount`、`distinct`、`aggregate`、`stats`。
- 允许 `db.<method>(...)`，method ∈ `getCollectionNames`、`listCollections`、`getCollectionInfos`。
- 其它形式（赋值、`const/let/var`、多表达式、`db.<coll>.insertXxx`、`runCommand`、`getCollection(...).drop()`、任何包含 `;` 的）一律拒绝。
- `aggregate` 的 pipeline 中出现 `$out`、`$merge`、`$accumulator` → 拒绝。

### 拒绝时
返回 `#META` 行 `ok:false` + `error` 说明是哪个规则挡的（不返回堆栈），`isError:true`；不启动子进程。

## 10. 限额与返回格式（定稿）

- 超时 **30 秒**（到点 `destroyForcibly`）；最大 **100 行**；输出上限 **60 KB**（61440 字节）。三个值都在 `application.yml` 可覆盖。
- 不限制并发（实际不会有大的并发），无信号量。
- 截断语义：输出读到第 101 行即停止读取并 kill 子进程，标 `truncated:true`；累计字节超 60KB 同样 kill 并标 `truncated:true`。
- **行数与字节上限只在 `CliRunner` 一处判定**：mongosh 脚本不得自行截断（曾自行截断，导致 `CliRunner` 看不到超限行、`#META.truncated` 恒为 `false`，与实际输出不一致）。
- **返回形态（用户指定）**：多行文本，**第一行是 JSON 的 `#META` 元信息行**，之后是工具原样输出（MySQL = `-t` ASCII 表格文本；Mongo = 每行一个 EJSON 文档）。
```
#META {"ok":true,"connection":"order_ro","db":"mysql","rows":2,"truncated":false,"elapsedMs":41,"outputBytes":96,"statement_digest":"SELECT id, ..."}
+----+----------+
| id | name     |
+----+----------+
|  1 | order-01 |
+----+----------+
```
- 失败时只有 `#META` 行：`{"ok":false,"connection":"...","db":"mysql","error":"ERROR 1049 (42000) at line 1: Unknown database 'nope'","elapsedMs":12,"statement_digest":"..."}`。
- `statement_digest`：语句前 200 字符（Agent 并行发多条请求时可对号）。
- 行数计算：MySQL = 以 `|` 开头的行数 − 1（表头），空输出 = 0；Mongo = 非空行数（脚本每文档一行）。

## 11. MCP 端点契约（手写最小实现，定稿）

**选型：不引入官方 Java MCP SDK（`io.modelcontextprotocol.sdk:mcp:2.0.1` 会传递 mcp-core、mcp-json-jackson3、reactor-core、jackson-annotations、networknt/json-schema-validator，约 6~10 个 jar）。手写约 200 行，零新增依赖。** 协议一致性用真实 MCP 客户端做链路验证来保证。

- 端点 `POST /mcp`（Streamable HTTP，单条 JSON-RPC 请求/响应，`application/json`）；`GET /mcp` → 405（不提供服务端推送通道）；`DELETE /mcp` → 200 并作废 session。
- 协议版本：声明 `2025-06-18`；若客户端请求更低版本则回落 `2025-03-26`；请求头 `MCP-Protocol-Version` 缺失时按 `2025-06-18` 处理。
- `initialize` 响应头下发 `Mcp-Session-Id`（随机串，内存表记录 + 对应 unlockGeneration），后续 POST 校验该头；服务端不保存业务状态。
- 实现方法：`initialize`、`notifications/initialized`（→ HTTP 202 空体）、`ping`、`tools/list`、`tools/call`、`resources/list`、`resources/read`。批量（数组）请求 → 拒绝。
- 未知方法 `-32601`；参数错 `-32602`；解析错 `-32700`；未解锁 `-32002 "mcp server not ready"`。
- **tools/list**：只有一个 tool —— `db_query(connection, statement)`
  - `connection`：`type:string`，`enum` = 当前所有连接名（动态生成）
  - `statement`：`type:string`
  - `required: [connection, statement]`
  - description 写清：只读查询；MySQL 传只读 SQL（可含 `UNION`/子查询），MongoDB 传 `db.xxx.yyy(...)` 单个表达式；返回首行是 `#META` JSON。
- **resources**（用户要求：Agent 索要资源列表时给出连接信息）
  - `resources/list` 返回一条 `dbmcp://connections`
  - `resources/read` 返回 JSON 数组，每项**只含 `name`、`description`、`kind`（数据库类型）**——描述用于让 Agent 判断这个连接属于哪个项目。**不含 host/port/账号/密码。**
- `tools/call` 结果：`content:[{"type":"text","text":"#META ...\n<正文>"}]`，失败时 `isError:true`。

## 12. 日志（定稿）

- 只输出到控制台（Boot 默认 logback），**不写日志文件**，要文件由启动方自行重定向。
- 每次 tool 调用记录：连接名、`statement_digest`（语句前 500 字符）、耗时、行数、成功/失败与错误摘要。
- 绝不记录：密码、解密后的完整配置明文。

## 13. 交付物与验证计划

- 交付：可运行 jar（`java -jar`）、`README.md`、`.gitignore`、Gradle wrapper（`distributionUrl` 指向 NJU 镜像）。
- 单元测试（只测纯逻辑，不依赖网络/DB）：
  1. `ConfigCipherTest`：加解密往返、错密码失败、信封字段完整。
  2. `StatementGuardTest`：MySQL/Mongo 允许与拒绝矩阵、字符串常量里的 `;`、括号包裹的 `UNION`、`$out/$merge/$accumulator`、`INTO OUTFILE`。
  3. `ResultLimiterTest`：100 行截断、60KB 截断、MySQL 表头行不计入行数、空结果。
  4. `MongoUriTest`：带/不带认证、authSource 拼接（并确保错误信息里不带密码）。
  5. `McpHandlerTest`：initialize/tools/list/resources/read/tools/call 参数校验（用假 state，不起容器）。
- 真连端到端验证（192.168.1.99）：已完成，58 项全绿，清单与结果见第 14 章「端到端实测记录」；可重跑脚本 = 项目根 `e2e-check.py`。
- 验证用的 `config.data` 写在系统临时目录（起 jar 时用 `--dbmcp.config-path` 指定），不碰用户真实配置路径，也不入库。

## 14. 实现进度

主代码、单测、真连端到端验证均已完成，`gradle build` 绿。

- [x] 设计定稿（三轮问答 + 环境实测）
- [x] Gradle 骨架：`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、`application.yml`、主类
- [x] 工具链验证：Kotlin 2.4.10 + Boot 4.1.1 + Gradle 9.5.1 + JDK 21，依赖从 aliyun 解析成功
- [x] `crypto`：ConfigCipher（PBKDF2-HMAC-SHA256 600k 轮 + AES-256-GCM 信封）
- [x] `config`：DbKind / DbConnection / AppConfig / ConfigStore / AppState（SETUP→LOCKED→UNLOCKED 状态机 + 解锁代次）
- [x] `exec`：StatementGuard、CliRunner（子进程私有 env + 超时/行数/字节三重上限）、MySqlClient、MongoShellClient、QueryService
- [x] `mcp`：McpHandler、McpController（session、协议版本协商、423+-32002、-32600/-32601/-32602）
- [x] `web`：AdminController、WebConfig（UnlockInterceptor + 代次校验）、ConnectionForm、5 张 Thymeleaf 页
- [x] `resources/scripts/mongo_run.js`（静态模板 + 语句内联）
- [x] 单测全绿（ConfigCipher / StatementGuard / CliRunner / MongoUri / McpHandler / MongoScriptTemplate 等）
- [x] 真连端到端验证 58 项全绿（脚本：项目根 `e2e-check.py`）
- [ ] Gradle wrapper 文件与 `git init`（见下）

### 端到端实测记录（192.168.1.99，直接跑 bootJar）

**产品缺陷（已修）：中文结果被转成 U+FFFD。** 现象是 MCP 输出里出现 `CA-APP����` 这类内容。
根本原因不在本项目代码：中文 Windows 上 `mysql` 客户端的默认字符集跟随系统代码页，实测 `@@character_set_results = gbk`，服务端于是把结果集转成 GBK 字节回传，而 `CliRunner` 按 UTF-8 解码。修法是给 mysql 加 `--default-character-set=utf8mb4`（A/B 实测：不加时「中文测试」的字节为 `d6 d0 ce c4 b2 e2 ca d4`，加上后为 `e4 b8 ad e6 96 87 e6 b5 8b e8 af 95`）。
已补端到端断言：`SELECT '中文测试' AS cn` 走 MCP 必须原样返回中文且不含 U+FFFD。mongosh 通道实测无此问题。

验证脚本用 Python 标准库写，无第三方依赖；跑法是先用 `--dbmcp.config-path` 指向临时目录起 jar，再执行
`python e2e-check.py <config.data 路径>`。覆盖：首次设密 → 建两个连接（含中文描述，验证 UTF-8 全链路）→
表单内与列表页测试连接（AJAX，只断言 JSON）→ MCP 握手与版本协商 → `tools/list`（enum 即连接名）→ `resources/list`/`resources/read`
（不回主机、账号、口令）→ MySQL 真查（`mysql -t` ASCII 表格原文）与 Mongo 真查（EJSON 每行一文档，
含 `db.c.find({}).limit(1)` 链式写法）→ 闸门拒绝写入/DDL/括号里的写入/`INTO OUTFILE`/`SET`/可执行注释/
`$out`/`runCommand`/shell 关键字 → 字符串常量里的 `;` 不误判（MySQL 与 Mongo 各一条）、`(SELECT…) UNION (SELECT…)`
放行、`;` 后的写入由服务端 `READ ONLY` 拦死（实测 `ERROR 1792 (25006) at line 1: Cannot execute statement in a
READ ONLY transaction.`）、Mongo 截断时 `truncated=true` → 100 行截断、60KB 截断、30s 超时杀进程（实测墙钟 30.0s、
`elapsedMs` 30013）→ 改密后旧 MCP 会话 404、浏览器需重新解锁、旧口令不能解锁、重新握手后可继续查 →
磁盘上的 `config.data` 仍是信封密文，不含明文连接名/主机/口令。

**产品缺陷（已修）**：「测试口令静默丢空」

- 现象：MCP 查 MySQL 报 `ERROR 1045 (28000): Access denied for user 'xxx' (using password: NO)`，解密 `config.data` 发现该连接的 `password` 是空串；用户表示新增时确实填了口令且测试通过。
- 根本原因：旧实现里表单页的「测试连接」是个普通 submit 按钮，服务端处理完测试后**重渲染整张表单**；而口令字段按「永不回显」的设计不带 `value`。于是用户看到「测试通过」后在同一页点保存，浏览器提交的就是空口令；`ConnectionForm` 里 `password.ifBlank { passwordIfBlank }` 在新增场景下 `passwordIfBlank` 也是空串，就静默存了空。执行链路（`MYSQL_PWD` 注入子进程私有环境）本身没问题；`using password: NO` 是「压根没传密码」而非「密码错」（后者是 `using password: YES`），这是定性依据。
- 修：表单页测试改为 AJAX（`POST /admin/connections/test` 回 JSON），服务端不再碰表单内容；`saveConnection` 里的 `action=test` 分支删除。补了两条回归断言：新增态先测后存再真查（口令丢了就会回 1045）；编辑态口令留空时沿用已存口令。
- 遗留风险（已知未改）：保存时服务端校验失败仍会重渲染表单，此时口令框也会被抹空（但本次保存并未成功，不会落错数据）；若用户只改其中一个字段的校验错误、其他字段靠浏览器保留，仍可能漏填口令。需要时可把校验也前移到 JS。

**规则变更（用户决定）**：撤销「按 `;` 拒绝多语句」。

- 直接理由：`;` 可以合法出现在字符串常量里（`SELECT ';' AS x`、`db.c.find({code:'A;B'})`），字符扫描无法区分；实测这两条都被误杀。
- 撤销后各侧兜底：MySQL 靠 `--init-command` 的会话只读事务（实测 `;` 后的 `DELETE` 报 `ERROR 1792 (25006)`，且第一条 `SELECT` 的结果正常返回，`rows=1`）+ 整串禁用词扫描；Mongo 靠 `MongoChain` 结构解析（整条必须是单个 `db` 起始表达式，`;` 后接第二条表达式仍被拒，实测有断言）。
- 同时放开括号开头的组合查询：首关键字正则改为 `^\s*[ (]*([A-Za-z]+)`，`(SELECT 1) UNION (SELECT 2)` 放行、`(DELETE FROM t)`/`((DROP TABLE t))` 仍拒。
- 曾按「引号/注释状态机恢复单语句判定」实现过一版，用户判定「本 MCP 不需要那么严格，不考虑自我撤销」，已撤销；
  撤销的实测依据见上面「已接受的残余风险」与第 3 章 mysql 客户端实测。

**产品缺陷（已修）：Mongo 结果被截断时 `#META.truncated` 仍为 `false`。**

- 现象：用户实测 Mongo 大结果集，输出末尾有「结果行数已达上限 100，后续内容被丢弃」，但 `truncated` 是 `false`。
- 根本原因：行数上限被判定两次 —— mongosh 脚本用 `DBMCP_MAX_ROWS` 自己 `break` 并打印提示行，`CliRunner` 又在读满 101 行时置位。脚本先截断，`CliRunner` 永远见不到第 101 行，于是 `truncated` 恒为 `false`（MySQL 通道没有脚本侧截断，所以只有 Mongo 出问题）。
- 修：删除脚本侧的上限判定与 `DBMCP_MAX_ROWS` 环境变量，上限只由 `CliRunner` 一处判定（与 MySQL 同路）；`MongoScriptTemplateTest` 加断言禁止模板再出现 `MAX_ROWS`。实测修复后 `rows=100`、`truncated=true`、末行为截断提示。

验证脚本/测试写法踩过的坑（非产品缺陷）：
- Git-Bash 交给 curl 的中文参数是 GBK 字节，服务端按 UTF-8 解会 400 → 验证脚本改用 Python 显式按 UTF-8 构造请求体。
- Jackson 3 的 `JsonNode` 自带 `map` 成员方法，会盖掉 Kotlin 的 `Iterable.map` → 测试里遍历数组改用下标（`size()`/`get(i)`）。

## 15. 决策记录（为什么这样选）

| 决策 | 选了什么 | 被否掉的方案与原因 |
|---|---|---|
| MCP 协议层 | 手写最小 JSON-RPC | 官方 Java SDK 2.0.1：多 6~10 个 jar，而本项目只有 1 个只读 tool、无流式/resources 也极简 |
| MySQL 调用 | 原生 CLI + 服务端 READ ONLY | JDBC：需求明确禁止 |
| 密码传递 | 子进程私有环境变量 | argv（同机其它用户可读进程命令行）；`--defaults-extra-file` 临时文件（明文落盘） |
| Mongo 输出 | 静态脚本 + `EJSON.stringify` 每行一文档 | `--json` 与 `--file` 互斥（实测）；stdin REPL 会回显噪声（实测） |
| KDF | PBKDF2-HMAC-SHA256 600k | Argon2id：要引 BouncyCastle ≈6MB，收益对「本机文件被拿到」的威胁模型不大 |
| 只读方式 | 白名单 + 单语句 + 服务端兜底 | 黑名单：可绕；纯靠 DB 账号权限：不可控且 Mongo 侧无兜底 |
| 结果格式 | `#META` JSON 行 + 原样文本 | 纯 JSON rows：用户要求保留 CLI 原样文本，省转换代码且不丢格式 |
| 连接发现 | MCP resources（name/description/kind）+ tool 参数 enum | 额外 `connections_list` tool：没必要 |
| 连接改名 | 不允许 | 改名会破坏 Agent 侧提示词引用 |
| 配置密码 | 可手工改密（整体重写文件）；无自动上锁、无遗忘恢复 | 闲置超时上锁：用户明确不要 |
| 并发 | 不限制 | 信号量 + busy 错误：用户判断实际不会有大并发 |
| 数值限额 | 30s / 100 行 / 60KB | — |
| 端口 | 17788，只绑 127.0.0.1 | 7788（用户改定） |
| 日志 | 只控制台 | 同时写文件：避免引入轮转配置 |
