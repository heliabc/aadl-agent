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

    /**
     * 使用自定义 ID 创建 session（基于需求文件名或文字片段）。
     * 如果 ID 已存在则直接复用，不重复创建。
     */
    public String createSession(String customId) {
        String sessionId = sanitizeSessionId(customId);
        if (sessionId == null || sessionId.trim().isEmpty()) {
            return createSession();
        }
        if (!sessions.containsKey(sessionId)) {
            sessions.put(sessionId, new ConcurrentLinkedDeque<>());
            log.info("Created new session with custom ID: {}", sessionId);
        }
        return sessionId;
    }

    /**
     * 从文件名或文字片段生成合法的 session ID。
     * 文件名：去掉路径和扩展名，如 "brake_system.txt" → "brake_system"
     * 文字片段：取前20个字符，替换空白和特殊字符为下划线
     */
    public static String sanitizeSessionId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String id = raw.trim();
        // 去掉路径前缀
        id = id.replaceAll(".*[\\\\/]", "");
        // 去掉常见扩展名
        id = id.replaceAll("\\.(txt|doc|docx|json|aadl|xml|csv|md)$", "");
        // 去掉 requirements_ / architecture_ 等前缀
        id = id.replaceAll("^(requirements|architecture|modules)_", "");
        // 去掉 -architecture / -modules 等后缀
        id = id.replaceAll("-(architecture|modules|aadl)$", "");
        // 限制长度
        if (id.length() > 50) {
            id = id.substring(0, 50);
        }
        // 替换空白和特殊字符为下划线（保留中文、字母、数字、下划线、连字符）
        id = id.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]+", "_");
        // 去掉首尾下划线
        id = id.replaceAll("^_+|_+$", "");
        return id.isEmpty() ? null : id;
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