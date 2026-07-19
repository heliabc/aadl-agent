
package com.example.aadlplugin.rag;

import com.example.aadlplugin.rag.model.Document;
import com.example.aadlplugin.config.RagConfig;
import java.util.*;
import java.util.logging.Logger;

public class RrfFusion {
    
    private static final Logger log = Logger.getLogger(RrfFusion.class.getName());

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

        log.info("RRF fusion completed, fused " + fusedResults.size() + " documents");

        return fusedResults;
    }
}
