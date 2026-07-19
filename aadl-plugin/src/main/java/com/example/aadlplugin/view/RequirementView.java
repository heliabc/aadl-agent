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

public class RequirementView extends ViewPart {

    public static final String ID = "com.example.aadlplugin.view.RequirementView";

    private TableViewer viewer;
    private List<RequirementItem> requirements = new ArrayList<>();

    public static class RequirementItem {
        private String id;
        private String title;
        private String description;
        private String originalRequirement;

        public RequirementItem(String id, String title, String description, String originalRequirement) {
            this.id = id;
            this.title = title;
            this.description = description;
            this.originalRequirement = originalRequirement;
        }

        public String getId() { return id; }
        public String getTitle() { return title; }
        public String getDescription() { return description; }
        public String getOriginalRequirement() { return originalRequirement; }
    }

    @Override
    public void createPartControl(Composite parent) {
        parent.setLayout(new FillLayout());

        viewer = new TableViewer(parent, SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.BORDER);
        
        Table table = viewer.getTable();
        table.setHeaderVisible(true);
        table.setLinesVisible(true);

        TableViewerColumn colId = new TableViewerColumn(viewer, SWT.NONE);
        colId.getColumn().setText("需求ID");
        colId.getColumn().setWidth(100);
        colId.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((RequirementItem) element).getId();
            }
        });

        TableViewerColumn colTitle = new TableViewerColumn(viewer, SWT.NONE);
        colTitle.getColumn().setText("需求标题");
        colTitle.getColumn().setWidth(200);
        colTitle.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((RequirementItem) element).getTitle();
            }
        });

        TableViewerColumn colDesc = new TableViewerColumn(viewer, SWT.NONE);
        colDesc.getColumn().setText("需求描述");
        colDesc.getColumn().setWidth(300);
        colDesc.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((RequirementItem) element).getDescription();
            }
        });

        TableViewerColumn colOriginal = new TableViewerColumn(viewer, SWT.NONE);
        colOriginal.getColumn().setText("原始需求");
        colOriginal.getColumn().setWidth(300);
        colOriginal.setLabelProvider(new ColumnLabelProvider() {
            @Override
            public String getText(Object element) {
                return ((RequirementItem) element).getOriginalRequirement();
            }
        });

        viewer.setContentProvider(ArrayContentProvider.getInstance());
        viewer.setInput(requirements);
    }

    @Override
    public void setFocus() {
        viewer.getControl().setFocus();
    }

    public void setRequirements(List<RequirementItem> requirements) {
        this.requirements = requirements;
        viewer.setInput(requirements);
        viewer.refresh();
    }

    public void addRequirement(RequirementItem item) {
        requirements.add(item);
        viewer.refresh();
    }

    public void clear() {
        requirements.clear();
        viewer.setInput(requirements);
        viewer.refresh();
    }
}