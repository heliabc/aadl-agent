package com.example.aadlplugin.action;

import com.example.aadlplugin.Activator;
import com.example.aadlplugin.agent.aadl.AadlErrorParserAgent;
import com.example.aadlplugin.agent.aadl.AadlFixerAgent;
import com.example.aadlplugin.agent.AgentInput;
import com.example.aadlplugin.agent.AgentOutput;
import com.example.aadlplugin.client.ModelType;
import com.example.aadlplugin.dialog.FixAadlInputDialog;
import org.eclipse.core.resources.IFile;
import org.eclipse.jface.action.IAction;
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
            
            FixAadlInputDialog dialog = new FixAadlInputDialog(shell);

            if (dialog.open() != org.eclipse.jface.window.Window.OK) {
                return;
            }

            String rawErrors = dialog.getErrorList();

            if (rawErrors == null || rawErrors.trim().isEmpty()) {
                MessageDialog.openError(shell, "Error", "错误信息不能为空");
                return;
            }

            // 第一步：解析错误
            AadlErrorParserAgent parserAgent = Activator.getDefault().getAadlErrorParserAgent();
            AgentInput parserInput = AgentInput.builder()
                    .content(aadlContent)
                    .metadata(rawErrors)
                    .modelType(ModelType.OLLAMA)
                    .build();

            MessageDialog.openInformation(shell, "Info", "正在解析错误信息...");
            
            AgentOutput parserOutput = parserAgent.execute(parserInput);

            if (!parserOutput.isSuccess()) {
                MessageDialog.openError(shell, "Error", "解析错误信息失败:\n" + parserOutput.getErrorMessage());
                return;
            }

            String parsedErrors = parserOutput.getContent();
            MessageDialog.openInformation(shell, "Info", "错误解析完成！\n解析出的错误信息:\n" + parsedErrors);

            // 第二步：修复AADL
            AadlFixerAgent fixerAgent = Activator.getDefault().getAadlFixerAgent();
            AgentInput fixerInput = AgentInput.builder()
                    .content(aadlContent)
                    .metadata(parsedErrors)
                    .modelType(ModelType.OLLAMA)
                    .build();

            MessageDialog.openInformation(shell, "Info", "正在修复AADL文件...");

            AgentOutput fixerOutput = fixerAgent.execute(fixerInput);

            if (!fixerOutput.isSuccess()) {
                MessageDialog.openError(shell, "Error", "修复AADL文件失败:\n" + fixerOutput.getErrorMessage());
                return;
            }

            String fixedContent = fixerOutput.getContent();

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
