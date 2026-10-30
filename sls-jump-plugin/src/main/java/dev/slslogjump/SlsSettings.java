package dev.slslogjump;

import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * SLS 连接配置（project 级持久化）。
 *
 * <p><b>安全边界</b>：只持久化非敏感目标参数（endpoint / project / logstore / 默认时间范围），
 * 凭证一律由 {@link AliyunCliCredentialResolver} 现场解析，绝不写入任何存储。</p>
 */
@State(name = "PublicSlsLogJumpSettings", storages = @Storage("sls-log-jump-public.xml"))
public final class SlsSettings implements PersistentStateComponent<SlsSettings.State> {

    /** 公开版不预置任何账号或内部项目，首次查询前由使用者配置。 */
    public static final String DEFAULT_ENDPOINT = "";
    public static final String DEFAULT_PROJECT = "";
    public static final String DEFAULT_LOGSTORE = "";
    public static final int DEFAULT_MINUTES = 15;
    /** 实时轮询间隔默认 2 秒。 */
    public static final int DEFAULT_POLL_INTERVAL = 2;
    public static final int MIN_POLL_INTERVAL = 1;
    public static final int MAX_POLL_INTERVAL = 10;
    /** 环形缓冲行数上限，限制控制台内存占用。 */
    public static final int DEFAULT_MAX_BUFFER_LINES = 13500;
    /** 旧版默认缓冲值：持久化配置里等于该值视为"未设置"，跟随新默认。 */
    private static final int LEGACY_MAX_BUFFER_LINES = 10000;
    /** 实时单轮拉取上限（积压时只保留最新 N 条）。 */
    public static final int DEFAULT_MAX_BATCH_LINES = 2000;

    /** 可持久化状态（XmlSerializer 要求 public 字段）。 */
    public static final class State {
        public String endpoint = DEFAULT_ENDPOINT;
        public String project = DEFAULT_PROJECT;
        public String logStore = DEFAULT_LOGSTORE;
        public int defaultMinutes = DEFAULT_MINUTES;
        public int pollIntervalSeconds = DEFAULT_POLL_INTERVAL;
        public int maxBufferLines = DEFAULT_MAX_BUFFER_LINES;
        public int maxBatchLines = DEFAULT_MAX_BATCH_LINES;
    }

    private final State state = new State();

    public static SlsSettings getInstance(Project project) {
        return project.getService(SlsSettings.class);
    }

    @Nullable
    @Override
    public State getState() {
        return state;
    }

    @Override
    public void loadState(@NotNull State loaded) {
        // 直接复制 public 字段（2026 平台已移除 XmlSerializer.copyInto）
        state.endpoint = clean(loaded.endpoint);
        state.project = clean(loaded.project);
        state.logStore = clean(loaded.logStore);
        state.defaultMinutes = loaded.defaultMinutes;
        state.pollIntervalSeconds = loaded.pollIntervalSeconds;
        // 缓冲上限：旧默认 10000 是历史遗留（无配置入口时写死的值），不迁移、跟随新默认
        state.maxBufferLines = (loaded.maxBufferLines == LEGACY_MAX_BUFFER_LINES)
                ? state.maxBufferLines : loaded.maxBufferLines;
        state.maxBatchLines = loaded.maxBatchLines;
    }

    public String getEndpoint() {
        return state.endpoint;
    }

    public String getProject() {
        return state.project;
    }

    public String getLogStore() {
        return state.logStore;
    }

    public boolean isConfigured() {
        return !clean(state.endpoint).isEmpty()
                && !clean(state.project).isEmpty()
                && !clean(state.logStore).isEmpty();
    }

    /** 三个目标参数独立保存，不包含凭证。 */
    public void updateTarget(String endpoint, String project, String logStore) {
        state.endpoint = clean(endpoint);
        state.project = clean(project);
        state.logStore = clean(logStore);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    public int getDefaultMinutes() {
        return state.defaultMinutes;
    }

    /** 实时轮询间隔（秒），钳制在 [1,10]。 */
    public int getPollIntervalSeconds() {
        return clamp(state.pollIntervalSeconds, MIN_POLL_INTERVAL, MAX_POLL_INTERVAL, DEFAULT_POLL_INTERVAL);
    }

    /** 环形缓冲行数上限，钳制在 [1000,50000]。 */
    public int getMaxBufferLines() {
        return clamp(state.maxBufferLines, 1000, 50000, DEFAULT_MAX_BUFFER_LINES);
    }

    /** 实时单轮拉取上限，钳制在 [100,10000]。 */
    public int getMaxBatchLines() {
        return clamp(state.maxBatchLines, 100, 10000, DEFAULT_MAX_BATCH_LINES);
    }

    private static int clamp(int v, int min, int max, int fallback) {
        if (v <= 0) {
            return fallback;
        }
        return Math.max(min, Math.min(max, v));
    }

    public void update(String endpoint, String project, String logStore, int defaultMinutes,
                       Integer pollIntervalSeconds, Integer maxBufferLines, Integer maxBatchLines) {
        if (endpoint != null && !endpoint.isBlank()) {
            state.endpoint = endpoint.trim();
        }
        if (project != null && !project.isBlank()) {
            state.project = project.trim();
        }
        if (logStore != null && !logStore.isBlank()) {
            state.logStore = logStore.trim();
        }
        if (defaultMinutes > 0) {
            state.defaultMinutes = defaultMinutes;
        }
        if (pollIntervalSeconds != null && pollIntervalSeconds > 0) {
            state.pollIntervalSeconds = pollIntervalSeconds;
        }
        if (maxBufferLines != null && maxBufferLines > 0) {
            state.maxBufferLines = maxBufferLines;
        }
        if (maxBatchLines != null && maxBatchLines > 0) {
            state.maxBatchLines = maxBatchLines;
        }
    }
}
