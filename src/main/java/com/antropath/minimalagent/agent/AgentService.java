package com.antropath.minimalagent.agent;

import com.antropath.minimalagent.api.AgentRequest;
import com.antropath.minimalagent.memory.ConversationMemoryService;
import org.springframework.stereotype.Service;

@Service
public class AgentService {

    private final Assistant assistant;
    private final ConversationMemoryService conversationMemoryService;

    public AgentService(Assistant assistant, ConversationMemoryService conversationMemoryService) {
        this.assistant = assistant;
        this.conversationMemoryService = conversationMemoryService;
    }

    public String answer(AgentRequest request) {
        String answer = assistant.chat(request);
        conversationMemoryService.recordExchange(request.userId(), request.task(), answer);
        return answer;
    }
}
