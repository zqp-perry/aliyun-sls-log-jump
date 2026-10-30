package dev.slslogjump;

import com.intellij.execution.impl.ConsoleViewImpl;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.LogicalPosition;
import com.intellij.openapi.editor.ScrollType;

import java.awt.Point;
import java.awt.Rectangle;

/**
 * 控制台环形缓冲（OOM 核心防护，全部方法仅限 EDT 调用）。
 *
 * <p>原理：ConsoleView 底层是 Editor Document；实时模式持续追加会导致文档无限增长。
 * 总行数超过 maxLines 后<b>整块删除头部多余行</b>（单次 deleteString 事务，
 * 避免逐行删除产生的海量文档事件）。</p>
 *
 * <p><b>视口保护</b>：删除头部行会让编辑器视口锚定的行号整体上移（内容视觉跳动）。
 * 裁剪前记录视口状态——贴底则裁完滚回底部（跟随最新日志）；
 * 用户正在翻历史则视口随内容平移同样行数，屏幕内容保持视觉不动。</p>
 */
public final class SlsConsoleBuffer {

    /** 视口"贴底"判定容差（行）。 */
    private static final int AT_BOTTOM_TOLERANCE_LINES = 2;

    /**
     * 追加后裁剪：文档总行数超过 maxLines 时删除头部多余行。
     *
     * @return 本次裁剪掉的行数（0 表示未裁剪）
     */
    public static int trim(ConsoleView console, int maxLines) {
        if (!(console instanceof ConsoleViewImpl impl)) {
            return 0;
        }
        Editor editor = impl.getEditor();
        if (editor == null) {
            return 0; // 控制台尚未初始化
        }
        Document doc = editor.getDocument();
        int total = doc.getLineCount();
        int drop = SlsRealtimePolicy.linesToDrop(total, maxLines);
        if (drop <= 0) {
            return 0;
        }

        // 裁剪前记录视口：是否贴底 + 视口顶部逻辑行
        var sm = editor.getScrollingModel();
        Rectangle area = sm.getVisibleAreaOnScrollingFinished();
        boolean atBottom = false;
        int topLine = 0;
        if (area != null && area.height > 0) {
            int bottomLine = editor.xyToLogicalPosition(new Point(0, area.y + area.height)).line;
            atBottom = bottomLine >= total - AT_BOTTOM_TOLERANCE_LINES;
            topLine = editor.xyToLogicalPosition(new Point(0, area.y)).line;
        }

        // 头部 drop 行 → 删除 [0, 第 drop 行行首) 区间
        final int endOffset = doc.getLineStartOffset(drop);
        ApplicationManager.getApplication().runWriteAction(() ->
                doc.deleteString(0, endOffset));

        // 视口恢复：贴底 → 滚回底部继续跟随最新；翻历史中 → 视口随内容平移，视觉不动
        LogicalPosition target = atBottom
                ? new LogicalPosition(Math.max(0, doc.getLineCount() - 1), 0)
                : new LogicalPosition(Math.max(0, topLine - drop), 0);
        sm.scrollTo(target, ScrollType.MAKE_VISIBLE);
        return drop;
    }

    /** 当前文档总行数（控制台未初始化时返回 0）。 */
    public static int currentLines(ConsoleView console) {
        if (!(console instanceof ConsoleViewImpl impl)) {
            return 0;
        }
        Editor editor = impl.getEditor();
        return editor == null ? 0 : editor.getDocument().getLineCount();
    }

    private SlsConsoleBuffer() {
    }
}
