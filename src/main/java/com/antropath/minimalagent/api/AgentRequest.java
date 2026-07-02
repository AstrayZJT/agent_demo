package com.antropath.minimalagent.api;

import jakarta.validation.constraints.NotBlank;

public record AgentRequest(
        @NotBlank(message = "userId must not be blank")
        String userId,

        Boolean useRag,

        @NotBlank(message = "task must not be blank")
        String task
) {
}
