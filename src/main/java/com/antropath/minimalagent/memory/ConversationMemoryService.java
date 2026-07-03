package com.antropath.minimalagent.memory;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ConversationMemoryService {

    private static final int MAX_HISTORY_MESSAGES = 20;
    private static final int RECENT_RAW_MESSAGE_COUNT = 4;
    private static final int SUMMARY_CONTENT_LIMIT = 120;
    private static final int RECENT_CONTENT_LIMIT = 500;

    private final UserConversationMessageRepository repository;

    public ConversationMemoryService(UserConversationMessageRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public String buildMemoryContext(String userId) {
        if ("-1".equals(userId)) {
            return "匿名用户不保存历史对话记忆。";
        }

        List<UserConversationMessage> recentMessages = repository.findTop20ByUserIdOrderByCreatedAtDesc(userId);
        if (recentMessages.isEmpty()) {
            return "暂无历史对话记忆。";
        }

        Collections.reverse(recentMessages);
        int recentCount = Math.min(RECENT_RAW_MESSAGE_COUNT, recentMessages.size());
        int summaryCount = Math.max(0, recentMessages.size() - recentCount);
        List<UserConversationMessage> summaryMessages = recentMessages.subList(0, summaryCount);
        List<UserConversationMessage> rawMessages = recentMessages.subList(summaryCount, recentMessages.size());

        StringBuilder builder = new StringBuilder();
        if (!summaryMessages.isEmpty()) {
            builder.append("历史摘要：\n")
                    .append(buildSummary(summaryMessages))
                    .append('\n');
        }
        builder.append("最近对话原文：\n");
        for (UserConversationMessage message : rawMessages) {
            builder.append(message.getRole()).append("：")
                    .append(truncate(message.getContent(), RECENT_CONTENT_LIMIT))
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

    private static String buildSummary(List<UserConversationMessage> messages) {
        List<String> lines = new ArrayList<>();
        lines.add("共 " + messages.size() + " 条更早对话。");
        lines.addAll(messages.stream()
                .limit(MAX_HISTORY_MESSAGES)
                .map(message -> message.getRole() + "：" + truncate(message.getContent(), SUMMARY_CONTENT_LIMIT))
                .collect(Collectors.toList()));
        return String.join("\n", lines);
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
