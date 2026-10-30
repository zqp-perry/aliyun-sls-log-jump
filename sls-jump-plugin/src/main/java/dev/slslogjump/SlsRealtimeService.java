package dev.slslogjump;

import com.aliyun.openservices.log.exception.LogException;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 实时拉取服务（tail -f 式轮询，生命周期挂接工具窗口 Content）。
 *
 * <p>后台单线程调度器每 pollInterval 秒拉一次增量窗口（from=游标秒），
 * 经 {@link SlsRealtimePolicy#dedup} 去重后，把新日志投递到 EDT 追加进控制台。</p>
 *
 * <p>状态机：RUNNING →(pause)→ PAUSED →(resume)→ RUNNING →(reset/stop)→ 重建或终止。</p>
 *
 * <p><b>边界保障</b>：</p>
 * <ul>
 *   <li>单轮失败静默重试；连续 3 次失败自动 PAUSED 并回调错误原因（凭证过期/网络）</li>
 *   <li>单轮拉取上限 maxBatch（查询层倒序拉取，天然保留最新 maxBatch 条）</li>
 *   <li>恢复时游标最多回看 5 分钟（{@link SlsRealtimePolicy#clampResumeCursor}）</li>
 *   <li>{@link Disposable} dispose 时关闭线程池，不留后台残留</li>
 * </ul>
 */
public final class SlsRealtimeService implements Disposable {

    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final long MAX_BACKFILL_SECONDS = 5 * 60L;
    private static final DateTimeFormatter HHMMSS =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 运行状态。 */
    public enum State { RUNNING, PAUSED, STOPPED }

    /** 状态投递给 UI（保证在 EDT 回调）。 */
    public interface Listener {
        /** 新日志到达（fresh 已去重、正序；可能为空列表的周期刷新）。 */
        void onBatch(List<SlsQueryClient.LogEntry> fresh, long cursorSecond, boolean backlog);

        /** 状态变化（含给状态栏的摘要文本，已脱敏）。 */
        void onState(State state, String summary);

        /** 恢复时丢弃了过长间隙（提示用）。 */
        void onGapDropped(long droppedSeconds);
    }

    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "SLS-Realtime-Poller");
                t.setDaemon(true);
                return t;
            });

    private final Listener listener;
    private final String endpoint;
    private final String project;
    private final String logStore;
    private final String query;
    private final int maxBatch;
    private final int pollIntervalSeconds;

    private final AtomicReference<State> state = new AtomicReference<>(State.RUNNING);
    private volatile long cursorSecond;
    /** 上一轮「游标秒」内已展示行的指纹集（供重叠窗口去重）。 */
    private volatile Set<String> lastSecondFingerprints = Collections.emptySet();
    private volatile long pausedAtSecond;
    private volatile long lastRefreshSecond;
    private int consecutiveFailures; // 仅在轮询线程访问

    /**
     * @param parentDisposable     生命周期宿主（工具窗口 Content），dispose 时自动停止
     * @param startFromSecond      起始游标（重置=当前时间）
     */
    public SlsRealtimeService(Disposable parentDisposable, Listener listener,
                              String endpoint, String project, String logStore, String query,
                              int pollIntervalSeconds, int maxBatch, long startFromSecond) {
        this.listener = listener;
        this.endpoint = endpoint;
        this.project = project;
        this.logStore = logStore;
        this.query = query;
        this.pollIntervalSeconds = Math.max(1, pollIntervalSeconds);
        this.maxBatch = Math.max(100, maxBatch);
        this.cursorSecond = startFromSecond;
        Disposer.register(parentDisposable, this);
    }

    /** 启动轮询。 */
    public void start() {
        executor.scheduleWithFixedDelay(this::pollOnce, 0, pollIntervalSeconds, TimeUnit.SECONDS);
    }

    /** 暂停：停止拉取，保留游标与已展示内容。 */
    public void pause() {
        if (state.getAndSet(State.PAUSED) == State.RUNNING) {
            pausedAtSecond = Instant.now().getEpochSecond();
            fireState("已暂停 · 游标 " + hhmmss(cursorSecond));
        }
    }

    /** 恢复：从游标继续，间隙最多补拉 5 分钟。 */
    public void resume() {
        State prev = state.getAndSet(State.RUNNING);
        if (prev == State.PAUSED) {
            long now = Instant.now().getEpochSecond();
            long before = cursorSecond;
            cursorSecond = SlsRealtimePolicy.clampResumeCursor(cursorSecond, now, MAX_BACKFILL_SECONDS);
            if (SlsRealtimePolicy.resumeDroppedGap(before, now, MAX_BACKFILL_SECONDS)) {
                long dropped = cursorSecond - before;
                fireEdt(() -> listener.onGapDropped(dropped));
            }
            consecutiveFailures = 0;
            fireState("实时中 · 上次刷新 -");
        }
    }

    /** 当前状态。 */
    public State currentState() {
        return state.get();
    }

    // ------------------------------------------------------------------ 内部

    private void pollOnce() {
        if (state.get() != State.RUNNING) {
            return;
        }
        long now = Instant.now().getEpochSecond();
        long from = Math.min(cursorSecond, now); // 时钟回拨容错
        try {
            // 倒序拉取（最新优先）+ 上限 maxBatch → 天然「积压只保留最新 N 条」；
            // query() 返回前已反转回正序。
            List<SlsQueryClient.LogEntry> batch = SlsQueryClient.query(
                    endpoint, project, logStore, from, now, query, maxBatch);
            consecutiveFailures = 0;

            boolean backlog = SlsRealtimePolicy.backlogHit(batch.size(), maxBatch);
            SlsRealtimePolicy.DedupResult result =
                    SlsRealtimePolicy.dedup(batch, cursorSecond, lastSecondFingerprints);
            cursorSecond = result.newCursorSecond;
            lastSecondFingerprints = result.lastSecondFingerprints;
            lastRefreshSecond = now;

            final List<SlsQueryClient.LogEntry> fresh =
                    result.fresh.isEmpty() ? Collections.emptyList() : result.fresh;
            fireEdt(() -> listener.onBatch(fresh, cursorSecond, backlog));
            if (fresh.isEmpty()) {
                fireState(String.format("实时中 · 上次刷新 %s · 暂无新日志", hhmmss(now)));
            }
        } catch (LogException e) {
            handleFailure("SLS 错误 " + e.GetErrorCode() + " - "
                    + SlsQueryClient.maskSecret(e.GetErrorMessage())
                    + (SlsQueryClient.isCredentialExpired(e)
                    ? "（STS 临时凭证已过期，更新本机阿里云凭证后点「恢复」）" : ""));
        } catch (IllegalStateException e) {
            handleFailure("凭证解析失败: " + e.getMessage());
        } catch (RuntimeException e) {
            handleFailure("查询异常: " + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " - " + e.getMessage()));
        }
    }

    private void handleFailure(String maskedReason) {
        consecutiveFailures++;
        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES
                && state.get() == State.RUNNING) {
            pausedAtSecond = Instant.now().getEpochSecond();
            state.set(State.PAUSED);
            fireState("已自动暂停（连续 " + consecutiveFailures + " 次失败）· " + maskedReason);
        }
    }

    private void fireState(String summary) {
        fireEdt(() -> listener.onState(state.get(), summary));
    }

    private void fireEdt(Runnable r) {
        Runnable guarded = () -> {
            if (state.get() != State.STOPPED) {
                r.run();
            }
        };
        if (ApplicationManager.getApplication() == null) {
            guarded.run(); // 纯环境（单测）直接执行
        } else {
            ApplicationManager.getApplication().invokeLater(guarded);
        }
    }

    private static String hhmmss(long epochSecond) {
        return HHMMSS.format(Instant.ofEpochSecond(epochSecond));
    }

    /** 上次成功刷新时间（状态栏展示用），0 表示尚未刷新。 */
    public long lastRefreshSecond() {
        return lastRefreshSecond;
    }

    @Override
    public void dispose() {
        state.set(State.STOPPED);
        executor.shutdownNow();
    }
}
