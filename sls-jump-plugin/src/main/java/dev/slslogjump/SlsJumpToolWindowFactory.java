package dev.slslogjump;

import com.aliyun.openservices.log.exception.LogException;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.execution.ui.ConsoleViewContentType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPanel;
import com.intellij.util.ui.JBUI;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import org.jetbrains.annotations.NotNull;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * SLS 日志跳转 Tool Window（双模式：历史 / 实时）。
 *
 * <p><b>历史模式</b>：一次性拉取所选时间范围的历史日志，堆栈可点击跳转。</p>
 * <p><b>实时模式</b>：{@link SlsRealtimeService} 每 N 秒轮询增量（tail -f 语义），
 * 经环形缓冲 {@link SlsConsoleBuffer} 裁剪防 OOM，状态栏展示运行/暂停/异常与缓冲计数，
 * 提供 暂停 / 恢复 / 重置。</p>
 *
 * <p>两个模式各持独立 ConsoleView（CardLayout 切换），来回切换内容互不覆盖；
 * JToggleButton 原生样式，打开时「历史」默认选中并获得焦点（呈现选中蓝框）；
 * 初始化无示例数据，仅显示模式提示。</p>
 */
public final class SlsJumpToolWindowFactory implements ToolWindowFactory {

    private static final DateTimeFormatter HHMMSS =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    /** 状态点语义色（JBColor 浅/深双值，主题自适应）。 */
    private static final JBColor RUN_GREEN = new JBColor(new Color(0x368746), new Color(0x5FB865));
    private static final JBColor PAUSE_YELLOW = new JBColor(new Color(0x9E8A1E), new Color(0xD6AE58));
    private static final JBColor ERR_RED = new JBColor(new Color(0xD92B2B), new Color(0xF75464));
    private static final JBColor META_GRAY = new JBColor(new Color(0x6C707E), new Color(0x9DA0A8));

    private static final String CARD_HISTORY = "history";
    private static final String CARD_REALTIME = "realtime";

    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        SlsSettings settings = SlsSettings.getInstance(project);
        AtomicLong historyRequest = new AtomicLong();

        // 1. 两个独立控制台：历史 / 实时（各自挂堆栈过滤器，内容互不覆盖）
        TextConsoleBuilder histBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        histBuilder.addFilter(new StackFrameFilter(project));
        ConsoleView historyConsole = histBuilder.getConsole();

        TextConsoleBuilder rtBuilder = TextConsoleBuilderFactory.getInstance().createBuilder(project);
        rtBuilder.addFilter(new StackFrameFilter(project));
        ConsoleView realtimeConsole = rtBuilder.getConsole();

        CardLayout cards = new CardLayout();
        JPanel center = new JPanel(cards);
        center.add(historyConsole.getComponent(), CARD_HISTORY);
        center.add(realtimeConsole.getComponent(), CARD_REALTIME);

        // 2. 工具栏控件
        javax.swing.JTextField appField = new javax.swing.JTextField(project.getName(), 16);
        appField.setToolTipText("按应用（k8s 容器名）过滤；清空则查看全部应用");
        JComboBox<SlsQueryClient.QueryType> typeBox = new JComboBox<>(SlsQueryClient.QueryType.values());
        JComboBox<Integer> rangeBox = new JComboBox<>(new Integer[]{5, 15, 60, 360, 1440});
        rangeBox.setEditable(true);
        rangeBox.setSelectedItem(Math.max(5, settings.getDefaultMinutes()));

        // 时间范围（仅历史模式显示）
        JPanel timePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        timePanel.setOpaque(false);
        timePanel.add(new JLabel("时间:"));
        timePanel.add(rangeBox);

        JButton queryButton = new JButton("查询", com.intellij.icons.AllIcons.Actions.Execute);
        queryButton.setToolTipText("按上方条件拉取所选时间范围内的历史日志");
        JButton configButton = new JButton("连接配置", com.intellij.icons.AllIcons.General.Settings);
        configButton.setToolTipText("配置当前 IDE 项目的 SLS Endpoint、Project 和 Logstore");

        // 模式状态（AtomicReference 供内部类引用）
        AtomicReference<String> mode = new AtomicReference<>(CARD_HISTORY);

        // 3. 状态栏：状态点 + 状态文本 + 元信息（实时控制按钮移至顶部工具栏，仅实时卡片显示）
        JLabel statusDot = new JLabel("●");
        statusDot.setFont(statusDot.getFont().deriveFont(11f));
        statusDot.setForeground(RUN_GREEN);
        JBLabel statusLabel = new JBLabel(" ");
        JBLabel metaLabel = new JBLabel(" ");
        metaLabel.setForeground(META_GRAY);

        JButton pauseBtn = new JButton("暂停");
        pauseBtn.setToolTipText("停止拉取，保留已输出内容；恢复后从暂停点继续（间隙最多补 5 分钟）");
        JButton resetBtn = new JButton("重置");
        resetBtn.setToolTipText("清空实时内容并从当前时间重新开始");
        JPanel realtimeControls = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        realtimeControls.setOpaque(false);
        realtimeControls.add(pauseBtn);
        realtimeControls.add(resetBtn);
        realtimeControls.setVisible(false);

        JPanel statusBar = new JBPanel<>(new BorderLayout(10, 0));
        statusBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, JBColor.border()),
                new EmptyBorder(4, 10, 4, 10)));
        JPanel statusLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        statusLeft.setOpaque(false);
        statusLeft.add(statusDot);
        statusLeft.add(statusLabel);
        statusBar.add(statusLeft, BorderLayout.WEST);
        statusBar.add(metaLabel, BorderLayout.CENTER);

        // 4. 主面板骨架（工具栏稍后填充，模式切换依赖 controller）
        JPanel panel = new JPanel(new BorderLayout());
        panel.add(center, BorderLayout.CENTER);
        panel.add(statusBar, BorderLayout.SOUTH);

        Content content = ContentFactory.getInstance().createContent(panel, "", false);
        Disposer.register(content, () -> historyRequest.incrementAndGet());

        // 5. 实时控制器（生命周期挂 Content；状态栏仅在实时卡片可见时被实时信息占用）
        RealtimeController controller = new RealtimeController(
                project, content, realtimeConsole, statusDot, statusLabel, metaLabel,
                pauseBtn, appField, typeBox,
                () -> CARD_REALTIME.equals(mode.get()));
        Disposer.register(content, () -> controller.stopIfAny());

        // 6. 模式切换：JToggleButton 原生样式（选中=蓝色边框），默认「历史」选中
        Consumer<Boolean> toHistory = sel -> {
            if (!sel) {
                return;
            }
            mode.set(CARD_HISTORY);
            cards.show(center, CARD_HISTORY);
            timePanel.setVisible(true);
            queryButton.setVisible(true);
            realtimeControls.setVisible(false);
            statusDot.setForeground(RUN_GREEN);
            statusLabel.setText("就绪：选好条件后点「查询」拉取日志，点击蓝色堆栈链接跳转源码");
            metaLabel.setText(" ");
        };
        Consumer<Boolean> toRealtime = sel -> {
            if (!sel) {
                return;
            }
            mode.set(CARD_REALTIME);
            cards.show(center, CARD_REALTIME);
            timePanel.setVisible(false);
            queryButton.setVisible(false);
            realtimeControls.setVisible(true);
            if (!controller.everStarted()) {
                controller.startFresh(); // 首次进入实时：自动从当前时间开始
            } else if (controller.isAlive()) {
                controller.restoreStatus(); // 实时仍在跑：恢复状态栏，内容原样保留
            } else {
                statusDot.setForeground(META_GRAY);
                statusLabel.setText("实时已停止：点「重置」从当前时间重新开始（内容已保留）");
                metaLabel.setText(" ");
            }
        };
        JToggleButton modeQueryBtn = new JToggleButton("历史", true);
        JToggleButton modeRealtimeBtn = new JToggleButton("实时");
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(modeQueryBtn);
        modeGroup.add(modeRealtimeBtn);
        modeQueryBtn.addActionListener(e -> {
            if (modeQueryBtn.isSelected()) {
                toHistory.accept(true);
            }
        });
        modeRealtimeBtn.addActionListener(e -> {
            if (modeRealtimeBtn.isSelected()) {
                if (!ensureConfigured(project)) {
                    modeQueryBtn.setSelected(true);
                    toHistory.accept(true);
                    statusLabel.setText("尚未配置 SLS：请点击「连接配置」");
                    return;
                }
                toRealtime.accept(true);
            }
        });
        // 工具窗口每次显示时给「历史」焦点：进来即呈现原生选中蓝框
        modeQueryBtn.addHierarchyListener(e2 -> {
            if ((e2.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0
                    && modeQueryBtn.isShowing()) {
                SwingUtilities.invokeLater(modeQueryBtn::requestFocusInWindow);
            }
        });

        JPanel toolbar = new JBPanel<>(new FlowLayout(FlowLayout.LEFT, 8, 4));
        toolbar.setBorder(JBUI.Borders.empty(2, 8));
        toolbar.add(modeQueryBtn);
        toolbar.add(modeRealtimeBtn);
        toolbar.add(separator());
        JPanel appPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        appPanel.setOpaque(false);
        appPanel.add(new JLabel("应用:"));
        appPanel.add(appField);
        toolbar.add(appPanel);
        toolbar.add(new JLabel("类型:"));
        toolbar.add(typeBox);
        toolbar.add(timePanel);
        toolbar.add(queryButton);
        toolbar.add(configButton);
        configButton.addActionListener(e -> {
            if (new SlsConfigDialog(project).showAndGet()) {
                historyRequest.incrementAndGet();
                controller.connectionChanged();
                modeQueryBtn.setSelected(true);
                toHistory.accept(true);
                statusLabel.setText("连接配置已保存：点击「查询」或切换到「实时」");
            }
        });
        // 顶部工具栏 = 左侧模式/过滤/查询 + 右侧实时控制（暂停/恢复/重置，仅实时卡片显示）
        JPanel north = new JPanel(new BorderLayout());
        north.setOpaque(false);
        north.add(toolbar, BorderLayout.CENTER);
        north.add(realtimeControls, BorderLayout.EAST);
        panel.add(north, BorderLayout.NORTH);

        // 7. 跳转结果联动（历史卡片时显示）
        NavigationLog.Listener navListener = s -> {
            if (CARD_HISTORY.equals(mode.get())) {
                statusLabel.setText(s);
            }
        };
        NavigationLog.addListener(navListener);
        Disposer.register(project, () -> NavigationLog.removeListener(navListener));

        // 8. 工具窗口隐藏时：停止实时并切回「历史」（下次打开永远是历史默认）
        project.getMessageBus().connect(content)
                .subscribe(com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC,
                        new com.intellij.openapi.wm.ex.ToolWindowManagerListener() {
                            @Override
                            public void stateChanged(@NotNull com.intellij.openapi.wm.ToolWindowManager wm,
                                                     @NotNull com.intellij.openapi.wm.ToolWindow tw,
                                                     @NotNull com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType type) {
                                if (!toolWindow.equals(tw)
                                        || type != com.intellij.openapi.wm.ex.ToolWindowManagerListener.ToolWindowManagerEventType.HideToolWindow) {
                                    return;
                                }
                                // 工具窗口隐藏：停止实时（下次打开永远是历史默认，不留后台轮询）
                                controller.stopIfAny();
                                if (CARD_REALTIME.equals(mode.get())) {
                                    modeQueryBtn.setSelected(true); // 程序式设置不触发 ActionListener，手动同步
                                    toHistory.accept(true);
                                }
                            }
                        });

        toolWindow.getContentManager().addContent(content);

        // 9. 执行按钮
        queryButton.addActionListener((ActionEvent e) ->
                runQuery(project, historyConsole, appField, typeBox, rangeBox, statusLabel, metaLabel, historyRequest));
        pauseBtn.addActionListener((ActionEvent e) -> controller.togglePause());
        resetBtn.addActionListener((ActionEvent e) -> controller.reset());

        // 10. 初始化：无示例数据，仅显示当前（历史）模式的提示
        historyConsole.print("首次使用请点击「连接配置」，填写 Endpoint、Project 和 Logstore。\n"
                + "提示：选择时间范围后点「查询」，拉取该范围内的日志；"
                + "点击蓝色堆栈链接可跳转源码。\n", ConsoleViewContentType.SYSTEM_OUTPUT);
        realtimeConsole.print("提示：切到「实时」后，从当前时间开始持续输出新增日志"
                + "（每 2 秒增量拉取；右上方可暂停 / 恢复 / 重置）。\n",
                ConsoleViewContentType.SYSTEM_OUTPUT);
    }

    // ---------------------------------------------------------------- 实时控制器

    /** 实时模式生命周期封装：启动 / 暂停切换 / 重置 / 停止。 */
    private static final class RealtimeController implements SlsRealtimeService.Listener {
        private final Project project;
        private final Content content;
        private final ConsoleView console;
        private final JLabel statusDot;
        private final JBLabel statusLabel;
        private final JBLabel metaLabel;
        private final JButton pauseBtn;
        private final javax.swing.JTextField appField;
        private final JComboBox<SlsQueryClient.QueryType> typeBox;
        /** 实时卡片是否正被查看（后台运行时状态栏让位给历史查询）。 */
        private final java.util.function.BooleanSupplier realtimeVisible;

        private SlsRealtimeService service;
        private long droppedLines;
        private boolean backlogFlag;
        private boolean everStarted;

        RealtimeController(Project project, Content content, ConsoleView console,
                           JLabel statusDot, JBLabel statusLabel, JBLabel metaLabel,
                           JButton pauseBtn,
                           javax.swing.JTextField appField, JComboBox<SlsQueryClient.QueryType> typeBox,
                           java.util.function.BooleanSupplier realtimeVisible) {
            this.project = project;
            this.content = content;
            this.console = console;
            this.statusDot = statusDot;
            this.statusLabel = statusLabel;
            this.metaLabel = metaLabel;
            this.pauseBtn = pauseBtn;
            this.appField = appField;
            this.typeBox = typeBox;
            this.realtimeVisible = realtimeVisible;
        }

        boolean everStarted() {
            return everStarted;
        }

        boolean isAlive() {
            return service != null
                    && service.currentState() != SlsRealtimeService.State.STOPPED;
        }

        /** 从运行/暂停态切回实时卡片时恢复状态栏显示（内容不动）。 */
        void restoreStatus() {
            if (service == null) {
                return;
            }
            onState(service.currentState(), service.currentState() == SlsRealtimeService.State.PAUSED
                    ? "已暂停（内容保留，可恢复）" : "实时输出中");
            refreshMeta(SlsSettings.getInstance(project));
        }

        /** 开启一路全新实时（首次进入 / 重置共用）。 */
        void startFresh() {
            stopIfAny();
            everStarted = true;
            SlsSettings settings = SlsSettings.getInstance(project);
            String app = appField.getText() == null ? "" : appField.getText().trim();
            SlsQueryClient.QueryType type = (SlsQueryClient.QueryType) typeBox.getSelectedItem();
            String query = (type == null ? SlsQueryClient.QueryType.ALL : type).buildQuery(app);
            String appLabel = app.isEmpty() ? "全部应用" : app;

            console.clear();
            droppedLines = 0;
            backlogFlag = false;
            long now = Instant.now().getEpochSecond();
            console.print(String.format(
                    "=== 实时开始 %s · 应用=%s · 类型=%s · 每 %ds 增量拉取 ===%n",
                    HHMMSS.format(Instant.ofEpochSecond(now)), appLabel,
                    type == null ? "全部" : type.getLabel(),
                    settings.getPollIntervalSeconds()),
                    ConsoleViewContentType.SYSTEM_OUTPUT);

            service = new SlsRealtimeService(content, this,
                    settings.getEndpoint(), settings.getProject(), settings.getLogStore(),
                    query, settings.getPollIntervalSeconds(), settings.getMaxBatchLines(), now);
            service.start();
            pauseBtn.setText("暂停");
            onState(SlsRealtimeService.State.RUNNING, "实时输出中");
        }

        /** 暂停 / 恢复切换。 */
        void togglePause() {
            if (service == null) {
                return;
            }
            if (service.currentState() == SlsRealtimeService.State.RUNNING) {
                service.pause();
            } else if (service.currentState() == SlsRealtimeService.State.PAUSED) {
                service.resume();
            }
        }

        /** 重置：清空并从当前时间重新开始。 */
        void reset() {
            startFresh();
        }

        void connectionChanged() {
            stopIfAny();
            everStarted = false;
            console.clear();
        }

        void stopIfAny() {
            if (service != null) {
                Disposer.dispose(service);
                service = null;
            }
        }

        // -------------------------------------------------- SlsRealtimeService.Listener（EDT）

        @Override
        public void onBatch(List<SlsQueryClient.LogEntry> fresh, long cursorSecond, boolean backlog) {
            if (backlog) {
                backlogFlag = true;
            }
            SlsSettings settings = SlsSettings.getInstance(project);
            for (SlsQueryClient.LogEntry e : fresh) {
                console.print(e.content + "\n", levelType(e.level));
            }
            droppedLines += SlsConsoleBuffer.trim(console, settings.getMaxBufferLines());
            if (realtimeVisible.getAsBoolean()) {
                refreshMeta(settings); // 后台运行时不抢状态栏
            }
        }

        @Override
        public void onState(SlsRealtimeService.State state, String summary) {
            if (!realtimeVisible.getAsBoolean()) {
                return; // 后台运行时不抢状态栏（切回实时时 restoreStatus 恢复）
            }
            switch (state) {
                case RUNNING -> statusDot.setForeground(RUN_GREEN);
                case PAUSED -> statusDot.setForeground(PAUSE_YELLOW);
                case STOPPED -> statusDot.setForeground(META_GRAY);
            }
            statusLabel.setText(summary);
            if (state == SlsRealtimeService.State.PAUSED) {
                pauseBtn.setText("恢复");
            } else if (state == SlsRealtimeService.State.RUNNING) {
                pauseBtn.setText("暂停");
            }
        }

        @Override
        public void onGapDropped(long droppedSeconds) {
            console.print(String.format("── 暂停期间间隙 %d 分钟，已按上限只补拉最近 5 分钟 ──%n",
                    droppedSeconds / 60), ConsoleViewContentType.SYSTEM_OUTPUT);
        }

        private void refreshMeta(SlsSettings settings) {
            String refresh = service == null ? "-" : HHMMSS.format(
                    Instant.ofEpochSecond(Math.max(service.lastRefreshSecond(), 0)));
            String meta = String.format("上次刷新 %s · 缓冲 %s/%s 行 · 已丢弃 %d 行%s",
                    refresh, SlsConsoleBuffer.currentLines(console),
                    settings.getMaxBufferLines(), droppedLines,
                    backlogFlag ? " · 积压截断(只保留最新)" : "");
            metaLabel.setText(meta);
        }
    }

    // ---------------------------------------------------------------- 工具栏辅助

    private static JComponent separator() {
        JComponent sep = new JComponent() {
            @Override protected void paintComponent(java.awt.Graphics g) {
                g.setColor(JBColor.border());
                g.fillRect(getWidth() / 2, 0, 1, getHeight());
            }
        };
        sep.setPreferredSize(new Dimension(3, 20));
        return sep;
    }

    // ---------------------------------------------------------------- 历史查询

    private static boolean ensureConfigured(Project project) {
        return SlsSettings.getInstance(project).isConfigured()
                || new SlsConfigDialog(project).showAndGet();
    }

    private static void runQuery(Project project, ConsoleView console,
                                 javax.swing.JTextField appField,
                                 JComboBox<SlsQueryClient.QueryType> typeBox,
                                 JComboBox<Integer> rangeBox,
                                 JBLabel statusLabel, JBLabel metaLabel, AtomicLong historyRequest) {
        SlsSettings settings = SlsSettings.getInstance(project);
        if (!ensureConfigured(project)) {
            statusLabel.setText("尚未配置 SLS：请点击「连接配置」");
            return;
        }
        long requestId = historyRequest.incrementAndGet();
        SlsQueryClient.QueryType type =
                (SlsQueryClient.QueryType) typeBox.getSelectedItem();
        // 时间范围可编辑：兼容手输任意分钟数，解析失败回退 15
        int minutesRaw;
        try {
            minutesRaw = Integer.parseInt(String.valueOf(rangeBox.getSelectedItem()).trim());
        } catch (NumberFormatException e) {
            minutesRaw = 15;
        }
        final int minutes = minutesRaw <= 0 ? 15 : minutesRaw;
        String endpoint = settings.getEndpoint();
        String project_ = settings.getProject();
        String logStore = settings.getLogStore();
        String app = appField.getText() == null ? "" : appField.getText().trim();
        String query = type == null ? "*" : type.buildQuery(app);
        String appLabel = app.isEmpty() ? "全部应用" : app;
        // 拉取上限：至少 10000，随显示缓冲上限放大（单次查询翻页上限，秒级返回）
        int maxLines = Math.max(settings.getMaxBufferLines(), 10000);

        long to = Instant.now().getEpochSecond();
        long from = to - minutes * 60L;

        statusLabel.setText("查询中…");

        console.print(String.format(
                "\n=== 查询 [%s] 应用=%s project=%s logstore=%s 最近%d分钟 ===%n",
                query, appLabel, project_, logStore, minutes),
                ConsoleViewContentType.SYSTEM_OUTPUT);

        new Task.Backgroundable(project, "查询 SLS 日志", true) {
            private List<SlsQueryClient.LogEntry> entries;
            private LogException failure;
            private IllegalStateException credFailure;
            private Exception netFailure;
            private ProgressIndicator ind;

            @Override
            public void run(@NotNull ProgressIndicator indicator) {
                ind = indicator;
                try {
                    entries = SlsQueryClient.query(endpoint, project_, logStore,
                            from, to, query, maxLines,
                            () -> indicator.isCanceled() || historyRequest.get() != requestId);
                } catch (LogException e) {
                    failure = e;
                } catch (IllegalStateException e) {
                    credFailure = e;
                } catch (Exception e) {
                    netFailure = e; // 网络/IO 异常也要可见，不能静默无输出
                }
            }

            @Override
            public void onSuccess() {
                if (historyRequest.get() != requestId || project.isDisposed()) {
                    return;
                }
                boolean partial = ind != null && ind.isCanceled()
                        && entries != null && !entries.isEmpty();
                if (credFailure != null) {
                    statusLabel.setText("凭证解析失败");
                    console.print("凭证解析失败: " + credFailure.getMessage() + "\n",
                            ConsoleViewContentType.ERROR_OUTPUT);
                    return;
                }
                if (netFailure != null) {
                    statusLabel.setText("查询异常: " + netFailure.getClass().getSimpleName());
                    console.print("查询异常: " + netFailure.getClass().getSimpleName()
                            + (netFailure.getMessage() == null ? "" : " - " + netFailure.getMessage())
                            + "\n请检查网络/代理后重试。\n",
                            ConsoleViewContentType.ERROR_OUTPUT);
                    return;
                }
                if (failure != null) {
                    String code = failure.GetErrorCode();
                    statusLabel.setText("查询失败: " + code);
                    console.print("SLS 查询失败: " + code + " - "
                            + SlsQueryClient.maskSecret(failure.GetErrorMessage()) + "\n",
                            ConsoleViewContentType.ERROR_OUTPUT);
                    if (SlsQueryClient.isCredentialExpired(failure)) {
                        console.print("怀疑 STS 凭证过期，请更新本机阿里云 CLI 配置或 IDE 环境变量后再次查询。\n",
                                ConsoleViewContentType.ERROR_OUTPUT);
                    }
                    return;
                }
                console.clear();
                statusLabel.setText("查询完成");
                console.print(String.format("拉取到 %d 条%s（%s，应用=%s，最近%d分钟）:%n",
                        entries == null ? 0 : entries.size(),
                        partial ? "（已取消，显示部分结果）" : "",
                        type.getLabel(), appLabel, minutes),
                        ConsoleViewContentType.SYSTEM_OUTPUT);
                if (entries == null || entries.isEmpty()) {
                    console.print("该时间范围内无匹配日志。\n", ConsoleViewContentType.SYSTEM_OUTPUT);
                    return;
                }
                for (SlsQueryClient.LogEntry e : entries) {
                    console.print(e.content + "\n", levelType(e.level));
                }
                // 环形缓冲：查询模式同样受上限保护
                int dropped = SlsConsoleBuffer.trim(console, settings.getMaxBufferLines());
                metaLabel.setText(String.format("共 %d 条 · 最近 %d 分钟 · 缓冲 %d/%d 行%s",
                        entries.size(), minutes, SlsConsoleBuffer.currentLines(console),
                        settings.getMaxBufferLines(),
                        dropped > 0 ? " · 已裁剪头部 " + dropped + " 行" : ""));
            }
        }.queue();
    }

    private static ConsoleViewContentType levelType(String level) {
        switch (level == null ? "" : level) {
            case "ERROR":
                return ConsoleViewContentType.ERROR_OUTPUT;
            case "WARN":
                return ConsoleViewContentType.LOG_WARNING_OUTPUT;
            default:
                return ConsoleViewContentType.NORMAL_OUTPUT;
        }
    }
}
