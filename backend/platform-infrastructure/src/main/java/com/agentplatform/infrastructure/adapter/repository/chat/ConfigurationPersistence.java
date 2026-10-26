package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.ChatProjections.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Configuration persistence use cases over typed MyBatis collaborators. */
final class ConfigurationPersistence extends ChatPersistenceSupport {
    ConfigurationPersistence(ChatMappers mappers) { super(mappers); }

    public List<Credential> recoveryCredentials(){return tx(c->{
        List<Credential> values=new ArrayList<>();
        { var rows21 = c.configuration().selectCredential21();
            for (var row21 : rows21)values.add(new Credential(row21.id(),row21.scopeType(),row21.spaceId(),row21.ownerUserId(),row21.ciphertext(),row21.nonce(),row21.keyVersion(),row21.revision()));
         }return List.copyOf(values);
    });}
    public UUID importCredential(Credential k){return tx(c->{c.configuration().insertCredential157(k.id(), k.scope(), k.spaceId(), k.ownerId(), k.ciphertext(), k.nonce(), k.keyVersion(), k.revision());return k.id();});}
    public void revokeCredential(UUID id){tx(c->{
        if(c.configuration().updateCredential158(id)==0){
            { var rows20 = c.configuration().selectCredential20(id); if(rows20.isEmpty())throw new ChatFailure(404,"RESOURCE_NOT_FOUND"); }
        }
        return null;
    });}
    public void bind(UUID model,Actor a,UUID credential,String source){tx(c->{auth(c,a);
        { var rows19 = c.configuration().selectCredential19(credential); var row19 = rows19.isEmpty() ? null : rows19.getFirst(); if(row19 == null||!source.equals(row19.value1())||("USER".equals(source)&&(!a.spaceId().equals(row19.value2())||!a.userId().equals(row19.value3()))))throw new ChatFailure(403,"CREDENTIAL_SCOPE_REJECTED"); }
        c.configuration().insertModelDefinition159(model);
        c.configuration().insertModelBinding160(a.spaceId(), a.userId(), model, credential, source);
        c.accounting().insertBudgetAccount161(a.spaceId(), a.userId());
        if("USER".equals(source))c.configuration().insertUserModelCredential162(a.spaceId(), a.userId(), credential);
        return null;
    });}
    public Map<String,Object> preferences(Actor a){return tx(c->{auth(c,a);return preferences(c,a);});}
    public Map<String,Object> preferences(Actor a,long expected,Map<String,Object> patch){return tx(c->{
        auth(c,a);if(!Set.of("defaultModelId","defaultCredentialSource").containsAll(patch.keySet()))throw new ChatFailure(422,"UNSUPPORTED_PREFERENCE");
        c.configuration().insertUserPreferenceRevision165(a.userId());
        if(c.configuration().updateUserPreferenceRevision166(a.userId(), expected)!=1)throw new ChatFailure(412,"PREFERENCE_VERSION_CONFLICT");
        for(var entry:patch.entrySet()){
            Object value=entry.getValue();if("defaultModelId".equals(entry.getKey())){if(value!=null){UUID model;try{model=UUID.fromString((String)value);}catch(Exception invalid){throw new ChatFailure(422,"INVALID_MODEL");}validateModel(c,model);}}
            else if(!Set.of("PLATFORM","USER").contains(value==null?"":value))throw new ChatFailure(422,"INVALID_CREDENTIAL_SOURCE");
            c.configuration().insertUserPreference167(a.userId(), entry.getKey(), json(value), expected+1);
        }audit(c,a,"PREFERENCES_UPDATED",a.userId(),Uuid7.next());return preferences(c,a);
    });}
    public Map<String,Object> effective(Actor a){return tx(true,c->{auth(c,a);var p=preferences(c,a);var resolved=resolve(c,a,null,null);var values=Map.of("modelId",resolved.get("modelId").toString(),"credentialSource",resolved.get("credentialSource"));
        return Map.of("values",values,"sources",Map.of("modelId",p.get("defaultModelId")==null?"PLATFORM":"USER","credentialSource",preferenceDefined(c,a,"defaultCredentialSource")?"USER":"PLATFORM"),"lockedFields",List.of(),"configHash",hash(json(new TreeMap<>(values))));
    });}
    public Map<String,Object> configuration(Actor a,UUID id){return tx(true,c->{auth(c,a);task(c,a,id,false);
        { var rows10 = c.conversation().selectTask10(id, a.spaceId()); var row10 = rows10.isEmpty() ? null : rows10.getFirst();
            if(row10 == null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");var values=new TreeMap<>(object(row10.configJson()));
            values.keySet().retainAll(Set.of("modelId","modelName","modelVersion","credentialId","credentialRevision","credentialSource","window","maxOutput","thinking","pricingVersion","inputPrice","outputPrice","perCallMicros","bundleHash","workspaceId","permissionMode"));
            return Map.of("configSnapshotId",row10.id().toString(),"configHash",row10.configHash(),"bundleId",row10.bundleId().toString(),"values",values,"restrictions",Map.of("credentialActive",row10.credentialActive(),"modelActive",row10.modelActive()));
         }
    });}
    public Map<String,Object> credentialMetadata(Actor a){return tx(c->{auth(c,a);return credentialMetadata(c,a);});}
    public Map<String,Object> replaceUserCredential(Actor a,long expected,Credential encrypted){return tx(c->{
        auth(c,a);if(encrypted!=null&&(!"USER".equals(encrypted.scope())||!a.spaceId().equals(encrypted.spaceId())||!a.userId().equals(encrypted.ownerId())))throw new ChatFailure(403,"CREDENTIAL_SCOPE_REJECTED");
        c.configuration().insertUserModelCredential168(a.spaceId(), a.userId());UUID prior;
        { var rows7 = c.configuration().selectUserModelCredential7(a.spaceId(), a.userId()); var row7 = rows7.isEmpty() ? null : rows7.getFirst(); if(row7.value2()!=expected)throw new ChatFailure(412,"CREDENTIAL_VERSION_CONFLICT");prior=row7.value1(); }
        if(prior!=null)c.configuration().updateCredential169(prior, a.spaceId(), a.userId());
        if(encrypted!=null)c.configuration().insertCredential170(encrypted.id(), a.spaceId(), a.userId(), encrypted.ciphertext(), encrypted.nonce(), encrypted.keyVersion(), encrypted.revision());
        c.configuration().updateUserModelCredential171(encrypted==null?null:encrypted.id(), a.spaceId(), a.userId());
        audit(c,a,encrypted==null?"USER_CREDENTIAL_REVOKED":"USER_CREDENTIAL_REPLACED",encrypted==null?a.userId():encrypted.id(),Uuid7.next());return credentialMetadata(c,a);
    });}
    public String modelName(Lease l){return tx(c->{owned(c,l,false);activeModel(c,l);return (String)frozen(c,l).get("modelName");});}
}
