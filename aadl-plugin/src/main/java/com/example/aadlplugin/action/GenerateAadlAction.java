package com.example.aadlplugin.action;

import com.example.aadlplugin.Activator;
import com.example.aadlplugin.client.ModelType;
import com.example.aadlplugin.dialog.AadlResultDialog;
import com.example.aadlplugin.dialog.RequirementInputDialog;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class GenerateAadlAction implements IObjectActionDelegate {

    private Shell shell;
    private IProject currentProject;

    @Override
    public void setActivePart(IAction action, IWorkbenchPart targetPart) {
        shell = targetPart.getSite().getShell();
    }

    @Override
    public void run(IAction action) {
        RequirementInputDialog dialog = new RequirementInputDialog(shell);
        if (dialog.open() != org.eclipse.jface.window.Window.OK) {
            return;
        }

        String requirement = dialog.getRequirement();
        String modelStr = dialog.getModel();
        ModelType modelType = "DEEPSEEK".equals(modelStr) ? ModelType.DEEPSEEK : ModelType.OLLAMA;
        boolean isIncremental = dialog.isIncremental();
        String sessionId = dialog.getSessionId();

        try {
            String title = isIncremental ? "AADL Generator - 增量更新" : "AADL Generator";
            String msg = isIncremental ? "开始增量更新..." : "开始分析需求...";
            MessageDialog.openInformation(shell, title, msg);

            String result;
            if (isIncremental) {
                result = Activator.getDefault().executeAgentIncremental(requirement, modelType, sessionId);
            } else {
                result = Activator.getDefault().executeAgent(requirement, modelType);
            }

            com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.JsonNode node = mapper.readTree(result);

            if (node.get("success").asBoolean()) {
                String aadlContent = node.get("data").asText();
                String outputFile = node.has("outputFile") ? node.get("outputFile").asText() : "";
                saveAadlFile(aadlContent);
                AadlResultDialog resultDialog = new AadlResultDialog(shell, aadlContent, outputFile);
                resultDialog.open();
            } else {
                MessageDialog.openError(shell, "Error", node.get("message").asText());
            }

        } catch (Exception e) {
            MessageDialog.openError(shell, "Error", "生成AADL模型失败:\n" + e.getMessage());
            e.printStackTrace();
        }
    }

    private void saveAadlFile(String aadlContent) {
        try {
            IWorkspaceRoot root = ResourcesPlugin.getWorkspace().getRoot();
            
            if (currentProject == null) {
                IStructuredSelection selection = (IStructuredSelection) 
                        org.eclipse.ui.PlatformUI.getWorkbench().getActiveWorkbenchWindow().getSelectionService().getSelection();
                if (!selection.isEmpty()) {
                    Object element = selection.getFirstElement();
                    if (element instanceof IProject) {
                        currentProject = (IProject) element;
                    }
                }
            }

            if (currentProject == null) {
                IProject[] projects = root.getProjects();
                if (projects.length > 0) {
                    currentProject = projects[0];
                } else {
                    MessageDialog.openError(shell, "Error", "请先创建一个项目");
                    return;
                }
            }

            String fileName = "generated_model.aadl";
            IFile aadlFile = currentProject.getFile(fileName);
            
            if (aadlFile.exists()) {
                int count = 1;
                while (currentProject.getFile("generated_model_" + count + ".aadl").exists()) {
                    count++;
                }
                fileName = "generated_model_" + count + ".aadl";
                aadlFile = currentProject.getFile(fileName);
            }

            InputStream is = new java.io.ByteArrayInputStream(aadlContent.getBytes(StandardCharsets.UTF_8));
            aadlFile.create(is, true, null);

        } catch (CoreException e) {
            MessageDialog.openError(shell, "Error", "保存AADL文件失败:\n" + e.getMessage());
        }
    }

    @Override
    public void selectionChanged(IAction action, ISelection selection) {
        if (selection instanceof IStructuredSelection) {
            Object element = ((IStructuredSelection) selection).getFirstElement();
            if (element instanceof IProject) {
                currentProject = (IProject) element;
            }
        }
    }
}