package dev.slslogjump;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 跳转结果状态栏的持有者。
 *
 * <p>ToolWindow 底部 label 与堆栈超链接之间通过该类通信。
 * Phase 1 demo 用最简的观察者模式；后续版本可换为 MessageBus 主题。</p>
 */
public final class NavigationLog {

    public interface Listener {
        void onNavigationMessage(String message);
    }

    private static final List<Listener> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile String lastMessage = "";

    private NavigationLog() {
    }

    public static void addListener(Listener listener) {
        LISTENERS.add(listener);
    }

    /**
     * 移除监听器。必须在持有方（Tool Window / Project）销毁时调用，
     * 否则 static 列表会随每次 createToolWindowContent 累积，造成内存泄漏与重复回调。
     *
     * @see com.intellij.openapi.Disposer
     */
    public static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    public static void setLastMessage(String message) {
        lastMessage = message;
        for (Listener listener : LISTENERS) {
            listener.onNavigationMessage(message);
        }
    }

    public static String getLastMessage() {
        return lastMessage;
    }
}
