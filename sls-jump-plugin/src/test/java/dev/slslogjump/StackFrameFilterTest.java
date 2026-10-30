package dev.slslogjump;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.HyperlinkInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 堆栈 Filter 单元测试（Phase 1b）。
 *
 * <p>applyFilter 只依赖正则与 HyperlinkInfo 构造，不触碰 IDE 运行时，
 * 因此可以脱离沙箱 IDE 直接运行。</p>
 */
class StackFrameFilterTest {

    private final StackFrameFilter filter = new StackFrameFilter(null);

    @Test
    @DisplayName("堆栈行：返回覆盖整行的链接结果")
    void appliesResultToFrameLine() {
        String line = "\tat org.example.shop.service.OrderServiceImpl.getUser(OrderServiceImpl.java:123)";

        Filter.Result result = filter.applyFilter(line, line.length());

        assertNotNull(result, "堆栈行应产生链接结果");
        // 行首是 tab，匹配从 "at" 开始（索引 1），覆盖到行尾
        assertEquals(line.indexOf("at"), result.getHighlightStartOffset());
        assertEquals(line.length(), result.getHighlightEndOffset());

        HyperlinkInfo info = result.getHyperlinkInfo();
        assertTrue(info instanceof StackFrameHyperlink, "链接应为堆栈超链接");
        StackFrameHyperlink link = (StackFrameHyperlink) info;
        assertEquals("org.example.shop.service.OrderServiceImpl", link.getClassFqn());
        assertEquals("OrderServiceImpl.java", link.getFileName());
        assertEquals(123, link.getLineNumber());
    }

    @Test
    @DisplayName("非堆栈行：返回 null")
    void returnsNullForNonStackLine() {
        assertNull(filter.applyFilter("2026-10-22 14:03:11 INFO normal log line", 40));
        assertNull(filter.applyFilter("java.lang.NullPointerException: msg", 37));
    }

    @Test
    @DisplayName("Native Method / Unknown Source 帧不产生链接")
    void skipsNativeFrames() {
        assertNull(filter.applyFilter(
                "\tat java.base/jdk.internal.reflect.NativeMethodAccessorImpl.invoke0(Native Method)", 88));
    }

    @Test
    @DisplayName("内部类帧：FQN 带 $ 也正确解析")
    void innerClassFrame() {
        String line = "\tat org.foo.Foo$Bar.baz(Foo.java:9)";
        Filter.Result result = filter.applyFilter(line, line.length());
        assertNotNull(result);
        assertEquals("org.foo.Foo$Bar", ((StackFrameHyperlink) result.getHyperlinkInfo()).getClassFqn());
    }

    @Test
    @DisplayName("一行多帧：链接覆盖最后一帧")
    void multiFrameOnOneLine() {
        String line = "\tat com.a.B.run(B.java:1) at com.a.C.run(C.java:2)";
        Filter.Result result = filter.applyFilter(line, line.length());
        assertNotNull(result);
        assertEquals("com.a.C", ((StackFrameHyperlink) result.getHyperlinkInfo()).getClassFqn());
        assertTrue(result.getHighlightStartOffset() > 0, "链接应从第二帧开始");
    }
}
