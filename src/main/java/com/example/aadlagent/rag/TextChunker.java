package com.example.aadlagent.rag;

import java.util.ArrayList;
import java.util.List;

public class TextChunker {

    public static List<String> chunk(String text, int chunkSize, int overlap) {
        List<String> chunks = new ArrayList<>();
        
        if (text == null || text.isEmpty()) {
            return chunks;
        }

        text = text.trim();
        
        if (text.length() <= chunkSize) {
            chunks.add(text);
            return chunks;
        }

        int start = 0;
        int end = chunkSize;
        
        while (start < text.length()) {
            String chunk = text.substring(start, Math.min(end, text.length()));
            
            if (end < text.length()) {
                int lastSpace = chunk.lastIndexOf(' ');
                int lastNewline = chunk.lastIndexOf('\n');
                int lastPeriod = chunk.lastIndexOf('。');
                int lastComma = chunk.lastIndexOf('，');
                
                int splitPoint = Math.max(lastSpace, Math.max(lastNewline, Math.max(lastPeriod, lastComma)));
                
                if (splitPoint > chunkSize / 2) {
                    chunk = chunk.substring(0, splitPoint + 1);
                    end = start + splitPoint + 1;
                }
            }
            
            chunks.add(chunk.trim());
            
            start = end - overlap;
            if (start < 0) start = 0;
            end = start + chunkSize;
            
            if (start >= text.length()) {
                break;
            }
        }

        return chunks;
    }

    public static List<String> chunk(String text) {
        return chunk(text, 500, 100);
    }
}