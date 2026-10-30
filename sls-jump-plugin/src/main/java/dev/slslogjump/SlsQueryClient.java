package dev.slslogjump;

import com.aliyun.openservices.log.Client;
import com.aliyun.openservices.log.common.auth.DefaultCredentials;
import com.aliyun.openservices.log.common.auth.StaticCredentialsProvider;
import com.aliyun.openservices.log.common.LogContent;
import com.aliyun.openservices.log.common.LogItem;
import com.aliyun.openservices.log.common.QueriedLog;
import com.aliyun.openservices.log.exception.LogException;
import com.aliyun.openservices.log.request.GetLogsRequest;
import com.aliyun.openservices.log.response.GetLogsResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * SLS 日志查询客户端（基于 aliyun-log 0.6.166，四参 Client 携带 STS token）。
 *
 * <p>每次查询现构 Client、现解析凭证（{@link AliyunCliCredentialResolver}），
 * 不缓存连接与凭证——STS 过期后更新本机阿里云 CLI 配置 即生效。</p>
 */
public final class SlsQueryClient {

    /** 查询类型（类型可选需求）：映射到 SLS 全文索引查询语句。 */
    public enum QueryType {
        ALL("全部", "*"),
        ERROR("错误", "ERROR OR Exception"),
        WARN("警告", "WARN"),
        INFO("信息", "INFO"),
        DEBUG("调试", "DEBUG");

        private final String label;
        private final String query;

        QueryType(String label, String query) {
            this.label = label;
            this.query = query;
        }

        /** 下拉框显示文本。 */
        public String getLabel() {
            return label;
        }

        /** 对应的 SLS 查询语句。 */
        public String getQuery() {
            return query;
        }

        /**
         * 组装最终查询语句：按应用（k8s 容器名）过滤 + 类型过滤。
         *
         * @param app 应用名（容器名），如 sample-app；空/blank 表示不过滤（查全部应用）
         */
        public String buildQuery(String app) {
            if (app == null || app.isBlank()) {
                return query;
            }
            String containerFilter = "_container_name_:" + app.trim();
            if ("*".equals(query)) {
                return containerFilter;
            }
            return containerFilter + " and (" + query + ")";
        }
    }

    /** 一条日志记录。 */
    public static final class LogEntry {
        public final long timeMillis;
        public final String level;
        public final String content;

        public LogEntry(long timeMillis, String level, String content) {
            this.timeMillis = timeMillis;
            this.level = level;
            this.content = content;
        }
    }

    /**
     * 查询指定时间范围的日志，自动翻页拉全（GetLogs 普通模式单次最多 100 条）。
     * 便捷重载：不可取消。
     */
    public static List<LogEntry> query(String endpoint, String project, String logStore,
                                       long fromUnixSeconds, long toUnixSeconds,
                                       String query, int maxLines) throws LogException {
        return query(endpoint, project, logStore, fromUnixSeconds, toUnixSeconds,
                query, maxLines, () -> false);
    }

    /**
     * 查询指定时间范围的日志，自动翻页拉全（GetLogs 普通模式单次最多 100 条）。
     *
     * @param maxLines  拉取总上限（翻页累计），如 10000；翻到没有更多时提前结束
     * @param cancelled 取消检查（由进度条提供）；返回 true 时停止翻页并返回已拉到的部分
     */
    public static List<LogEntry> query(String endpoint, String project, String logStore,
                                       long fromUnixSeconds, long toUnixSeconds,
                                       String query, int maxLines,
                                       java.util.function.BooleanSupplier cancelled) throws LogException {
        AliyunCliCredentialResolver.Credential cred = AliyunCliCredentialResolver.resolve();
        // 注意：不能用四参 Client(endpoint, id, secret, token)——aliyun-log 0.6.166 该构造器
        // 实测不把 SecurityToken 放进签名头，SLS 服务端会报 "AccessKeyId not found"。
        // 必须把 token 放进 Credentials（三参 DefaultCredentials），经 StaticCredentialsProvider 传入。
        StaticCredentialsProvider provider = new StaticCredentialsProvider(
                cred.hasToken()
                        ? new DefaultCredentials(cred.accessKeyId, cred.accessKeySecret, cred.securityToken)
                        : new DefaultCredentials(cred.accessKeyId, cred.accessKeySecret));
        Client client = new Client(endpoint, provider,
                cred.hasToken() ? cred.securityToken : null);

        List<LogEntry> entries = new ArrayList<>();
        final int PAGE = 100;
        int offset = 0;
        // 倒序翻页（最新优先）：GetLogs 正序会拿到窗口里"最旧"的；拉满后整体反转回正序展示。
        while (entries.size() < maxLines && !cancelled.getAsBoolean()) {
            GetLogsRequest request = new GetLogsRequest(
                    project, logStore,
                    (int) fromUnixSeconds, (int) toUnixSeconds,
                    "",                     // topic
                    query == null ? "" : query);
            request.SetLine(PAGE);
            request.SetOffset((long) offset);
            request.SetReverse(true);

            GetLogsResponse response = client.GetLogs(request);
            List<LogEntry> page = parsePage(response);
            entries.addAll(page);
            if (page.size() < PAGE) {
                break; // 没有更多了
            }
            offset += page.size();
        }
        // 拉取时倒序（最新优先），这里反转回时间正序展示
        java.util.Collections.reverse(entries);
        return entries;
    }

    /** 解析一页 GetLogs 结果：k8s 日志只取 content 字段（日志原文）。 */
    private static List<LogEntry> parsePage(GetLogsResponse response) {
        List<LogEntry> entries = new ArrayList<>();
        for (QueriedLog queriedLog : response.getLogs()) {
            LogItem item = queriedLog.GetLogItem();
            long timeMillis = (long) item.GetTime() * 1000L;
            // k8s 日志的字段结构（实测）：content=日志原文，_pod_name_/_image_name_ 等为元数据。
            // 只输出 content（即 catalina.out 风格的原始日志行），无 content 时兜底拼接全部字段值。
            String content = null;
            StringBuilder fallback = new StringBuilder();
            for (LogContent lc : item.GetLogContents()) {
                if ("content".equals(lc.GetKey())) {
                    content = lc.GetValue();
                } else if (fallback.length() > 0) {
                    fallback.append(' ').append(lc.GetValue());
                } else {
                    fallback.append(lc.GetValue());
                }
            }
            if (content == null) {
                content = fallback.toString();
            }
            entries.add(new LogEntry(timeMillis, guessLevel(content), content));
        }
        return entries;
    }

    /**
     * 脱敏：掩盖 SLS 错误回显中的 AccessKeyId / Token 等敏感串，避免泄露到 IDE 日志。
     * 覆盖 {@code STS.*} 临时 akId 与裸 akId 形态。
     */
    public static String maskSecret(String msg) {
        if (msg == null) {
            return null;
        }
        // 1) "AccessKeyId ...: <id>" 形态（先跑，避免吃掉后续规则的结果）
        msg = msg.replaceAll("(?i)(accesskeyid[^:=]*[:=]\\s*)\\S+", "$1***");
        // 2) 裸 AK 形态：LTAI*/LTS*/ACS* 开头的长 token
        msg = msg.replaceAll("\\b(?:LTAI|LTS|ACS)[A-Za-z0-9]{8,}\\b", "***");
        // 3) STS.NXdx5qBoq... 形态兜底
        msg = msg.replaceAll("STS\\.[A-Za-z0-9]+", "STS.***");
        return msg;
    }

    /**
     * 判断是否为 STS 过期类错误（用于给出重跑登录的提示）。
     */
    public static boolean isCredentialExpired(LogException e) {
        String code = e.GetErrorCode();
        return "SignatureNotMatch".equals(code) || "InvalidAccessKeyId".equals(code)
                || "SecurityTokenExpired".equals(code);
    }

    private static String guessLevel(String line) {
        if (line == null) {
            return "INFO";
        }
        // 先剥离 ANSI 颜色转义（\x1b[...m），避免转义序列中的 ERROR/31m 字样干扰级别判定
        String upper = line.replaceAll("\u001B\\[[0-9;]*m", "").toUpperCase();
        if (upper.contains("ERROR") || upper.contains("EXCEPTION")) {
            return "ERROR";
        }
        if (upper.contains("WARN")) {
            return "WARN";
        }
        return "INFO";
    }

    private SlsQueryClient() {
    }
}
