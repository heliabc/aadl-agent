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
    private String currentSessionId;

    public SessionManager() {
    }

    public String createSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            return createSession();
        }
        sessions.put(sessionId, new ConcurrentLinkedDeque<>());
        this.currentSessionId = sessionId;
        log.info(String.format("Created new session with id: %s", sessionId));
        return sessionId;
    }

    public String createSession() {
        String sessionId = "SES-" + UUID.randomUUID().toString().substring(0, 8);
        sessions.put(sessionId, new ConcurrentLinkedDeque<>());
        log.info(String.format("Created new session: %s", sessionId));
        return sessionId;
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
        log.info(String.format("Removed session: %s", sessionId));
    }

    public void deleteSession(String sessionId) {
        sessions.remove(sessionId);
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