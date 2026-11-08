package com.agentplatform.infrastructure.adapter.repository.capability;

import com.agentplatform.domain.capability.CapabilityMaterializationCommand;
import com.agentplatform.domain.capability.CapabilityMaterializationRepository;
import com.agentplatform.infrastructure.dao.capability.CapabilityMaterializationMapper;
import java.util.UUID;
import java.util.Objects;

public final class MyBatisCapabilityMaterializationRepository implements CapabilityMaterializationRepository {
    private final CapabilityMaterializationMapper mapper;
    public MyBatisCapabilityMaterializationRepository(CapabilityMaterializationMapper mapper){this.mapper=Objects.requireNonNull(mapper);}
    @Override public boolean currentLease(CapabilityMaterializationCommand command){return mapper.currentLease(command);}
    @Override public boolean accept(CapabilityMaterializationCommand command,String hash,String json){return mapper.insert(command,hash,json)==1;}
    @Override public Record find(UUID operation){return mapper.find(operation);}
    @Override public void committed(CapabilityMaterializationCommand command,String json,String hash){
        if(mapper.committed(command,json,hash)!=1)throw new IllegalStateException("CAPABILITY_MATERIALIZATION_RECEIPT_CONFLICT");
    }
    @Override public void failed(CapabilityMaterializationCommand command,String code,String json,String hash){
        if(mapper.failed(command,code,json,hash)!=1)throw new IllegalStateException("CAPABILITY_MATERIALIZATION_FAILURE_CONFLICT");
    }
    @Override public void blocked(CapabilityMaterializationCommand command,String code,String json,String hash){
        if(mapper.blocked(command,code,json,hash)!=1)throw new IllegalStateException("CAPABILITY_MATERIALIZATION_BLOCK_CONFLICT");
    }
}
