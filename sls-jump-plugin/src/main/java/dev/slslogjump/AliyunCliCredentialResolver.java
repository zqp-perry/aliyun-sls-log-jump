package dev.slslogjump;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 阿里云凭证解析器（只读、不缓存、绝不打印凭据）。
 *
 * <p>解析优先级：</p>
 * <ol>
 *   <li><b>环境变量</b>（Alibaba Cloud 官方约定名，兼容旧 {@code ALIYUN_AK_*}）：
 *       {@code ALIBABA_CLOUD_ACCESS_KEY_ID} / {@code ALIBABA_CLOUD_ACCESS_KEY_SECRET}
 *       / {@code ALIBABA_CLOUD_SECURITY_TOKEN}</li>
 *   <li><b>aliyun-cli 配置回退</b>：读取 {@code ~/.aliyun/config.json} 中
 *       {@code current} 指向的 profile，取其 AK/STS 字段。</li>
 * </ol>
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li>每次调用都<b>现读</b> config.json，不缓存——STS 过期后更新本机 CLI 配置即可生效，无需重启 IDE。</li>
 *   <li>任何异常都只说明<b>原因</b>，绝不输出 akId / akSecret / token 原文。</li>
 *   <li>支持「长期 AK（无 token）」与「STS 临时凭证（带 token）」两种形态，
 *       由 {@link Credential#hasToken()} 区分。</li>
 * </ul>
 */
public final class AliyunCliCredentialResolver {

    /** 不可变凭证三元组。 */
    public static final class Credential {
        public final String accessKeyId;
        public final String accessKeySecret;
        public final String securityToken; // STS 场景非空；长期 AK 场景为 null

        public Credential(String accessKeyId, String accessKeySecret, String securityToken) {
            this.accessKeyId = accessKeyId;
            this.accessKeySecret = accessKeySecret;
            this.securityToken = securityToken;
        }

        /** 是否为 STS 临时凭证（带 security token）。 */
        public boolean hasToken() {
            return securityToken != null && !securityToken.isEmpty();
        }
    }

    // 环境变量候选名（按顺序取第一个非空值），官方名在前、旧名兜底
    private static final String[] ENV_ID = {
            "ALIBABA_CLOUD_ACCESS_KEY_ID", "ALIYUN_AK_ID"
    };
    private static final String[] ENV_SECRET = {
            "ALIBABA_CLOUD_ACCESS_KEY_SECRET", "ALIYUN_AK_SECRET"
    };
    private static final String[] ENV_TOKEN = {
            "ALIBABA_CLOUD_SECURITY_TOKEN", "ALIYUN_STS_TOKEN"
    };

    private static String firstEnv(String[] keys) {
        for (String k : keys) {
            String v = System.getenv(k);
            if (v != null && !v.isEmpty()) {
                return v;
            }
        }
        return null;
    }

    /**
     * 解析凭证：环境变量优先，失败回退 config.json。
     *
     * @throws IllegalStateException 两种来源都拿不到有效凭证时
     */
    public static Credential resolve() {
        String id = firstEnv(ENV_ID);
        String secret = firstEnv(ENV_SECRET);
        String token = firstEnv(ENV_TOKEN);
        if (id != null && secret != null) {
            return new Credential(id, secret, token);
        }
        return fromAliyunCliConfig();
    }

    private static Credential fromAliyunCliConfig() {
        Path path = Paths.get(System.getProperty("user.home"), ".aliyun", "config.json");
        return fromConfig(path);
    }

    /** 可测试的文件读取入口；不回显文件内容、真实路径或 profile 名称。 */
    static Credential fromConfig(Path path) {
        if (!Files.exists(path)) {
            throw new IllegalStateException(
                    "未找到阿里云 CLI 配置，请先配置本机阿里云 CLI，"
                            + "或设置 IDE 环境变量 ALIBABA_CLOUD_ACCESS_KEY_ID / ALIBABA_CLOUD_ACCESS_KEY_SECRET。");
        }
        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(Files.readAllBytes(path));
            JsonNode profiles = root == null ? null : root.get("profiles");
            if (profiles == null || !profiles.isArray() || profiles.isEmpty()) {
                throw new IllegalStateException("CLI 配置缺少有效的 profiles，请重新配置本机阿里云 CLI");
            }
            String current = root.path("current").asText("");
            JsonNode target = null;
            for (JsonNode p : profiles) {
                if (current.equals(p.path("name").asText())) {
                    target = p;
                    break;
                }
            }
            if (target == null && current.isBlank() && profiles.size() == 1) {
                target = profiles.get(0);
            }
            if (target == null) {
                throw new IllegalStateException("CLI 当前 profile 不存在或未选定，请在阿里云 CLI 中选择要使用的账号配置");
            }
            String mode = target.path("mode").asText("").trim();
            if (!mode.isEmpty() && !"AK".equalsIgnoreCase(mode) && !"StsToken".equalsIgnoreCase(mode)) {
                throw new IllegalStateException("当前 CLI 凭证模式不支持直接读取，请使用 AK/StsToken 配置，"
                        + "或将目标身份的临时凭证提供给 IDE 环境变量");
            }
            String id = target.path("access_key_id").asText(null);
            String secret = target.path("access_key_secret").asText(null);
            String token = target.path("sts_token").asText(null);
            if (id == null || id.isBlank() || secret == null || secret.isBlank()) {
                throw new IllegalStateException(
                        "当前 CLI 配置中缺少 access_key_id / access_key_secret，"
                                + "请更新本机 CLI 配置或使用 IDE 环境变量。");
            }
            return new Credential(id, secret, token);
        } catch (IOException e) {
            throw new IllegalStateException("无法读取或解析阿里云 CLI 配置，请检查文件权限和 JSON 格式");
        }
    }

    private AliyunCliCredentialResolver() {
    }
}
