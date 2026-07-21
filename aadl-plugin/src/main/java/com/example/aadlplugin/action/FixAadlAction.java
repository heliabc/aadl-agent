package com.example.aadlplugin.action;

import com.example.aadlplugin.Activator;
import com.example.aadlplugin.agent.aadl.AadlFixerAgent;
import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.client.ModelType;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.InputDialog;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class FixAadlAction implements IObjectActionDelegate {

    private Shell shell;
    private IFile selectedFile;

    @Override
    public void setActivePart(IAction action, IWorkbenchPart targetPart) {
        shell = targetPart.getSite().getShell();
    }

    @Override
    public void run(IAction action) {
        if (selectedFile == null) {
            MessageDialog.openError(shell, "Error", "请选择一个AADL文件");
            return;
        }

        try {
            String aadlContent = new String(selectedFile.getContents().readAllBytes(), StandardCharsets.UTF_8);
            
            InputDialog dialog = new InputDialog(
                    shell,
                    "Fix AADL Errors",
                    "请输入AADL文件的错误列表（每行一个错误）:",
                    "",
                    null);

            if (dialog.open() != org.eclipse.jface.window.Window.OK) {
                return;
            }

            String errors = dialog.getValue();

            AadlFixerAgent agent = Activator.getDefault().getAadlFixerAgent();
            AgentInput input = AgentInput.builder()
                    .content(aadlContent)
                    .metadata(errors)
                    .modelType(ModelType.OLLAMA)
                    .build();

            AgentOutput output = agent.execute(input);

            if (!output.isSuccess()) {
                MessageDialog.openError(shell, "Error", output.getErrorMessage());
                return;
            }

            String fixedContent = output.getContent();

            InputStream is = new java.io.ByteArrayInputStream(fixedContent.getBytes(StandardCharsets.UTF_8));
            selectedFile.setContents(is, true, false, null);

            MessageDialog.openInformation(shell, "Success", "AADL文件已修复！");

        } catch (Exception e) {
            MessageDialog.openError(shell, "Error", "修复AADL文件失败:\n" + e.getMessage());
        }
    }

    @Override
    public void selectionChanged(IAction action, ISelection selection) {
        if (selection instanceof IStructuredSelection) {
            Object element = ((IStructuredSelection) selection).getFirstElement();
            if (element instanceof IFile) {
                IFile file = (IFile) element;
                if (file.getName().endsWith(".aadl")) {
                    selectedFile = file;
                }
            }
        }
    }
}