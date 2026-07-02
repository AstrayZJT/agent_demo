package com.antropath.minimalagent.api;

import com.antropath.minimalagent.agent.AgentService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
    }

    @PostMapping("/run")
    public AgentResponse run(@Valid @RequestBody AgentRequest request) {
        return new AgentResponse(agentService.answer(request));
    }
}
