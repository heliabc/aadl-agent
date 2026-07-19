package com.example.aadlplugin.config;

public class FileConfig {

    private String inputPath = "./input";

    private String outputPath = "./output";

    private String requirementsPath = "./output/requirements";

    private String architecturePath = "./output/architecture";

    private String modulesPath = "./output/modules";

    private String aadlPath = "./output/aadl";

    public FileConfig() {
    }

    public String getInputPath() {
        return inputPath;
    }

    public void setInputPath(String inputPath) {
        this.inputPath = inputPath;
    }

    public String getOutputPath() {
        return outputPath;
    }

    public void setOutputPath(String outputPath) {
        this.outputPath = outputPath;
    }

    public String getRequirementsPath() {
        return requirementsPath;
    }

    public void setRequirementsPath(String requirementsPath) {
        this.requirementsPath = requirementsPath;
    }

    public String getArchitecturePath() {
        return architecturePath;
    }

    public void setArchitecturePath(String architecturePath) {
        this.architecturePath = architecturePath;
    }

    public String getModulesPath() {
        return modulesPath;
    }

    public void setModulesPath(String modulesPath) {
        this.modulesPath = modulesPath;
    }

    public String getAadlPath() {
        return aadlPath;
    }

    public void setAadlPath(String aadlPath) {
        this.aadlPath = aadlPath;
    }
}
