
package com.example.aadlagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Requirement {

    private String requirementId;

    private String title;

    private String description;

    private String priority;

    private List<String> acceptanceCriteria;

    private List<String> dependencies;

    private List<String> globalRef;
}
