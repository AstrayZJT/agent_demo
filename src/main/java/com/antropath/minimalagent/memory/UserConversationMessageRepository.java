package com.antropath.minimalagent.memory;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface UserConversationMessageRepository extends JpaRepository<UserConversationMessage, Long> {

    List<UserConversationMessage> findTop12ByUserIdOrderByCreatedAtDesc(String userId);
}
