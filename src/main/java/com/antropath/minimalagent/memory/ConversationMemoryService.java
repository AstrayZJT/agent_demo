package com.antropath.minimalagent.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

@Service
public class ConversationMemoryService {

    private final UserConversationMessageRepository repository;

    public ConversationMemoryService(UserConversationMessageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public String buildMemoryContext(String userId) {
        if ("-1".equals(userId)) {
            return "匿名用户不保存历史对话记忆。";
        }

        List<UserConversationMessage> recentMessages = repository.findTop12ByUserIdOrderByCreatedAtDesc(userId);
        if (recentMessages.isEmpty()) {
            return "暂无历史对话记忆。";
        }

        Collections.reverse(recentMessages);
        StringBuilder builder = new StringBuilder();
        for (UserConversationMessage message : recentMessages) {
            builder.append(message.getRole()).append("：")
                    .append(truncate(message.getContent(), 500))
                    .append('\n');
        }
        return builder.toString().trim();
    }

    @Transactional
    public void recordExchange(String userId, String task, String answer) {
        if ("-1".equals(userId)) {
            return;
        }
        repository.save(new UserConversationMessage(userId, "用户", task));
        repository.save(new UserConversationMessage(userId, "助手", answer));
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
