package dev.slslogjump;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.ValidationInfo;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;

/** 只配置 SLS 目标位置；查询、日志解析和凭证读取沿用默认规则。 */
public final class SlsConfigDialog extends DialogWrapper {
    private final SlsSettings settings;
    private final JBTextField endpointField = new JBTextField(36);
    private final JBTextField projectField = new JBTextField(36);
    private final JBTextField logStoreField = new JBTextField(36);

    public SlsConfigDialog(Project project) {
        super(project);
        settings = SlsSettings.getInstance(project);
        setTitle("aliyun-SLS-Log-jump - 连接配置");
        setOKButtonText("保存");
        setCancelButtonText("取消");
        endpointField.setText(settings.getEndpoint());
        projectField.setText(settings.getProject());
        logStoreField.setText(settings.getLogStore());
        endpointField.getEmptyText().setText("例如：cn-hangzhou.log.aliyuncs.com");
        projectField.getEmptyText().setText("例如：my-app-logs");
        logStoreField.getEmptyText().setText("例如：application-logs");
        endpointField.setToolTipText("填写目标 Project 所在地域的 SLS Endpoint，不是控制台网页地址");
        projectField.setToolTipText("填写阿里云 SLS 控制台 Project 列表中的名称，不是 IDEA 项目名称");
        logStoreField.setToolTipText("进入上述 Project 后，填写日志库列表中要查询的 Logstore 名称");
        init();
    }

    @Nullable
    @Override
    protected JComponent createCenterPanel() {
        JPanel fields = new JPanel(new GridBagLayout());
        addField(fields, 0, "Endpoint（服务地址）:", endpointField,
                "<html>填写 Project 所在地域的 SLS 服务地址，不是控制台网页地址。<br>"
                        + "可在 SLS 控制台查看对应地域的 Endpoint；本机需能访问该地址。</html>");
        addField(fields, 2, "Project（日志项目）:", projectField,
                "<html>填写 SLS 控制台 Project 列表中的项目名称。<br>"
                        + "这是阿里云日志项目名称，不是 IDEA 项目名称。</html>");
        addField(fields, 4, "Logstore（日志库）:", logStoreField,
                "<html>进入上面填写的 Project，在「日志存储 → 日志库」中查看名称。<br>"
                        + "填写需要查询的日志库名称，不是日志文件名或文件路径。</html>");

        JTextArea hint = new JTextArea(
                "上面的示例仅说明格式，请替换成自己的 SLS 信息。配置按当前 IDE 项目保存。\n"
                        + "凭证自动从 IDE 环境变量或 ~/.aliyun/config.json 的当前配置读取，"
                        + "不在这里填写，也不会保存到插件配置。\n"
                        + "日志解析和过滤规则使用内置默认值，无需额外设置。");
        hint.setEditable(false);
        hint.setLineWrap(true);
        hint.setWrapStyleWord(true);
        hint.setBackground(null);
        hint.setBorder(null);
        hint.setFont(UIUtil.getLabelFont());
        hint.setFocusable(false);
        hint.setColumns(48);
        hint.setRows(4);

        JPanel root = new JPanel(new BorderLayout(0, 12));
        root.add(fields, BorderLayout.NORTH);
        root.add(hint, BorderLayout.CENTER);
        return root;
    }

    private static void addField(JPanel panel, int row, String title, JBTextField field, String help) {
        JLabel label = new JLabel(title);
        label.setLabelFor(field);
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = row;
        c.anchor = GridBagConstraints.NORTHWEST;
        c.insets = new Insets(4, 0, 0, 12);
        panel.add(label, c);

        c.gridx = 1;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(0, 0, 0, 0);
        panel.add(field, c);

        JLabel explanation = new JLabel(help);
        explanation.setForeground(UIUtil.getContextHelpForeground());
        c.gridy = row + 1;
        c.insets = new Insets(4, 0, 14, 0);
        panel.add(explanation, c);
    }

    @Nullable
    @Override
    protected ValidationInfo doValidate() {
        if (endpointField.getText().isBlank()) {
            return new ValidationInfo("请填写 SLS Endpoint", endpointField);
        }
        if (projectField.getText().isBlank()) {
            return new ValidationInfo("请填写 Project 名称", projectField);
        }
        if (logStoreField.getText().isBlank()) {
            return new ValidationInfo("请填写 Logstore 名称", logStoreField);
        }
        return null;
    }

    @Override
    public JComponent getPreferredFocusedComponent() {
        return endpointField;
    }

    @Override
    protected void doOKAction() {
        if (doValidate() != null) {
            return;
        }
        settings.updateTarget(endpointField.getText(), projectField.getText(), logStoreField.getText());
        super.doOKAction();
    }
}
