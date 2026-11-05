package com.agentplatform.domain.attachment;

import com.agentplatform.domain.files.FileData.Actor;
import com.agentplatform.domain.attachment.AttachmentData.*;
import java.util.UUID;

/** Each method is one short transaction. Object transfers occur outside this port. */
public interface AttachmentRepository {
    Upload create(Actor actor, UUID request, String hash, Create body);
    Upload upload(Actor actor, UUID id);
    Upload request(Actor actor, UUID request);
    Upload begin(Actor actor, UUID id, UUID owner);
    void guard(Actor actor, UUID id, UUID owner);
    void verified(Actor actor, UUID id, UUID owner);
    void uncertain(Actor actor, UUID id, UUID owner);
    void rejected(Actor actor,UUID id,UUID owner);
    Upload recoverVerified(Actor actor,UUID id,long revision);
    Attachment complete(Actor actor, UUID upload);
    Attachment get(Actor actor, UUID attachment);
    AttachmentData.Usage usage(Actor actor);
}
