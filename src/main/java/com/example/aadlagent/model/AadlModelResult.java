package com.example.aadlagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AadlModelResult {

    private String aadlContent;
    
    private Integer componentCount;
    
    private Integer connectionCount;
}