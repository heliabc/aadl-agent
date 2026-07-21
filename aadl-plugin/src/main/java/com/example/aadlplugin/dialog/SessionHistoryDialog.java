package com.example.aadlplugin.dialog;

import com.example.aadlplugin.Activator;
import com.example.aadlplugin.session.ChatMessage;
import com.example.aadlplugin.session.SessionMetadata;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.util.List;

public class SessionHistoryDialog extends Dialog {

    private String sessionId;
    private Text historyText;

    public SessionHistoryDialog(Shell parentShell, String sessionId) {
        super(parentShell);
        this.sessionId = sessionId;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 10;
        layout.marginWidth = 10;
        container.setLayout(layout);

        SessionMetadata metadata = Activator.getDefault().getSessionManager().getSessionMetadata(sessionId);
        if (metadata != null) {
            Label titleLabel = new Label(container, SWT.NONE);
            titleLabel.setText("会话详情: " + metadata.getName());
            GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
            gd.horizontalSpan = 2;
            titleLabel.setLayoutData(gd);

            Label infoLabel = new Label(container, SWT.NONE);
            infoLabel.setText(String.format("会话ID: %s | 创建时间: %s | 模型: %s",
                    metadata.getSessionId(),
                    metadata.getCreatedAtFormatted(),
                    metadata.getModelType() != null ? metadata.getModelType() : "未知"));
            gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
            gd.horizontalSpan = 2;
            infoLabel.setLayoutData(gd);
        }

        Label historyLabel = new Label(container, SWT.NONE);
        historyLabel.setText("对话历史:");
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        historyLabel.setLayoutData(gd);

        historyText = new Text(container, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 600;
        gd.heightHint = 400;
        historyText.setLayoutData(gd);

        loadHistory();

        return container;
    }

    private void loadHistory() {
        List<ChatMessage> messages = Activator.getDefault().getSessionManager().getMessages(sessionId);
        if (messages.isEmpty()) {
            historyText.setText("该会话暂无对话历史");
            return;
        }

        StringBuilder history = new StringBuilder();
        int index = 1;
        for (ChatMessage msg : messages) {
            String roleLabel;
            switch (msg.getRole()) {
                case "user":
                    roleLabel = "[用户]";
                    break;
                case "assistant":
                    roleLabel = "[助手]";
                    break;
                case "system":
                    roleLabel = "[系统]";
                    break;
                default:
                    roleLabel = "[" + msg.getRole() + "]";
                    break;
            }
            history.append(String.format("\n--- 消息 %d ---\n", index++));
            history.append(roleLabel).append("\n");
            history.append(msg.getContent()).append("\n");
        }
        historyText.setText(history.toString());
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("会话详情");
        newShell.setSize(700, 550);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "关闭", true);
    }
}
