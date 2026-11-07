package com.agentplatform.infrastructure.dao.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.ConnectorCredentialRepository.Metadata;
import com.agentplatform.domain.capability.ConnectorCredentialRepository.Revocation;
import com.agentplatform.domain.capability.ConnectorCiphertext;
import com.agentplatform.infrastructure.dao.po.ConnectorCredentialRequestRow;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConnectorCredentialMapper {
    boolean authorized(@Param("actor") Actor actor);
    void lock(@Param("actor") Actor actor);
    ConnectorCredentialRequestRow findRequest(@Param("actor") Actor actor, @Param("request") UUID request);
    Metadata find(@Param("actor") Actor actor, @Param("id") UUID id, @Param("lock") boolean lock);
    List<Metadata> list(@Param("actor") Actor actor, @Param("after") UUID after, @Param("limit") int limit);
    int insert(@Param("actor") Actor actor, @Param("request") UUID request, @Param("fingerprint") String fingerprint, @Param("credential") ConnectorCiphertext credential);
    Revocation revocation(@Param("actor") Actor actor, @Param("request") UUID request);
    int revoke(@Param("actor") Actor actor, @Param("id") UUID id, @Param("expected") long expected);
    int recordRevocation(@Param("actor") Actor actor, @Param("request") UUID request, @Param("id") UUID id, @Param("expected") long expected);
    int audit(@Param("actor") Actor actor, @Param("auditId") UUID auditId, @Param("id") UUID id, @Param("trace") UUID trace);
}
