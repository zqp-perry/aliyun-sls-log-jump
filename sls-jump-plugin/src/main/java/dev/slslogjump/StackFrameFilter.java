package dev.slslogjump;

import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.Filter.Result;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 堆栈帧 Console Filter。
 *
 * <p>与 Phase 1a demo 的 {@code StackFrame.FRAME_PATTERN} 保持同一套正则：
 * {@code at com.x.Foo.bar(Foo.java:45)}。
 * 与 Phase 1a 已验证的解析规则一致，保证规则正确性经过单测覆盖。</p>
 */
public final class StackFrameFilter implements Filter {

    static final Pattern FRAME_PATTERN = Pattern.compile(
            "at\\s+([\\w.$]+)\\.([\\w$<>]+)\\(([\\w$]+\\.java):(\\d+)\\)");

    private final Project project;

    public StackFrameFilter(Project project) {
        this.project = project;
    }

    @Override
    public @Nullable Result applyFilter(@NotNull String line, int entireLength) {
        Matcher matcher = FRAME_PATTERN.matcher(line);
        if (!matcher.find()) {
            return null;
        }
        // 一行内匹配最后一帧（堆栈通常一行一帧；多帧行取最后一帧，避免重复链接）
        // 注意：group() 必须在 find() 成功后立即读取，失败的 find() 会重置匹配状态
        int start = matcher.start();
        int end = matcher.end();
        String classFqn = matcher.group(1);
        String fileName = matcher.group(3);
        int lineNumber = Integer.parseInt(matcher.group(4));
        while (matcher.find()) {
            start = matcher.start();
            end = matcher.end();
            classFqn = matcher.group(1);
            fileName = matcher.group(3);
            lineNumber = Integer.parseInt(matcher.group(4));
        }

        return new Result(start, end,
                new StackFrameHyperlink(classFqn, fileName, lineNumber));
    }

    public Project getProject() {
        return project;
    }
}
