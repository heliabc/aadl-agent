package com.example.aadlplugin.action;

import com.example.aadlplugin.Activator;
import com.example.aadlplugin.model.TraceabilityRecord;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.ui.IObjectActionDelegate;
import org.eclipse.ui.IWorkbenchPart;

import java.util.List;
import java.util.Set;

public class ViewTraceabilityAction implements IObjectActionDelegate {

    private Shell shell;

    @Override
    public void setActivePart(IAction action, IWorkbenchPart targetPart) {
        shell = targetPart.getSite().getShell();
    }

    @Override
    public void run(IAction action) {
        try {
            String sessionId = Activator.getDefault().getSessionManager().getCurrentSessionId();
            
            if (sessionId == null) {
                Set<String> availableSessions = Activator.getDefault().getTraceabilityService().getAllSessionIds();
                if (availableSessions.isEmpty()) {
                    MessageDialog.openInformation(shell, "Traceability", "请先运行需求分析");
                    return;
                }
                
                String[] sessionArray = availableSessions.toArray(new String[0]);
                org.eclipse.jface.dialogs.InputDialog dialog = new org.eclipse.jface.dialogs.InputDialog(
                        shell, "选择会话", "请选择一个会话ID:", sessionArray[0], null);
                if (dialog.open() == org.eclipse.jface.window.Window.OK) {
                    sessionId = dialog.getValue();
                } else {
                    return;
                }
            }

            List<TraceabilityRecord> records = Activator.getDefault().getTraceabilityService().getRecords(sessionId);
            
            if (records.isEmpty()) {
                MessageDialog.openInformation(shell, "Traceability", "没有找到追溯记录");
                return;
            }

            StringBuilder html = new StringBuilder();
            html.append("<html><body>");
            html.append("<h3>需求追溯矩阵</h3>");
            
            List<TraceabilityRecord> reqToOriginal = records.stream()
                    .filter(r -> TraceabilityRecord.TRACE_LEVEL_REQ_TO_ORIGINAL.equals(r.getTraceLevel()))
                    .toList();
            List<TraceabilityRecord> aadlToReq = records.stream()
                    .filter(r -> TraceabilityRecord.TRACE_LEVEL_AADL_TO_REQ.equals(r.getTraceLevel()))
                    .toList();

            if (!reqToOriginal.isEmpty()) {
                html.append("<h4 style='color: #007bff;'>📋 条目化需求 → 原始需求</h4>");
                html.append("<table border='1' cellpadding='5' cellspacing='0'>");
                html.append("<tr><th>需求ID</th><th>需求标题</th><th>对应原始需求</th></tr>");
                reqToOriginal.forEach(r -> {
                    html.append("<tr>");
                    html.append("<td>").append(r.getRequirementId()).append("</td>");
                    html.append("<td>").append(r.getRequirementTitle()).append("</td>");
                    html.append("<td>").append(r.getOriginalRequirement()).append("</td>");
                    html.append("</tr>");
                });
                html.append("</table>");
            }

            if (!aadlToReq.isEmpty()) {
                html.append("<h4 style='color: #28a745;'>🔗 AADL组件 → 条目化需求</h4>");
                html.append("<table border='1' cellpadding='5' cellspacing='0'>");
                html.append("<tr><th>需求ID</th><th>需求标题</th><th>AADL组件</th><th>来源</th></tr>");
                aadlToReq.forEach(r -> {
                    html.append("<tr>");
                    html.append("<td>").append(r.getRequirementId()).append("</td>");
                    html.append("<td>").append(r.getRequirementTitle()).append("</td>");
                    html.append("<td>").append(r.getAadlComponent()).append("</td>");
                    html.append("<td>").append(r.getSource()).append("</td>");
                    html.append("</tr>");
                });
                html.append("</table>");
            }

            html.append("</body></html>");

            org.eclipse.jface.dialogs.Dialog dialog = new org.eclipse.jface.dialogs.Dialog(shell) {
                @Override
                protected void configureShell(org.eclipse.swt.widgets.Shell newShell) {
                    super.configureShell(newShell);
                    newShell.setText("Traceability Matrix");
                    org.eclipse.swt.graphics.Rectangle screenBounds = newShell.getDisplay().getPrimaryMonitor().getBounds();
                    int width = (int) (screenBounds.width * 0.85);
                    int height = (int) (screenBounds.height * 0.8);
                    newShell.setSize(width, height);
                    newShell.setLocation((screenBounds.width - width) / 2, (screenBounds.height - height) / 2);
                }
                
                @Override
                protected org.eclipse.swt.widgets.Control createDialogArea(org.eclipse.swt.widgets.Composite parent) {
                    org.eclipse.swt.widgets.Composite composite = (org.eclipse.swt.widgets.Composite) super.createDialogArea(parent);
                    composite.setLayout(new org.eclipse.swt.layout.FillLayout());
                    org.eclipse.swt.browser.Browser browser = new org.eclipse.swt.browser.Browser(composite, org.eclipse.swt.SWT.NONE);
                    browser.setText(html.toString());
                    return composite;
                }
            };
            dialog.open();

        } catch (Exception e) {
            MessageDialog.openError(shell, "Error", "获取追溯记录失败:\n" + e.getMessage());
        }
    }

    @Override
    public void selectionChanged(IAction action, ISelection selection) {
    }
}