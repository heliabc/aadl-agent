
package com.example.aadlagent.rag.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Document {

    private String id;

    private String content;

    private String title;

    private String source;

    private float[] embedding;

    private double score;
}
