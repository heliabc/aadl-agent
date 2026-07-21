package com.example.aadlagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
public class TaskCancellationService {

    private final Map<String, AtomicBoolean> cancellationMap = new ConcurrentHashMap<>();

    public AtomicBoolean registerTask(String sessionId) {
        AtomicBoolean flag = new AtomicBoolean(false);
        cancellationMap.put(sessionId, flag);
        log.info("Registered task for cancellation: {}", sessionId);
        return flag;
    }

    public void cancelTask(String sessionId) {
        AtomicBoolean flag = cancellationMap.get(sessionId);
        if (flag != null) {
            flag.set(true);
            log.info("Task cancelled: {}", sessionId);
        } else {
            log.warn("No task found to cancel: {}", sessionId);
        }
    }

    public boolean isCancelled(String sessionId) {
        AtomicBoolean flag = cancellationMap.get(sessionId);
        return flag != null && flag.get();
    }

    public AtomicBoolean getCancellationFlag(String sessionId) {
        return cancellationMap.get(sessionId);
    }

    public void unregisterTask(String sessionId) {
        cancellationMap.remove(sessionId);
        log.info("Unregistered task: {}", sessionId);
    }

    public void clearAll() {
        cancellationMap.clear();
        log.info("Cleared all cancellation flags");
    }

    public int getActiveTaskCount() {
        return cancellationMap.size();
    }
}
