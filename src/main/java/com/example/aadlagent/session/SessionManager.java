package com.example.aadlagent.session;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Slf4j
@Component
public class SessionManager {

    private static final int MAX_MESSAGES_PER_SESSION = 50;
    private static final int MAX_SESSIONS = 100;

    private final Map<String, Deque<ChatMessage>> sessions = new ConcurrentHashMap<>();

    public String createSession() {
        String sessionId = "SES-" + UUID.randomUUID().toString().substring(0, 8);
        sessions.put(sessionId, new ConcurrentLinkedDeque<>());
        log.info("Created new session: {}", sessionId);
        return sessionId;
    }

    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    public void addMessage(String sessionId, ChatMessage message) {
        Deque<ChatMessage> messages = sessions.get(sessionId);
        if (messages == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        messages.add(message);
        
        while (messages.size() > MAX_MESSAGES_PER_SESSION) {
            messages.pollFirst();
        }

        log.debug("Added message to session {}, total messages: {}", sessionId, messages.size());
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
            String roleLabel = switch (msg.getRole()) {
                case "user" -> "用户";
                case "assistant" -> "助手";
                case "system" -> "系统";
                default -> msg.getRole();
            };
            context.append(String.format("%s: %s\n", roleLabel, msg.getContent()));
        }

        return context.toString();
    }

    public void clearSession(String sessionId) {
        Deque<ChatMessage> messages = sessions.get(sessionId);
        if (messages != null) {
            messages.clear();
            log.info("Cleared session: {}", sessionId);
        }
    }

    public void removeSession(String sessionId) {
        sessions.remove(sessionId);
        log.info("Removed session: {}", sessionId);
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
                log.info("Cleaning up empty session: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}