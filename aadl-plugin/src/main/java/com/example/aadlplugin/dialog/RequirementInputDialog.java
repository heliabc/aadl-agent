package com.example.aadlplugin.dialog;

import com.example.aadlplugin.Activator;
import com.example.aadlplugin.session.SessionMetadata;
import com.example.aadlplugin.util.DocFileReader;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.util.List;

public class RequirementInputDialog extends Dialog {

    private Text requirementText;
    private Button ollamaButton;
    private Button deepseekButton;
    private Button selectFileButton;
    private Button newSessionButton;
    private Button incrementalButton;
    private Button viewHistoryButton;
    private Combo sessionCombo;
    private Text sessionInfoText;
    private String requirement;
    private String model = "OLLAMA";
    private String sessionId;
    private boolean isIncremental = false;

    public RequirementInputDialog(Shell parentShell) {
        super(parentShell);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayout layout = new GridLayout(2, false);
        layout.marginHeight = 10;
        layout.marginWidth = 10;
        container.setLayout(layout);

        Label modeLabel = new Label(container, SWT.NONE);
        modeLabel.setText("运行模式:");
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.horizontalSpan = 2;
        modeLabel.setLayoutData(gd);

        newSessionButton = new Button(container, SWT.RADIO);
        newSessionButton.setText("新会话（上传完整需求文档）");
        newSessionButton.setSelection(true);
        gd = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        gd.horizontalSpan = 2;
        newSessionButton.setLayoutData(gd);

        incrementalButton = new Button(container, SWT.RADIO);
        incrementalButton.setText("增量更新（基于已有会话修改）");
        gd = new GridData(SWT.LEFT, SWT.CENTER, true, false);
        gd.horizontalSpan = 2;
        incrementalButton.setLayoutData(gd);

        Label sessionLabel = new Label(container, SWT.NONE);
        sessionLabel.setText("选择会话:");
        gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        sessionLabel.setLayoutData(gd);

        sessionCombo = new Combo(container, SWT.DROP_DOWN | SWT.READ_ONLY);
        gd = new GridData(SWT.FILL, SWT.CENTER, true, false);
        sessionCombo.setLayoutData(gd);
        loadSessionList();

        viewHistoryButton = new Button(container, SWT.PUSH);
        viewHistoryButton.setText("查看会话详情");
        gd = new GridData(SWT.RIGHT, SWT.CENTER, false, false);
        gd.horizontalSpan = 2;
        viewHistoryButton.setLayoutData(gd);
        viewHistoryButton.setEnabled(false);
        viewHistoryButton.addListener(SWT.Selection, e -> viewSessionHistory());

        sessionInfoText = new Text(container, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.READ_ONLY);
        gd = new GridData(SWT.FILL, SWT.FILL, true, false);
        gd.widthHint = 500;
        gd.heightHint = 80;
        gd.horizontalSpan = 2;
        sessionInfoText.setLayoutData(gd);

        Label modelLabel = new Label(container, SWT.NONE);
        modelLabel.setText("选择模型:");
        gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.horizontalSpan = 2;
        modelLabel.setLayoutData(gd);

        ollamaButton = new Button(container, SWT.RADIO);
        ollamaButton.setText("Ollama (本地)");
        ollamaButton.setSelection(true);
        ollamaButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        deepseekButton = new Button(container, SWT.RADIO);
        deepseekButton.setText("DeepSeek");
        deepseekButton.setLayoutData(new GridData(SWT.LEFT, SWT.CENTER, false, false));

        Label requirementLabel = new Label(container, SWT.NONE);
        requirementLabel.setText("需求描述 / 修改内容:");
        gd = new GridData(SWT.LEFT, SWT.TOP, false, false);
        gd.horizontalSpan = 2;
        requirementLabel.setLayoutData(gd);

        requirementText = new Text(container, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 500;
        gd.heightHint = 250;
        gd.horizontalSpan = 2;
        requirementText.setLayoutData(gd);

        selectFileButton = new Button(container, SWT.PUSH);
        selectFileButton.setText("从文件加载需求");
        selectFileButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        selectFileButton.addListener(SWT.Selection, e -> selectFile());

        Label hintLabel = new Label(container, SWT.NONE);
        hintLabel.setText("提示：新会话模式下输入完整需求；增量更新模式下输入修改内容");
        gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.horizontalSpan = 2;
        hintLabel.setLayoutData(gd);

        newSessionButton.addListener(SWT.Selection, e -> updateModeUI());
        incrementalButton.addListener(SWT.Selection, e -> updateModeUI());
        sessionCombo.addListener(SWT.Selection, e -> updateSessionInfo());

        return container;
    }

    private void loadSessionList() {
        sessionCombo.removeAll();
        List<SessionMetadata> sessions = Activator.getDefault().getSessionManager().getAllSessionMetadata();
        if (!sessions.isEmpty()) {
            for (SessionMetadata metadata : sessions) {
                String displayText = String.format("%s - %s (%s, %d条需求)",
                        metadata.getName(),
                        metadata.getCreatedAtFormatted(),
                        metadata.getModelType() != null ? metadata.getModelType() : "未知",
                        metadata.getRequirementCount());
                sessionCombo.add(displayText, metadata.getSessionId().hashCode());
            }
            sessionCombo.select(0);
            updateSessionInfo();
        }
    }

    private void updateSessionInfo() {
        sessionInfoText.setText("");
        if (!sessionCombo.getEnabled()) {
            return;
        }
        int index = sessionCombo.getSelectionIndex();
        if (index < 0) {
            return;
        }
        List<SessionMetadata> sessions = Activator.getDefault().getSessionManager().getAllSessionMetadata();
        if (index < sessions.size()) {
            SessionMetadata metadata = sessions.get(index);
            StringBuilder info = new StringBuilder();
            info.append("会话名称: ").append(metadata.getName()).append("\n");
            info.append("会话ID: ").append(metadata.getSessionId()).append("\n");
            info.append("创建时间: ").append(metadata.getCreatedAtFormatted()).append("\n");
            info.append("最后修改: ").append(metadata.getLastModifiedFormatted()).append("\n");
            info.append("模型类型: ").append(metadata.getModelType() != null ? metadata.getModelType() : "未知").append("\n");
            info.append("需求数量: ").append(metadata.getRequirementCount()).append("\n");
            info.append("AADL生成: ").append(metadata.isHasAadlGenerated() ? "是" : "否").append("\n");
            if (metadata.getRequirementSummary() != null && !metadata.getRequirementSummary().isEmpty()) {
                info.append("\n需求摘要: ").append(metadata.getRequirementSummary());
            }
            sessionInfoText.setText(info.toString());
            sessionId = metadata.getSessionId();
        }
    }

    private void updateModeUI() {
        boolean incremental = incrementalButton.getSelection();
        sessionCombo.setEnabled(incremental);
        viewHistoryButton.setEnabled(incremental && sessionCombo.getItemCount() > 0);
        selectFileButton.setEnabled(!incremental);
        sessionInfoText.setEnabled(incremental);
        if (incremental) {
            requirementText.setMessage("请输入修改内容...");
            updateSessionInfo();
        } else {
            requirementText.setMessage("请输入完整需求描述...");
            sessionInfoText.setText("");
            sessionId = null;
        }
    }

    private void selectFile() {
        FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
        dialog.setText("选择需求文件");
        dialog.setFilterExtensions(new String[]{"*.txt", "*.docx", "*.doc", "*.*"});
        dialog.setFilterNames(new String[]{"文本文件", "Word文档", "旧版Word文档", "所有文件"});

        String filePath = dialog.open();
        if (filePath != null) {
            try {
                DocFileReader reader = new DocFileReader();
                String content = reader.readFile(filePath);
                requirementText.setText(content);
            } catch (Exception e) {
                org.eclipse.jface.dialogs.MessageDialog.openError(getShell(), "文件读取失败", "无法读取文件:\n" + e.getMessage());
            }
        }
    }

    private void viewSessionHistory() {
        int index = sessionCombo.getSelectionIndex();
        if (index < 0) {
            return;
        }
        List<SessionMetadata> sessions = Activator.getDefault().getSessionManager().getAllSessionMetadata();
        if (index < sessions.size()) {
            SessionMetadata metadata = sessions.get(index);
            SessionHistoryDialog historyDialog = new SessionHistoryDialog(getShell(), metadata.getSessionId());
            historyDialog.open();
        }
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("AADL Generator - 需求输入");
        newShell.setSize(600, 650);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "生成 AADL", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "取消", false);
    }

    @Override
    protected void okPressed() {
        requirement = requirementText.getText();
        if (ollamaButton.getSelection()) {
            model = "OLLAMA";
        } else if (deepseekButton.getSelection()) {
            model = "DEEPSEEK";
        }
        isIncremental = incrementalButton.getSelection();
        if (!isIncremental) {
            sessionId = null;
        }
        super.okPressed();
    }

    public String getRequirement() {
        return requirement;
    }

    public String getModel() {
        return model;
    }

    public String getSessionId() {
        return sessionId;
    }

    public boolean isIncremental() {
        return isIncremental;
    }
}
