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

public class FixAadlInputDialog extends Dialog {

    private String errorList;
    private Text errorText;

    public FixAadlInputDialog(Shell parentShell) {
        super(parentShell);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite container = (Composite) super.createDialogArea(parent);
        GridLayout layout = new GridLayout(1, false);
        layout.marginWidth = 15;
        layout.marginHeight = 15;
        layout.verticalSpacing = 10;
        container.setLayout(layout);

        Label label = new Label(container, SWT.NONE);
        label.setText("请粘贴OSATE的错误信息:");

        errorText = new Text(container, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL);
        GridData data = new GridData(GridData.FILL_BOTH);
        data.widthHint = 600;
        data.heightHint = 300;
        errorText.setLayoutData(data);
        errorText.setFont(new org.eclipse.swt.graphics.Font(parent.getDisplay(), "Courier New", 10, SWT.NORMAL));
        errorText.setForeground(new org.eclipse.swt.graphics.Color(parent.getDisplay(), 0, 0, 0));
        errorText.setBackground(new org.eclipse.swt.graphics.Color(parent.getDisplay(), 255, 255, 255));

        if (errorList != null && !errorList.isEmpty()) {
            errorText.setText(errorList);
        }

        return container;
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "确定", true);
        createButton(parent, IDialogConstants.CANCEL_ID, "取消", false);
    }

    @Override
    protected void configureShell(Shell newShell) {
        super.configureShell(newShell);
        newShell.setText("Fix AADL Errors");
        newShell.setSize(650, 450);
    }

    public String getErrorList() {
        return errorList;
    }

    @Override
    protected void okPressed() {
        errorList = errorText.getText();
        super.okPressed();
    }
}
