package com.antropath.minimalagent.agent;

import com.antropath.minimalagent.api.AgentRequest;

public interface Assistant {

    String chat(AgentRequest request);
}
