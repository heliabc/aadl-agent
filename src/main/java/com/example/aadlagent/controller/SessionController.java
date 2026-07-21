package com.example.aadlagent.controller;

import com.example.aadlagent.session.ChatMessage;
import com.example.aadlagent.session.SessionManager;
import com.example.aadlagent.service.TaskCancellationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionManager sessionManager;
    private final TaskCancellationService cancellationService;

    public SessionController(SessionManager sessionManager, TaskCancellationService cancellationService) {
        this.sessionManager = sessionManager;
        this.cancellationService = cancellationService;
    }

    @PostMapping("/create")
    public ResponseEntity<Map<String, Object>> createSession() {
        String sessionId = sessionManager.createSession();
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("message", "Session 创建成功");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/info/{sessionId}")
    public ResponseEntity<Map<String, Object>> getSessionInfo(@PathVariable String sessionId) {
        if (!sessionManager.exists(sessionId)) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Session 不存在: " + sessionId);
            return ResponseEntity.badRequest().body(error);
        }

        List<ChatMessage> messages = sessionManager.getMessages(sessionId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("messageCount", messages.size());
        response.put("messages", messages);
        
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{sessionId}")
    public ResponseEntity<Map<String, Object>> deleteSession(@PathVariable String sessionId) {
        if (!sessionManager.exists(sessionId)) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Session 不存在: " + sessionId);
            return ResponseEntity.badRequest().body(error);
        }

        sessionManager.removeSession(sessionId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Session 删除成功");
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{sessionId}/clear")
    public ResponseEntity<Map<String, Object>> clearSession(@PathVariable String sessionId) {
        if (!sessionManager.exists(sessionId)) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Session 不存在: " + sessionId);
            return ResponseEntity.badRequest().body(error);
        }

        sessionManager.clearSession(sessionId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "Session 历史已清空");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/list")
    public ResponseEntity<Map<String, Object>> listSessions() {
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("count", sessionManager.getSessionCount());
        response.put("sessionIds", sessionManager.getAllSessionIds());
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{sessionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelTask(@PathVariable String sessionId) {
        log.info("收到任务取消请求，sessionId: {}", sessionId);
        
        cancellationService.cancelTask(sessionId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("message", "任务取消请求已发送");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{sessionId}/cancelled")
    public ResponseEntity<Map<String, Object>> isTaskCancelled(@PathVariable String sessionId) {
        boolean cancelled = cancellationService.isCancelled(sessionId);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("cancelled", cancelled);
        
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{sessionId}/message")
    public ResponseEntity<Map<String, Object>> addMessage(@PathVariable String sessionId, 
                                                         @RequestBody Map<String, String> request) {
        String role = request.get("role");
        String content = request.get("content");
        String agentType = request.get("agentType");

        if (!sessionManager.exists(sessionId)) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Session 不存在: " + sessionId);
            return ResponseEntity.badRequest().body(error);
        }

        if (content == null || content.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "内容不能为空");
            return ResponseEntity.badRequest().body(error);
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
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", "消息添加成功");
        
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{sessionId}/context")
    public ResponseEntity<Map<String, Object>> getContext(@PathVariable String sessionId,
                                                          @RequestParam(defaultValue = "10") int maxMessages) {
        if (!sessionManager.exists(sessionId)) {
            Map<String, Object> error = new HashMap<>();
            error.put("success", false);
            error.put("message", "Session 不存在: " + sessionId);
            return ResponseEntity.badRequest().body(error);
        }

        String context = sessionManager.buildContext(sessionId, maxMessages);
        
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("sessionId", sessionId);
        response.put("context", context);
        
        return ResponseEntity.ok(response);
    }
}