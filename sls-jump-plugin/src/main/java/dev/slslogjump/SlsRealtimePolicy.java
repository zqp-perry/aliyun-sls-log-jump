package dev.slslogjump;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 实时拉取的纯策略逻辑（无 IntelliJ / 无网络依赖，可脱离平台单测）。
 *
 * <p>三个边界问题的唯一裁决点：</p>
 * <ol>
 *   <li><b>同秒去重</b>：轮询窗口 from=游标秒，与上一轮重叠 1 秒；
 *       该秒内已展示过的行按「内容指纹」去重，不丢不重。</li>
 *   <li><b>积压丢弃</b>：单轮拉取量达到上限视为积压，只保留最新 maxBatch 条
 *       （由查询层倒序拉取天然保证），返回警告供状态栏提示。</li>
 *   <li><b>暂停恢复补拉</b>：恢复时游标最多回看 maxBackfillSeconds 秒，
 *       更早的间隙直接丢弃（防止暂停数小时后一次性灌入巨量日志）。</li>
 * </ol>
 */
public final class SlsRealtimePolicy {

    /** 单条日志的重复判定指纹（内容 hash + 长度，秒级窗口内碰撞概率可忽略）。 */
    public static String fingerprint(SlsQueryClient.LogEntry e) {
        String c = e.content == null ? "" : e.content;
        return c.length() + ":" + c.hashCode();
    }

    /** 去重结果：新日志 + 新游标秒 + 新的「最后 1 秒指纹集」（供下一轮重叠去重）。 */
    public static final class DedupResult {
        public final List<SlsQueryClient.LogEntry> fresh;
        public final long newCursorSecond;
        public final Set<String> lastSecondFingerprints;

        DedupResult(List<SlsQueryClient.LogEntry> fresh, long newCursorSecond,
                    Set<String> lastSecondFingerprints) {
            this.fresh = fresh;
            this.newCursorSecond = newCursorSecond;
            this.lastSecondFingerprints = lastSecondFingerprints;
        }
    }

    /**
     * 对一批正序日志做「同秒重叠去重」。
     *
     * @param forwardBatch            本轮拉到的正序日志（时间升序；乱序按容错处理）
     * @param cursorSecond            上一轮游标（已展示到的秒，含）
     * @param prevSecondFingerprints  上一轮「游标秒」内已展示行的指纹集；首轮传空集
     */
    public static DedupResult dedup(List<SlsQueryClient.LogEntry> forwardBatch,
                                    long cursorSecond,
                                    Set<String> prevSecondFingerprints) {
        List<SlsQueryClient.LogEntry> out = new ArrayList<>();
        long lastSec = cursorSecond;
        Set<String> secHashes = new HashSet<>();
        for (SlsQueryClient.LogEntry e : forwardBatch) {
            long sec = e.timeMillis / 1000L;
            if (sec < lastSec) {
                continue; // 迟到/乱序且落在已展示区间，丢弃
            }
            if (sec > lastSec) {
                lastSec = sec;
                secHashes.clear();
            }
            String fp = fingerprint(e);
            if (secHashes.contains(fp)) {
                continue; // 本批内重复
            }
            if (sec == cursorSecond && prevSecondFingerprints.contains(fp)) {
                continue; // 与上一轮游标秒重叠的重复
            }
            secHashes.add(fp);
            out.add(e);
        }
        long newCursor = Math.max(cursorSecond, lastSec);
        Set<String> newFingerprints = out.isEmpty()
                ? prevSecondFingerprints   // 无新日志：保留旧指纹集
                : secHashes;
        return new DedupResult(out, newCursor, newFingerprints);
    }

    /**
     * 恢复时的游标钳制：暂停后游标落后超过 maxBackfillSeconds 时，
     * 只从 now-maxBackfillSeconds 开始补拉（返回新游标）。
     */
    public static long clampResumeCursor(long cursorSecond, long nowSecond, long maxBackfillSeconds) {
        long earliest = nowSecond - maxBackfillSeconds;
        return Math.max(cursorSecond, earliest);
    }

    /** 恢复补拉是否丢弃了间隙（游标落后超过 maxBackfillSeconds）。 */
    public static boolean resumeDroppedGap(long cursorSecond, long nowSecond, long maxBackfillSeconds) {
        return cursorSecond < nowSecond - maxBackfillSeconds;
    }

    /** 环形缓冲：追加后总行数超上限时应从头裁掉的行数（只裁超出部分）。 */
    public static int linesToDrop(int totalLinesAfterAppend, int maxLines) {
        if (maxLines <= 0 || totalLinesAfterAppend <= maxLines) {
            return 0;
        }
        return totalLinesAfterAppend - maxLines;
    }

    /** 单轮拉取是否触发积压（达到上限即视为可能还有更多被截断）。 */
    public static boolean backlogHit(int fetchedCount, int maxBatch) {
        return maxBatch > 0 && fetchedCount >= maxBatch;
    }

    private SlsRealtimePolicy() {
    }
}
