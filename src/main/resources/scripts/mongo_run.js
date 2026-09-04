/*
 * db-access-mcp 的 MongoDB 执行脚本模板。
 *
 * 这个文件是 jar 内的静态资源。每次查询时把 __DBMCP_STATEMENT__ 替换成待执行语句，
 * 写成临时脚本，再用 `mongosh --nodb --quiet --norc --file <临时脚本>` 执行，用完即删。
 *
 * 为什么要把语句内联进源码而不是走运行时 eval：
 * mongosh 会对脚本源码做 async-rewriter 变换，把游标方法调用改写到位，
 * 于是 `db.c.find({}).limit(1)` 这种链式写法才可用；
 * 而运行时 eval 的字符串不参与这个变换，同一个表达式会报
 * `TypeError: ...find(...).limit is not a function`（实测 mongosh 2.6.0）。
 *
 * 凭据与语句的分工：连接串走环境变量，不进 argv 也不落盘；
 * 语句由只读闸门校验后内联进临时脚本（临时脚本创建即仅属主可读，执行完立刻删除）。
 *
 * 输出约定：游标结果每行一个 EJSON 文档；非游标结果打印为一行；
 * `#` 开头的行是本服务器补的注释行。
 *
 * 行数与字节上限只在 Java 侧（CliRunner）一处判定，本脚本不得自行截断：
 * 脚本自己截断的话，CliRunner 看不到超限行，`#META.truncated` 会与实际输出不一致。
 */
var db = connect(process.env.DBMCP_MONGO_URI);
var __dbmcpResult = __DBMCP_STATEMENT__;

function __dbmcpPrint(value) {
    try {
        print(EJSON.stringify(value));
    } catch (e) {
        print('# 返回值无法序列化为 EJSON：' + e.message);
    }
}

if (__dbmcpResult && typeof __dbmcpResult.hasNext === 'function' && typeof __dbmcpResult.next === 'function') {
    while (__dbmcpResult.hasNext()) {
        __dbmcpPrint(__dbmcpResult.next());
    }
} else {
    __dbmcpPrint(__dbmcpResult === undefined ? null : __dbmcpResult);
}
