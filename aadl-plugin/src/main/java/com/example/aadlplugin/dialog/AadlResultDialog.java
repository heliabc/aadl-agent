package com.example.aadlplugin.dialog;

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

public class AadlResultDialog extends Dialog {

    private String aadlContent;
    private String outputFile;

    public AadlResultDialog(Shell parentShell, String aadlContent, String outputFile) {
        super(parentShell);
        this.aadlContent = aadlContent;
        this.outputFile = outputFile;
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayout layout = new GridLayout(1, false);
        layout.marginHeight = 10;
        layout.marginWidth = 10;
        container.setLayout(layout);

        Label titleLabel = new Label(container, SWT.NONE);
        titleLabel.setText("AADL模型生成成功");
        GridData gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        gd.horizontalSpan = 2;
        titleLabel.setLayoutData(gd);

        if (outputFile != null && !outputFile.isEmpty()) {
            Label fileLabel = new Label(container, SWT.NONE);
            fileLabel.setText("输出文件: " + outputFile);
            gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
            gd.horizontalSpan = 2;
            fileLabel.setLayoutData(gd);
        }

        Label contentLabel = new Label(container, SWT.NONE);
        contentLabel.setText("生成的AADL内容:");
        gd = new GridData(SWT.LEFT, SWT.CENTER, false, false);
        contentLabel.setLayoutData(gd);

        Text contentText = new Text(container, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        contentText.setFont(new org.eclipse.swt.graphics.Font(container.getDisplay(), "Courier New", 10, SWT.NORMAL));
        gd = new GridData(SWT.FILL, SWT.FILL, true, true);
        gd.widthHint = 800;
        gd.heightHint = 500;
        contentText.setLayoutData(gd);
        contentText.setText(aadlContent != null ? aadlContent : "");

        return container;
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("AADL模型生成结果");
        newShell.setSize(850, 600);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "关闭", true);
    }
}
