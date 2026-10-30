package dev.slslogjump;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * SlsQueryClient 纯逻辑单测（不依赖 IDE 运行时、不发网络请求）。
 */
public class SlsQueryClientTest {

    @Test
    public void queryTypeAllMapsToWildcard() {
        assertEquals("*", SlsQueryClient.QueryType.ALL.getQuery());
        assertEquals("全部", SlsQueryClient.QueryType.ALL.getLabel());
    }

    @Test
    public void queryTypeErrorIncludesErrorAndException() {
        String q = SlsQueryClient.QueryType.ERROR.getQuery();
        assertTrue(q.contains("ERROR"));
        assertTrue(q.contains("Exception"));
    }

    @Test
    public void queryTypeWarnAndInfo() {
        assertEquals("WARN", SlsQueryClient.QueryType.WARN.getQuery());
        assertEquals("INFO", SlsQueryClient.QueryType.INFO.getQuery());
        assertEquals("DEBUG", SlsQueryClient.QueryType.DEBUG.getQuery());
        assertEquals("调试", SlsQueryClient.QueryType.DEBUG.getLabel());
    }

    @Test
    public void buildQueryWithAppFilter() {
        // 指定应用：容器过滤 + 类型过滤
        assertEquals(
                "_container_name_:sample-app and (ERROR OR Exception)",
                SlsQueryClient.QueryType.ERROR.buildQuery("sample-app"));
        // 指定应用 + 全部类型：只有容器过滤
        assertEquals(
                "_container_name_:sample-app",
                SlsQueryClient.QueryType.ALL.buildQuery("sample-app"));
        // 应用带空格：trim 后拼入
        assertEquals(
                "_container_name_:sample-app",
                SlsQueryClient.QueryType.ALL.buildQuery("  sample-app  "));
    }

    @Test
    public void buildQueryWithoutAppFilter() {
        // 应用为空/null：返回原语句（查全部应用）
        assertEquals("ERROR OR Exception", SlsQueryClient.QueryType.ERROR.buildQuery(""));
        assertEquals("ERROR OR Exception", SlsQueryClient.QueryType.ERROR.buildQuery("   "));
        assertEquals("*", SlsQueryClient.QueryType.ALL.buildQuery(null));
    }

    @Test
    public void maskSecretCoversStsAndPlainAccessKeyId() {
        String in = "Unauthorized - AccessKeyId not found: STS.NXdx5qBoqabc123";
        String out = SlsQueryClient.maskSecret(in);
        // 安全断言：任何 AK 原文都不得出现在脱敏结果中
        assertFalse(out.contains("NXdx5qBoqabc123"));
        assertTrue(out.contains("***"));

        String in2 = "InvalidAccessKeyId: AccessKeyId not found: LTAI5tAbcDef";
        String out2 = SlsQueryClient.maskSecret(in2);
        assertFalse(out2.contains("LTAI5tAbcDef"));
        assertTrue(out2.contains("***"));
    }

    @Test
    public void credentialExpiredCodesRecognized() {
        com.aliyun.openservices.log.exception.LogException expired =
                new com.aliyun.openservices.log.exception.LogException(
                        "SecurityTokenExpired", "token expired", null);
        assertTrue(SlsQueryClient.isCredentialExpired(expired));

        com.aliyun.openservices.log.exception.LogException other =
                new com.aliyun.openservices.log.exception.LogException(
                        "InternalServerError", "oops", null);
        assertFalse(SlsQueryClient.isCredentialExpired(other));
    }
}
