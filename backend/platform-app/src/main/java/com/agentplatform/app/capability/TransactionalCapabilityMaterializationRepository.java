package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.CapabilityMaterializationCommand;
import com.agentplatform.domain.capability.CapabilityMaterializationRepository;
import com.agentplatform.infrastructure.dao.capability.CapabilityMaterializationMapper;
import java.util.UUID;
import java.util.Objects;
import org.springframework.transaction.support.TransactionTemplate;

public final class TransactionalCapabilityMaterializationRepository implements CapabilityMaterializationRepository {
    private final CapabilityMaterializationRepository delegate;private final TransactionTemplate tx;
    public TransactionalCapabilityMaterializationRepository(CapabilityMaterializationMapper mapper,TransactionTemplate tx){
        this.delegate=new com.agentplatform.infrastructure.adapter.repository.capability.MyBatisCapabilityMaterializationRepository(mapper);
        this.tx=Objects.requireNonNull(tx);
    }
    @Override public boolean currentLease(CapabilityMaterializationCommand c){return tx.execute(s->delegate.currentLease(c));}
    @Override public boolean accept(CapabilityMaterializationCommand c,String h,String j){return tx.execute(s->delegate.accept(c,h,j));}
    @Override public Record find(UUID id){return tx.execute(s->delegate.find(id));}
    @Override public void committed(CapabilityMaterializationCommand c,String j,String h){tx.execute(s->{delegate.committed(c,j,h);return null;});}
    @Override public void failed(CapabilityMaterializationCommand c,String code,String j,String h){tx.execute(s->{delegate.failed(c,code,j,h);return null;});}
    @Override public void blocked(CapabilityMaterializationCommand c,String code,String j,String h){tx.execute(s->{delegate.blocked(c,code,j,h);return null;});}
}
