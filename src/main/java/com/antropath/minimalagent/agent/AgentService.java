package com.antropath.minimalagent.agent;

import org.springframework.stereotype.Service;

@Service
public class AgentService {


    private final Assistant assistant;

    public AgentService(Assistant assistant) {
        this.assistant = assistant;
    }

    public String answer(String task) {
        return assistant.chat(task);
    }
}
