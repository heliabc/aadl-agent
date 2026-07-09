
package com.example.aadlagent.rag;

import com.example.aadlagent.rag.model.Document;
import com.example.aadlagent.config.RagConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Slf4j
@Component
public class RrfFusion {

    private final RagConfig ragConfig;

    public RrfFusion(RagConfig ragConfig) {
        this.ragConfig = ragConfig;
    }

    public List<Document> fuse(List<List<Document>> resultLists) {
        if (resultLists == null || resultLists.isEmpty()) {
            return Collections.emptyList();
        }

        Map<String, Double> scoreMap = new HashMap<>();
        Map<String, Document> docMap = new HashMap<>();

        int k = ragConfig.getRrfK();

        for (List<Document> results : resultLists) {
            if (results == null || results.isEmpty()) {
                continue;
            }

            for (int rank = 0; rank < results.size(); rank++) {
                Document doc = results.get(rank);
                String docId = doc.getId();

                double rrfScore = 1.0 / (k + rank + 1);

                scoreMap.merge(docId, rrfScore, Double::sum);
                docMap.put(docId, doc);
            }
        }

        List<Document> fusedResults = new ArrayList<>();
        for (Map.Entry<String, Double> entry : scoreMap.entrySet()) {
            Document doc = docMap.get(entry.getKey());
            doc.setScore(entry.getValue());
            fusedResults.add(doc);
        }

        fusedResults.sort((a, b) -> Double.compare(b.getScore(), a.getScore()));

        int topK = ragConfig.getTopK();
        if (fusedResults.size() > topK) {
            fusedResults = fusedResults.subList(0, topK);
        }

        log.info("RRF fusion completed, fused {} documents", fusedResults.size());

        return fusedResults;
    }
}
