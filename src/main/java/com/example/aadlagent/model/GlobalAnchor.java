package com.example.aadlagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GlobalAnchor {

    private String anchorId;

    private String anchorType;

    private String content;

    private String source;

    private String category;
}
