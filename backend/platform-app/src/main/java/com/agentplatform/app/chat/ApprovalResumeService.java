package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.types.PlanInteractionData.*;
import java.util.Set;
import java.util.UUID;

/** Accepts an exact decision; the existing transactional ledger owns scheduling and consumption. */
public final class ApprovalResumeService {
    private final ChatRepository repository;
    public ApprovalResumeService(ChatRepository repository) { this.repository = repository; }
    public Accepted answer(Actor actor, UUID task, UUID interaction, UUID command, Answer answer) {
        if (!Set.of(Decision.ALLOW_ONCE, Decision.DENY).contains(answer.decision()))
            throw new ChatFailure(422, "INTERACTION_ANSWER_INVALID");
        return repository.answer(actor, task, interaction, command, answer);
    }
}
