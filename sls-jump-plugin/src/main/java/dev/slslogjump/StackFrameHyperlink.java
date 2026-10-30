package dev.slslogjump;

import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.JavaPsiFacade;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiFile;
import com.intellij.psi.search.FilenameIndex;
import com.intellij.psi.search.GlobalSearchScope;
import org.jetbrains.annotations.NotNull;

/**
 * 堆栈帧超链接：点击后在编辑器中打开对应源码并定位行号。
 *
 * <p>三级降级策略（v2 方案核心）：</p>
 * <ol>
 *   <li>按完整 FQN 找到类，且 FQN 与堆栈一致、行号在文件范围内 → 打开并精确定位；</li>
 *   <li>找到类但行号越界（本地分支与云端版本不一致）→ 打开文件，不定位行，状态栏提示；</li>
 *   <li>类找不到（第三方库 / 未 checkout）→ 退化为按文件名查找；再找不到 → 状态栏提示。</li>
 * </ol>
 */
public final class StackFrameHyperlink implements com.intellij.execution.filters.HyperlinkInfo {

    private final String classFqn;
    private final String fileName;
    private final int lineNumber;

    public StackFrameHyperlink(String classFqn, String fileName, int lineNumber) {
        this.classFqn = classFqn;
        this.fileName = fileName;
        this.lineNumber = lineNumber;
    }

    public String getClassFqn() {
        return classFqn;
    }

    public String getFileName() {
        return fileName;
    }

    public int getLineNumber() {
        return lineNumber;
    }

    @Override
    public void navigate(@NotNull Project project) {
        PsiClass psiClass = JavaPsiFacade.getInstance(project)
                .findClass(classFqn, GlobalSearchScope.allScope(project));

        // 一级：完整 FQN 命中
        if (psiClass != null && classFqn.equals(psiClass.getQualifiedName())) {
            PsiFile containingFile = psiClass.getContainingFile();
            VirtualFile vf = containingFile == null ? null : containingFile.getVirtualFile();
            if (vf == null) {
                NavigationLog.setLastMessage("已找到类 " + classFqn + "，但无法获取文件: " + fileName);
                return;
            }
            if (isLineInRange(vf, lineNumber)) {
                new OpenFileDescriptor(project, vf, lineNumber - 1, 0).navigate(true);
                NavigationLog.setLastMessage("已定位 " + classFqn + ":" + lineNumber);
            } else {
                // 二级：行号越界，打开但不定位
                new OpenFileDescriptor(project, vf).navigate(true);
                NavigationLog.setLastMessage(
                        "行号 " + lineNumber + " 超出 " + fileName + " 当前范围（本地分支可能与云端不一致），已仅打开文件");
            }
            return;
        }

        // 三级：按文件名退化查找
        PsiFile[] files = FilenameIndex.getFilesByName(
                project, fileName, GlobalSearchScope.allScope(project));
        if (files.length > 0) {
            VirtualFile vf = files[0].getVirtualFile();
            if (vf != null) {
                new OpenFileDescriptor(project, vf).navigate(true);
                NavigationLog.setLastMessage(
                        "本地未找到类 " + classFqn + "（分支或模块不一致），已打开同名文件 " + fileName);
                return;
            }
        }

        NavigationLog.setLastMessage(
                "本地未找到 " + classFqn + " 或 " + fileName + "，请确认代码已 checkout 到对应分支");
    }

    private static boolean isLineInRange(@NotNull VirtualFile vf, int line) {
        com.intellij.openapi.editor.Document document =
                FileDocumentManager.getInstance().getDocument(vf);
        if (document == null) {
            return false;
        }
        int lineCount = document.getLineCount();
        return line >= 1 && line <= lineCount;
    }

    /** 供调试/日志使用。 */
    @Override
    public String toString() {
        return "StackFrameHyperlink{" + classFqn + ":" + lineNumber + "}";
    }
}
