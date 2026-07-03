package com.antropath.minimalagent.guardrail;

import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.guardrail.InputGuardrail;
import dev.langchain4j.guardrail.InputGuardrailRequest;
import dev.langchain4j.guardrail.InputGuardrailResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
public class PromptInjectionInputGuardrail implements InputGuardrail {

    private static final Logger log = LoggerFactory.getLogger(PromptInjectionInputGuardrail.class);

    private static final int MAX_INPUT_LENGTH = 4000;
    private static final Set<Pattern> SUSPICIOUS_PATTERNS = Set.of(
            Pattern.compile("(?i)ignore\\s+(all\\s+)?previous\\s+instructions"),
            Pattern.compile("(?i)system\\s+prompt"),
            Pattern.compile("(?i)developer\\s+message"),
            Pattern.compile("(?i)prompt\\s+injection"),
            Pattern.compile("(?i)jailbreak"),
            Pattern.compile("忽略(之前|以上).{0,12}(指令|规则|提示词)"),
            Pattern.compile("(泄露|展示|输出).{0,12}(系统提示词|提示词|prompt)"),
            Pattern.compile("扮演.{0,12}(系统|开发者)"),
            Pattern.compile("role\\s*:\\s*(system|developer)")
    );

    @Override
    public InputGuardrailResult validate(InputGuardrailRequest request) {
        String text = extractText(request.userMessage());
        if (text.isBlank()) {
            return fatal("输入不能为空。");
        }
        if (text.length() > MAX_INPUT_LENGTH) {
            return fatal("输入内容过长，请精简后再试。");
        }

        String normalized = text.replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        boolean suspicious = SUSPICIOUS_PATTERNS.stream().anyMatch(pattern -> pattern.matcher(normalized).find());
        if (suspicious) {
            log.warn("Prompt injection blocked: {}", text);
            return fatal("检测到疑似提示词注入或越权指令，请换一种方式描述你的问题。");
        }

        return success();
    }

    private static String extractText(UserMessage userMessage) {
        if (userMessage == null) {
            return "";
        }
        if (userMessage.hasSingleText()) {
            return userMessage.singleText();
        }
        return userMessage.contents().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .collect(Collectors.joining(" "))
                .trim();
    }
}
