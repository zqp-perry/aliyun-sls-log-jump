package dev.slslogjump;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link SlsRealtimePolicy} 纯逻辑单测：同秒指纹去重 / 游标推进 / 补拉钳制 / 裁剪 / 积压。 */
class SlsRealtimePolicyTest {

    private static SlsQueryClient.LogEntry entry(long timeMillis, String content) {
        return new SlsQueryClient.LogEntry(timeMillis, "INFO", content);
    }

    private static Set<String> fps(SlsQueryClient.LogEntry... entries) {
        Set<String> set = new HashSet<>();
        for (SlsQueryClient.LogEntry e : entries) {
            set.add(SlsRealtimePolicy.fingerprint(e));
        }
        return set;
    }

    // ------------------------------------------------------------ 指纹

    @Test
    void 指纹_相同内容相同_不同内容不同() {
        assertEquals(SlsRealtimePolicy.fingerprint(entry(0, "abc")),
                SlsRealtimePolicy.fingerprint(entry(999, "abc")));
        assertNotEquals(SlsRealtimePolicy.fingerprint(entry(0, "abc")),
                SlsRealtimePolicy.fingerprint(entry(0, "abd")));
    }

    @Test
    void 指纹_null内容不抛() {
        SlsQueryClient.LogEntry e = new SlsQueryClient.LogEntry(0, "INFO", null);
        assertTrue(SlsRealtimePolicy.fingerprint(e).startsWith("0:"));
    }

    // ------------------------------------------------------------ 去重：游标秒重叠

    @Test
    void 首轮空指纹集_全部放行_游标推进到最后一条秒() {
        List<SlsQueryClient.LogEntry> batch = List.of(
                entry(1000_000, "a"), entry(1000_500, "b"), entry(1002_000, "c"));
        SlsRealtimePolicy.DedupResult r = SlsRealtimePolicy.dedup(batch, 900, Collections.emptySet());
        assertEquals(3, r.fresh.size());
        assertEquals(1002, r.newCursorSecond);
        assertEquals(1, r.lastSecondFingerprints.size()); // 指纹集只保留最后 1 秒（"c"）
    }

    @Test
    void 与上轮游标秒重叠_已展示指纹判重() {
        SlsQueryClient.LogEntry a = entry(1000_000, "a");
        SlsRealtimePolicy.DedupResult r = SlsRealtimePolicy.dedup(
                List.of(a), 1000, fps(a));
        assertTrue(r.fresh.isEmpty());
        assertEquals(1000, r.newCursorSecond);
    }

    @Test
    void 同秒新内容_不被旧指纹集吞掉() {
        SlsQueryClient.LogEntry a = entry(1000_000, "a");
        SlsQueryClient.LogEntry b = entry(1000_100, "b");
        SlsRealtimePolicy.DedupResult r = SlsRealtimePolicy.dedup(
                List.of(a, b), 1000, fps(a));
        assertEquals(1, r.fresh.size());
        assertEquals("b", r.fresh.get(0).content);
    }

    @Test
    void 批内同秒重复行判重() {
        List<SlsQueryClient.LogEntry> batch = List.of(
                entry(1000_000, "x"), entry(1000_100, "x"), entry(1000_200, "y"));
        SlsRealtimePolicy.DedupResult r = SlsRealtimePolicy.dedup(batch, 900, Collections.emptySet());
        assertEquals(2, r.fresh.size()); // x, y
    }

    @Test
    void 迟到乱序行_落在已展示区间丢弃() {
        List<SlsQueryClient.LogEntry> batch = List.of(
                entry(2000_000, "new"), entry(1000_000, "old"));
        SlsRealtimePolicy.DedupResult r = SlsRealtimePolicy.dedup(batch, 2000, Collections.emptySet());
        assertEquals(1, r.fresh.size());
        assertEquals("new", r.fresh.get(0).content);
    }

    @Test
    void 空批次_游标与指纹集原样保留() {
        Set<String> prev = fps(entry(1000_000, "a"));
        SlsRealtimePolicy.DedupResult r = SlsRealtimePolicy.dedup(List.of(), 1000, prev);
        assertTrue(r.fresh.isEmpty());
        assertEquals(1000, r.newCursorSecond);
        assertEquals(prev, r.lastSecondFingerprints);
    }

    @Test
    void 无新日志的轮次_游标不回退() {
        SlsRealtimePolicy.DedupResult r = SlsRealtimePolicy.dedup(
                List.of(), 5000, Collections.emptySet());
        assertEquals(5000, r.newCursorSecond);
    }

    @Test
    void 模拟心跳_多轮同内容行不重复展示() {
        // 第 1 轮：sec=1000 展示 "tick"；第 2 轮：窗口重叠 sec=1000 又拉到 "tick" → 判重
        SlsQueryClient.LogEntry tick1 = entry(1000_000, "tick");
        SlsRealtimePolicy.DedupResult r1 = SlsRealtimePolicy.dedup(
                List.of(tick1), 900, Collections.emptySet());
        assertEquals(1, r1.fresh.size());

        SlsQueryClient.LogEntry tick2 = entry(1000_900, "tick"); // 同秒同内容
        SlsRealtimePolicy.DedupResult r2 = SlsRealtimePolicy.dedup(
                List.of(tick2), r1.newCursorSecond, r1.lastSecondFingerprints);
        assertTrue(r2.fresh.isEmpty());
    }

    // ------------------------------------------------------------ 暂停恢复钳制

    @Test
    void 恢复钳制_游标新鲜不动() {
        assertEquals(1800, SlsRealtimePolicy.clampResumeCursor(1800, 2000, 300));
    }

    @Test
    void 恢复钳制_游标过旧拉回上限内() {
        assertEquals(1700, SlsRealtimePolicy.clampResumeCursor(1000, 2000, 300));
    }

    @Test
    void 间隙判定_超过上限才丢弃() {
        assertFalse(SlsRealtimePolicy.resumeDroppedGap(1800, 2000, 300));
        assertTrue(SlsRealtimePolicy.resumeDroppedGap(1000, 2000, 300));
    }

    // ------------------------------------------------------------ 裁剪

    @Test
    void 未超上限不裁剪() {
        assertEquals(0, SlsRealtimePolicy.linesToDrop(100, 100));
        assertEquals(0, SlsRealtimePolicy.linesToDrop(99, 100));
        assertEquals(0, SlsRealtimePolicy.linesToDrop(50, 0));
    }

    @Test
    void 超上限只裁超出部分() {
        assertEquals(1, SlsRealtimePolicy.linesToDrop(101, 100));
        assertEquals(2000, SlsRealtimePolicy.linesToDrop(12000, 10000));
    }

    // ------------------------------------------------------------ 积压

    @Test
    void 拉满上限视为积压() {
        assertTrue(SlsRealtimePolicy.backlogHit(2000, 2000));
        assertTrue(SlsRealtimePolicy.backlogHit(2001, 2000));
        assertFalse(SlsRealtimePolicy.backlogHit(1999, 2000));
        assertFalse(SlsRealtimePolicy.backlogHit(100, 0)); // 无上限不判积压
    }
}
