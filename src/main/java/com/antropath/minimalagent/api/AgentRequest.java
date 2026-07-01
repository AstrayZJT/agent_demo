package com.antropath.minimalagent.api;

import jakarta.validation.constraints.NotBlank;

public record AgentRequest(
        @NotBlank(message = "task must not be blank")
        String task
) {
}
