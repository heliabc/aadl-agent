package com.example.aadlplugin.dialog;

import com.example.aadlplugin.Activator;
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

import java.util.Set;

public class RequirementInputDialog extends Dialog {

    private Text requirementText;
    private Button ollamaButton;
    private Button deepseekButton;
    private Button selectFileButton;
    private Button newSessionButton;
    private Button incrementalButton;
    private Combo sessionCombo;
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
        gd.heightHint = 300;
        gd.horizontalSpan = 2;
        requirementText.setLayoutData(gd);

        selectFileButton = new Button(container, SWT.PUSH);
        selectFileButton.setText("从文件加载需求");
        selectFileButton.setLayoutData(new GridData(SWT.RIGHT, SWT.CENTER, false, false));
        selectFileButton.addListener(SWT.Selection, e -> selectFile());

        Label hintLabel = new Label(container, SWT.NONE);
        hintLabel.setText("提示：新会话模式下输入完整需求；增量更新模式下输入修改内容（如\"将任务调度优先级从高改为中\"）");
        gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.horizontalSpan = 2;
        hintLabel.setLayoutData(gd);

        newSessionButton.addListener(SWT.Selection, e -> updateModeUI());
        incrementalButton.addListener(SWT.Selection, e -> updateModeUI());

        return container;
    }

    private void loadSessionList() {
        sessionCombo.removeAll();
        Set<String> sessions = Activator.getDefault().getTraceabilityService().getAllSessionIds();
        if (!sessions.isEmpty()) {
            sessionCombo.setItems(sessions.toArray(new String[0]));
            sessionCombo.select(0);
        }
    }

    private void updateModeUI() {
        boolean incremental = incrementalButton.getSelection();
        sessionCombo.setEnabled(incremental);
        selectFileButton.setEnabled(!incremental);
        if (incremental) {
            requirementText.setMessage("请输入修改内容...");
        } else {
            requirementText.setMessage("请输入完整需求描述...");
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

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("AADL Generator - 需求输入");
        newShell.setSize(600, 500);
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
        if (isIncremental) {
            sessionId = sessionCombo.getText();
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