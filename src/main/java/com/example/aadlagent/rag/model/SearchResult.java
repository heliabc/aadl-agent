
package com.example.aadlagent.rag.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SearchResult {

    private List<Document> documents;

    private String rewrittenQuery;

    private long searchTime;
}
