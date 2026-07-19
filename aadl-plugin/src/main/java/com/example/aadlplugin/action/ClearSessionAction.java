package com.example.aadlplugin.action;

import com.example.aadlplugin.Activator;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;

public class ClearSessionAction implements IObjectActionDelegate {

    private Shell shell;

    @Override
    public void setActivePart(IAction action, IWorkbenchPart targetPart) {
        shell = targetPart.getSite().getShell();
    }

    @Override
    public void run(IAction action) {
        if (!MessageDialog.openConfirm(shell, "Clear Session", "确定要清除当前会话吗？")) {
            return;
        }

        try {
            Activator.getDefault().getSessionManager().deleteSession(
                    Activator.getDefault().getSessionManager().getCurrentSessionId()
            );
            Activator.getDefault().getTraceabilityService().clearRecords(
                    Activator.getDefault().getSessionManager().getCurrentSessionId()
            );
            MessageDialog.openInformation(shell, "Success", "会话已清除");
        } catch (Exception e) {
            MessageDialog.openError(shell, "Error", "清除会话失败:\n" + e.getMessage());
        }
    }

    @Override
    public void selectionChanged(IAction action, ISelection selection) {
    }
}