package com.antropath.minimalagent.agent;

import com.antropath.minimalagent.api.AgentRequest;
import com.antropath.minimalagent.memory.ConversationMemoryService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final Assistant ragAssistant;
    private final Assistant toolAssistant;
    private final ConversationMemoryService conversationMemoryService;

    public AgentService(@Qualifier("ragAssistant") Assistant ragAssistant,
                        @Qualifier("toolAssistant") Assistant toolAssistant,
                        ConversationMemoryService conversationMemoryService) {
        this.ragAssistant = ragAssistant;
        this.toolAssistant = toolAssistant;
        this.conversationMemoryService = conversationMemoryService;
    }

    public String answer(AgentRequest request) {
        Assistant assistant = Boolean.FALSE.equals(request.useRag()) ? toolAssistant : ragAssistant;
        String answer = assistant.chat(request.userId(), request.task());
        conversationMemoryService.recordExchange(request.userId(), request.task(), answer);
        return answer;
    }
}
