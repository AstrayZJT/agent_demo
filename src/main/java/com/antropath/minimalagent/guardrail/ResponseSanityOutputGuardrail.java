package com.antropath.minimalagent.guardrail;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.guardrail.OutputGuardrail;
import dev.langchain4j.guardrail.OutputGuardrailRequest;
import dev.langchain4j.guardrail.OutputGuardrailResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class ResponseSanityOutputGuardrail implements OutputGuardrail {

    private static final Logger log = LoggerFactory.getLogger(ResponseSanityOutputGuardrail.class);

    @Override
    public OutputGuardrailResult validate(OutputGuardrailRequest request) {
        AiMessage aiMessage = request.responseFromLLM() == null ? null : request.responseFromLLM().aiMessage();
        String text = aiMessage == null ? "" : aiMessage.text();
        if (text == null || text.isBlank()) {
            log.warn("Empty model response blocked by output guardrail.");
            return reprompt(
                    "模型返回了空内容，请基于用户问题和已有上下文重新生成完整中文回答。",
                    "请直接输出完整中文回答，不要返回空内容。"
            );
        }
        if (text.trim().length() < 2) {
            return retry("模型输出过短，请补充成完整回答。");
        }
        return successWith(aiMessage);
    }
}
