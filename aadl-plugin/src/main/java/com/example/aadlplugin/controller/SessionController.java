package com.example.aadlplugin.controller;

import com.example.aadlplugin.session.ChatMessage;
import com.example.aadlplugin.session.SessionManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

public class SessionController {

    private static final Logger log = Logger.getLogger(SessionController.class.getName());

    private final SessionManager sessionManager;

    public SessionController(SessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    public Map<String, Object> createSession() {
        String sessionId = sessionManager.createSession();

        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("message", "Session 创建成功");

        return response;
    }

    public Map<String, Object> getSessionInfo(String sessionId) {
        Map<String, Object> response = new HashMap<>();

        if (!sessionManager.exists(sessionId)) {
            response.put("success", false);
            response.put("message", "Session 不存在: " + sessionId);
            return response;
        }

        List<ChatMessage> messages = sessionManager.getMessages(sessionId);

        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("messageCount", messages.size());
        response.put("messages", messages);

        return response;
    }

    public Map<String, Object> deleteSession(String sessionId) {
        Map<String, Object> response = new HashMap<>();

        if (!sessionManager.exists(sessionId)) {
            response.put("success", false);
            response.put("message", "Session 不存在: " + sessionId);
            return response;
        }

        sessionManager.removeSession(sessionId);

        response.put("success", true);
        response.put("message", "Session 删除成功");

        return response;
    }

    public Map<String, Object> clearSession(String sessionId) {
        Map<String, Object> response = new HashMap<>();

        if (!sessionManager.exists(sessionId)) {
            response.put("success", false);
            response.put("message", "Session 不存在: " + sessionId);
            return response;
        }

        sessionManager.clearSession(sessionId);

        response.put("success", true);
        response.put("message", "Session 历史已清空");

        return response;
    }

    public Map<String, Object> listSessions() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", sessionManager.getSessionCount());
        response.put("sessionIds", sessionManager.getAllSessionIds());

        return response;
    }

    public Map<String, Object> addMessage(String sessionId, String role, String content, String agentType) {
        Map<String, Object> response = new HashMap<>();

        if (!sessionManager.exists(sessionId)) {
            response.put("success", false);
            response.put("message", "Session 不存在: " + sessionId);
            return response;
        }

        if (content == null || content.trim().isEmpty()) {
            response.put("success", false);
            response.put("message", "内容不能为空");
            return response;
        }

        ChatMessage message;
        if ("user".equals(role)) {
            message = ChatMessage.user(content);
        } else if ("system".equals(role)) {
            message = ChatMessage.system(content);
        } else {
            message = ChatMessage.assistant(content, agentType);
        }

        sessionManager.addMessage(sessionId, message);

        response.put("success", true);
        response.put("message", "消息添加成功");

        return response;
    }

    public Map<String, Object> getContext(String sessionId, int maxMessages) {
        Map<String, Object> response = new HashMap<>();

        if (!sessionManager.exists(sessionId)) {
            response.put("success", false);
            response.put("message", "Session 不存在: " + sessionId);
            return response;
        }

        String context = sessionManager.buildContext(sessionId, maxMessages);

        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("context", context);

        return response;
    }
}