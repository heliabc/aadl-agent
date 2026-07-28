package com.example.aadlplugin.rag.model;

import java.util.ArrayList;
import java.util.List;

public class CodeExample {

    private String id;
    private String title;
    private String category;
    private String description;
    private List<String> keywords;
    private String code;

    public CodeExample() {
        this.keywords = new ArrayList<>();
    }

    public CodeExample(String id, String title, String category, String description, 
                       List<String> keywords, String code) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.description = description;
        this.keywords = keywords != null ? keywords : new ArrayList<>();
        this.code = code;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords != null ? keywords : new ArrayList<>();
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }
}
