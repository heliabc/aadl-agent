package com.example.aadlagent.session;

import com.example.aadlagent.memory.AgentContext;
import com.example.aadlagent.memory.DecisionRecord;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 会话管理器（升级版：分层记忆）
 *
 * 会话记忆包含三层：
 * 1. 消息队列（最近N条原始消息）- 短期记忆
 * 2. 历史摘要（较早消息的压缩摘要）- 中期记忆
 * 3. 决策记录（关键节点）- 重要事件时间线
 * 4. 工作记忆快照（AgentContext）- 结构化中间结果
 *
 * 当消息数超过阈值时，自动将早期消息压缩为摘要。
 */
@Slf4j
@Component
public class SessionManager {

    private static final int MAX_MESSAGES_PER_SESSION = 50;
    private static final int SUMMARY_TRIGGER_THRESHOLD = 30; // 超过这个数量触发摘要
    private static final int MAX_SESSIONS = 100;

    private final Map<String, SessionState> sessions = new ConcurrentHashMap<>();

    /**
     * 会话状态（完整的会话记忆）
     */
    @Data
    public static class SessionState {
        /** 最近的消息队列（短期记忆） */
        Deque<ChatMessage> recentMessages = new ConcurrentLinkedDeque<>();

        /** 历史摘要（中期记忆：较早消息被压缩成摘要文本） */
        String historySummary = "";

        /** 决策记录列表（关键事件时间线） */
        List<DecisionRecord> decisions = new ArrayList<>();

        /** 工作记忆快照（结构化中间结果） */
        AgentContext agentContext;

        /** 最后活动时间 */
        LocalDateTime lastActiveTime = LocalDateTime.now();

        /** 会话创建时间 */
        LocalDateTime createdAt = LocalDateTime.now();

        /** 已生成摘要的次数 */
        int summaryCount = 0;
    }

    public String createSession() {
        String sessionId = "SES-" + UUID.randomUUID().toString().substring(0, 8);
        sessions.put(sessionId, new SessionState());
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
            sessions.put(sessionId, new SessionState());
            log.info("Created new session with custom ID: {}", sessionId);
        }
        return sessionId;
    }

    /**
     * 从文件名或文字片段生成合法的 session ID。
     */
    public static String sanitizeSessionId(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        String id = raw.trim();
        id = id.replaceAll(".*[\\\\/]", "");
        id = id.replaceAll("\\.(txt|doc|docx|json|aadl|xml|csv|md)$", "");
        id = id.replaceAll("^(requirements|architecture|modules)_", "");
        id = id.replaceAll("-(architecture|modules|aadl)$", "");
        if (id.length() > 50) {
            id = id.substring(0, 50);
        }
        id = id.replaceAll("[^a-zA-Z0-9\\u4e00-\\u9fa5_-]+", "_");
        id = id.replaceAll("^_+|_+$", "");
        return id.isEmpty() ? null : id;
    }

    public boolean exists(String sessionId) {
        return sessions.containsKey(sessionId);
    }

    /**
     * 获取会话完整状态
     */
    public SessionState getSessionState(String sessionId) {
        return sessions.get(sessionId);
    }

    // ==================== 消息管理 ====================

    public void addMessage(String sessionId, ChatMessage message) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        state.recentMessages.add(message);
        state.lastActiveTime = LocalDateTime.now();

        // 检查是否需要触发摘要压缩
        if (state.recentMessages.size() > SUMMARY_TRIGGER_THRESHOLD) {
            compressHistory(state);
        }

        // 确保不超过最大限制
        while (state.recentMessages.size() > MAX_MESSAGES_PER_SESSION) {
            state.recentMessages.pollFirst();
        }

        log.debug("Added message to session {}, total messages: {}", sessionId, state.recentMessages.size());
    }

    public List<ChatMessage> getMessages(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(state.recentMessages);
    }

    /**
     * 构建对话上下文（包含历史摘要 + 最近消息）
     */
    public String buildContext(String sessionId, int maxMessages) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return "";
        }

        List<ChatMessage> messages = new ArrayList<>(state.recentMessages);
        if (messages.isEmpty() && (state.historySummary == null || state.historySummary.isEmpty())) {
            return "";
        }

        StringBuilder context = new StringBuilder();

        // 先放历史摘要
        if (state.historySummary != null && !state.historySummary.isEmpty()) {
            context.append("【历史摘要】\n");
            context.append(state.historySummary).append("\n\n");
        }

        // 再放最近消息
        int startIndex = Math.max(0, messages.size() - maxMessages);
        context.append("【最近对话】\n");

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

    /**
     * 压缩历史：将超过阈值的早期消息压缩为摘要文本
     */
    private void compressHistory(SessionState state) {
        int messagesToCompress = state.recentMessages.size() - SUMMARY_TRIGGER_THRESHOLD / 2;
        if (messagesToCompress <= 0) return;

        List<ChatMessage> oldMessages = new ArrayList<>();
        for (int i = 0; i < messagesToCompress && !state.recentMessages.isEmpty(); i++) {
            ChatMessage msg = state.recentMessages.pollFirst();
            if (msg != null) {
                oldMessages.add(msg);
            }
        }

        if (oldMessages.isEmpty()) return;

        // 生成摘要（规则驱动的压缩，不依赖 LLM）
        int nextSummaryIndex = state.summaryCount + 1;
        String newSummary = generateRuleBasedSummary(oldMessages, nextSummaryIndex);

        // 追加到已有摘要
        if (state.historySummary != null && !state.historySummary.isEmpty()) {
            state.historySummary = state.historySummary + "\n\n" + newSummary;
        } else {
            state.historySummary = newSummary;
        }

        state.summaryCount = nextSummaryIndex;
        log.info("Compressed session history: {} messages → summary (total summaries: {})",
                oldMessages.size(), state.summaryCount);
    }

    /**
     * 规则驱动的消息摘要（不依赖 LLM，避免额外开销）
     *
     * 提取关键信息：
     * - 用户的主要请求
     * - 助手的关键产出
     * - 重要的修正和确认
     */
    private String generateRuleBasedSummary(List<ChatMessage> messages, int summaryIndex) {
        StringBuilder summary = new StringBuilder();
        summary.append("[第").append(summaryIndex).append("段历史摘要]\n");

        int userCount = 0;
        int assistantCount = 0;
        List<String> keyPoints = new ArrayList<>();

        for (ChatMessage msg : messages) {
            if ("user".equals(msg.getRole())) {
                userCount++;
                // 提取用户输入的前50字作为关键点
                String content = msg.getContent();
                if (content != null && content.length() > 10) {
                    String point = "用户: " + content.substring(0, Math.min(50, content.length()));
                    if (content.length() > 50) point += "...";
                    keyPoints.add(point);
                }
            } else if ("assistant".equals(msg.getRole())) {
                assistantCount++;
                // 如果有 agentType，记录该阶段完成
                if (msg.getAgentType() != null && !msg.getAgentType().isEmpty()) {
                    keyPoints.add("助手完成 " + msg.getAgentType() + " 阶段");
                }
            }
        }

        summary.append("共 ").append(userCount).append(" 条用户消息, ")
                .append(assistantCount).append(" 条助手消息\n");
        summary.append("关键节点：\n");
        // 最多保留5个关键点
        int maxPoints = Math.min(5, keyPoints.size());
        for (int i = 0; i < maxPoints; i++) {
            // 均匀采样
            int idx = i * keyPoints.size() / maxPoints;
            summary.append("- ").append(keyPoints.get(idx)).append("\n");
        }

        return summary.toString();
    }

    // ==================== 决策记录 ====================

    /**
     * 添加决策记录
     */
    public void addDecision(String sessionId, DecisionRecord decision) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }

        if (decision.getDecisionId() == null) {
            decision.setDecisionId("DEC-" + UUID.randomUUID().toString().substring(0, 8));
        }
        if (decision.getTimestamp() == null) {
            decision.setTimestamp(LocalDateTime.now());
        }

        state.decisions.add(decision);
        state.lastActiveTime = LocalDateTime.now();
        log.debug("Added decision to session {}: {} - {}",
                sessionId, decision.getType(), decision.getDescription());
    }

    /**
     * 获取决策记录列表
     */
    public List<DecisionRecord> getDecisions(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return Collections.emptyList();
        }
        return new ArrayList<>(state.decisions);
    }

    // ==================== 工作记忆 ====================

    /**
     * 保存工作记忆快照
     */
    public void saveAgentContext(String sessionId, AgentContext context) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            log.warn("Session not found: {}", sessionId);
            return;
        }
        state.agentContext = context;
        state.lastActiveTime = LocalDateTime.now();
        log.debug("Saved agent context to session {}", sessionId);
    }

    /**
     * 获取工作记忆快照
     */
    public AgentContext getAgentContext(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state == null) {
            return null;
        }
        return state.agentContext;
    }

    // ==================== 会话管理 ====================

    public void clearSession(String sessionId) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.recentMessages.clear();
            state.historySummary = "";
            state.decisions.clear();
            state.agentContext = null;
            state.summaryCount = 0;
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

    /**
     * 获取历史摘要
     */
    public String getHistorySummary(String sessionId) {
        SessionState state = sessions.get(sessionId);
        return state != null ? state.historySummary : "";
    }

    /**
     * 手动设置历史摘要（如用 LLM 生成更好的摘要后）
     */
    public void setHistorySummary(String sessionId, String summary) {
        SessionState state = sessions.get(sessionId);
        if (state != null) {
            state.historySummary = summary;
        }
    }

    public void cleanupExpiredSessions(long maxIdleMinutes) {
        LocalDateTime expireTime = LocalDateTime.now().minusMinutes(maxIdleMinutes);
        sessions.entrySet().removeIf(entry -> {
            SessionState state = entry.getValue();
            if (state.lastActiveTime.isBefore(expireTime)) {
                log.info("Cleaning up expired session: {}", entry.getKey());
                return true;
            }
            if (state.recentMessages.isEmpty() && state.decisions.isEmpty()) {
                log.info("Cleaning up empty session: {}", entry.getKey());
                return true;
            }
            return false;
        });
    }
}
