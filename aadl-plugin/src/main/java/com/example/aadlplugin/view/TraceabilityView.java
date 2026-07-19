package com.example.aadlplugin.view;

import org.eclipse.jface.viewers.ArrayContentProvider;
import org.eclipse.jface.viewers.ColumnLabelProvider;
import org.eclipse.jface.viewers.TableViewer;
import org.eclipse.jface.viewers.TableViewerColumn;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.FillLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Table;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;

public class TraceabilityView extends ViewPart {

    public static final String ID = "com.example.aadlplugin.view.TraceabilityView";

    private TableViewer viewer;
    private List<TraceabilityItem> records = new ArrayList<>();

    public static class TraceabilityItem {
        private String requirementId;
        private String requirementTitle;
        private String originalRequirement;
        private String aadlComponent;
        private String aadlCode;
        private String traceLevel;
        private String source;

        public TraceabilityItem(String requirementId, String requirementTitle, 
                                String originalRequirement, String aadlComponent,
                                String aadlCode, String traceLevel, String source) {
            this.requirementId = requirementId;
            this.requirementTitle = requirementTitle;
            this.originalRequirement = originalRequirement;
            this.aadlComponent = aadlComponent;
            this.aadlCode = aadlCode;
            this.traceLevel = traceLevel;
            this.source = source;
        }

        public String getRequirementId() { return requirementId; }
        public String getRequirementTitle() { return requirementTitle; }
        public String getOriginalRequirement() { return originalRequirement; }
        public String getAadlComponent() { return aadlComponent; }
        public String getAadlCode() { return aadlCode; }
        public String getTraceLevel() { return traceLevel; }
        public String getSource() { return source; }
    }

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());

        viewer = new TableViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        TableViewerColumn colReqId = new TableViewerColumn(viewer, SWT.NONE);
        colReqId.getColumn().setText("需求ID");
        colReqId.getColumn().setWidth(100);
        colReqId.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((TraceabilityItem) element).getRequirementId();
            }
        });

        TableViewerColumn colReqTitle = new TableViewerColumn(viewer, SWT.NONE);
        colReqTitle.getColumn().setText("需求标题");
        colReqTitle.getColumn().setWidth(150);
        colReqTitle.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((TraceabilityItem) element).getRequirementTitle();
            }
        });

        TableViewerColumn colOriginal = new TableViewerColumn(viewer, SWT.NONE);
        colOriginal.getColumn().setText("原始需求");
        colOriginal.getColumn().setWidth(200);
        colOriginal.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((TraceabilityItem) element).getOriginalRequirement();
            }
        });

        TableViewerColumn colAadlComp = new TableViewerColumn(viewer, SWT.NONE);
        colAadlComp.getColumn().setText("AADL组件");
        colAadlComp.getColumn().setWidth(150);
        colAadlComp.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((TraceabilityItem) element).getAadlComponent();
            }
        });

        TableViewerColumn colLevel = new TableViewerColumn(viewer, SWT.NONE);
        colLevel.getColumn().setText("追溯级别");
        colLevel.getColumn().setWidth(120);
        colLevel.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                String level = ((TraceabilityItem) element).getTraceLevel();
                if ("REQ_TO_ORIGINAL".equals(level)) {
                    return "需求→原始";
                } else if ("AADL_TO_REQ".equals(level)) {
                    return "AADL→需求";
                }
                return level;
            }
        });

        TableViewerColumn colSource = new TableViewerColumn(viewer, SWT.NONE);
        colSource.getColumn().setText("来源");
        colSource.getColumn().setWidth(100);
        colSource.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((TraceabilityItem) element).getSource();
            }
        });

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setInput(records);
    }

    @Override
    public void setFocus() {
        viewer.getControl().setFocus();
    }

    public void setRecords(List<TraceabilityItem> records) {
        this.records = records;
        viewer.setInput(records);
        viewer.refresh();
    }

    public void addRecord(TraceabilityItem item) {
        records.add(item);
        viewer.refresh();
    }

    public void clear() {
        records.clear();
        viewer.setInput(records);
        viewer.refresh();
    }
}