package com.example.aadlplugin.session;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.logging.Logger;

public class SessionManager {
    
    private static final Logger log = Logger.getLogger(SessionManager.class.getName());

    private static final int MAX_MESSAGES_PER_SESSION = 50;
    private static final int MAX_SESSIONS = 100;

    private final Map<String, Deque<ChatMessage>> sessions = new ConcurrentHashMap<>();
    private final Map<String, SessionMetadata> sessionMetadata = new ConcurrentHashMap<>();
    private String currentSessionId;

    public SessionManager() {
    }

    public String createSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return createSession();
        }
        sessions.put(sessionId, new ConcurrentLinkedDeque<>());
        SessionMetadata metadata = new SessionMetadata();
        metadata.setSessionId(sessionId);
        metadata.setName("会话 " + sessionId.substring(0, Math.min(8, sessionId.length())));
        sessionMetadata.put(sessionId, metadata);
        this.currentSessionId = sessionId;
        log.info(String.format("Created new session with id: %s", sessionId));
        return sessionId;
    }

    public String createSession() {
        String sessionId = "SES-" + UUID.randomUUID().toString().substring(0, 8);
        sessions.put(sessionId, new ConcurrentLinkedDeque<>());
        SessionMetadata metadata = new SessionMetadata();
        metadata.setSessionId(sessionId);
        metadata.setName("会话 " + sessionId.substring(4));
        sessionMetadata.put(sessionId, metadata);
        log.info(String.format("Created new session: %s", sessionId));
        return sessionId;
    }
    
    public void setSessionName(String sessionId, String name) {
        SessionMetadata metadata = sessionMetadata.get(sessionId);
        if (metadata != null) {
            metadata.setName(name);
            log.info(String.format("Renamed session %s to: %s", sessionId, name));
        }
    }
    
    public void setSessionRequirementSummary(String sessionId, String summary) {
        SessionMetadata metadata = sessionMetadata.get(sessionId);
        if (metadata != null) {
            metadata.setRequirementSummary(summary);
        }
    }
    
    public void setSessionModelType(String sessionId, String modelType) {
        SessionMetadata metadata = sessionMetadata.get(sessionId);
        if (metadata != null) {
            metadata.setModelType(modelType);
        }
    }
    
    public void setSessionRequirementCount(String sessionId, int count) {
        SessionMetadata metadata = sessionMetadata.get(sessionId);
        if (metadata != null) {
            metadata.setRequirementCount(count);
        }
    }
    
    public void setSessionHasAadlGenerated(String sessionId, boolean hasGenerated) {
        SessionMetadata metadata = sessionMetadata.get(sessionId);
        if (metadata != null) {
            metadata.setHasAadlGenerated(hasGenerated);
        }
    }
    
    public SessionMetadata getSessionMetadata(String sessionId) {
        return sessionMetadata.get(sessionId);
    }
    
    public List<SessionMetadata> getAllSessionMetadata() {
        List<SessionMetadata> metadataList = new ArrayList<>(sessionMetadata.values());
        metadataList.sort((a, b) -> b.getCreatedAt().compareTo(a.getCreatedAt()));
        return metadataList;
    }

    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public void addMessage(String sessionId, ChatMessage message) {
        Deque<ChatMessage> messages = sessions.get(sessionId);
        if (messages == null) {
            log.warning(String.format("Session not found: %s", sessionId));
            return;
        }

        messages.add(message);
        
        while (messages.size() > MAX_MESSAGES_PER_SESSION) {
            messages.pollFirst();
        }

        log.fine(String.format("Added message to session %s, total messages: %d", sessionId, messages.size()));
    }

    public List<ChatMessage> getMessages(String sessionId) {
        Deque<ChatMessage> messages = sessions.get(sessionId);
        if (messages == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(messages);
    }

    public String buildContext(String sessionId, int maxMessages) {
        List<ChatMessage> messages = getMessages(sessionId);
        if (messages.isEmpty()) {
            return "";
        }

        int startIndex = Math.max(0, messages.size() - maxMessages);
        StringBuilder context = new StringBuilder();
        context.append("【对话历史】\n");

        for (int i = startIndex; i < messages.size(); i++) {
            ChatMessage msg = messages.get(i);
            String roleLabel;
            switch (msg.getRole()) {
                case "user": roleLabel = "用户"; break;
                case "assistant": roleLabel = "助手"; break;
                case "system": roleLabel = "系统"; break;
                default: roleLabel = msg.getRole(); break;
            }
            context.append(String.format("%s: %s\n", roleLabel, msg.getContent()));
        }

        return context.toString();
    }

    public void clearSession(String sessionId) {
        Deque<ChatMessage> messages = sessions.get(sessionId);
        if (messages != null) {
            messages.clear();
            log.info(String.format("Cleared session: %s", sessionId));
        }
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        sessionMetadata.remove(sessionId);
        log.info(String.format("Removed session: %s", sessionId));
    }

    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
        sessionMetadata.remove(sessionId);
        log.info(String.format("Deleted session: %s", sessionId));
    }

    public int getSessionCount() {
        return sessions.size();
    }

    public Set<String> getAllSessionIds() {
        return sessions.keySet();
    }

    public void cleanupExpiredSessions(long maxIdleMinutes) {
        long expireTime = System.currentTimeMillis() - (maxIdleMinutes * 60 * 1000);
        sessions.entrySet().removeIf(entry -> {
            Deque<ChatMessage> messages = entry.getValue();
            if (messages.isEmpty()) {
                log.info(String.format("Cleaning up empty session: %s", entry.getKey()));
                return true;
            }
            return false;
        });
    }

    public String getCurrentSessionId() {
        return currentSessionId;
    }

    public void setCurrentSessionId(String sessionId) {
        if (sessions.containsKey(sessionId)) {
            this.currentSessionId = sessionId;
            log.info(String.format("Current session set to: %s", sessionId));
        } else {
            log.warning(String.format("Cannot set current session to non-existent session: %s", sessionId));
        }
    }
}