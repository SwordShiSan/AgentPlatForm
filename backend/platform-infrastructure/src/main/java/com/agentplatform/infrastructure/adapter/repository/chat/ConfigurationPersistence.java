package com.agentplatform.infrastructure.adapter.repository.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.types.Uuid7;
import com.agentplatform.types.harness.HarnessData;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.agentplatform.infrastructure.dao.chat.ChatMappers;
import com.agentplatform.infrastructure.dao.po.ChatProjections.*;
import com.agentplatform.infrastructure.dao.po.ConfigurationRows.UserModelCredentialRow55;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Configuration persistence use cases over typed MyBatis collaborators. */
final class ConfigurationPersistence extends ChatPersistenceSupport {
    private final ContextPolicy contextPolicy;
    ConfigurationPersistence(ChatMappers mappers){this(mappers,ContextPolicy.legacy());}
    ConfigurationPersistence(ChatMappers mappers,ContextPolicy contextPolicy) { super(mappers);this.contextPolicy=contextPolicy; }

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
        auth(c,a);c.configuration().lockPersonalConfiguration(a.userId());if(!Set.of("defaultModelId","defaultCredentialSource","defaultPlanEnabled","defaultApprovalMode","workbenchLayout","recentFiles").containsAll(patch.keySet()))throw new ChatFailure(422,"UNSUPPORTED_PREFERENCE");
        c.configuration().insertUserPreferenceRevision165(a.userId());
        if(c.configuration().updateUserPreferenceRevision166(a.userId(), expected)!=1)throw new ChatFailure(412,"PREFERENCE_VERSION_CONFLICT");
        for(var entry:patch.entrySet()){
            Object value=entry.getValue();if("defaultModelId".equals(entry.getKey())){if(value!=null){UUID model;try{model=UUID.fromString((String)value);}catch(Exception invalid){throw new ChatFailure(422,"INVALID_MODEL");}validateModel(c,model);}}
            else if("defaultCredentialSource".equals(entry.getKey())){if(value!=null&&!Set.of("PLATFORM","USER").contains(value))throw new ChatFailure(422,"INVALID_CREDENTIAL_SOURCE");}
            else if("defaultPlanEnabled".equals(entry.getKey())){if(value!=null&&!(value instanceof Boolean))throw new ChatFailure(422,"INVALID_PLAN_MODE");}
            else if("defaultApprovalMode".equals(entry.getKey())){if(value!=null&&!Set.of("RISK_BASED","FULL").contains(value))throw new ChatFailure(422,"INVALID_PERMISSION_MODE");}
            else if("workbenchLayout".equals(entry.getKey())){try{value=JSON.convertValue(value,com.agentplatform.types.WorkbenchPreferences.Layout.class);if(value==null)throw new IllegalArgumentException();}catch(RuntimeException invalid){throw new ChatFailure(422,"INVALID_WORKBENCH_PREFERENCE");}}
            else {
                if(!(value instanceof List<?> list)||list.size()>20||c.attachments()==null)throw new ChatFailure(422,"INVALID_WORKBENCH_PREFERENCE");
                var recent=new ArrayList<com.agentplatform.types.WorkbenchPreferences.RecentFile>();
                for(var item:list){com.agentplatform.types.WorkbenchPreferences.RecentFile file;try{file=JSON.convertValue(item,com.agentplatform.types.WorkbenchPreferences.RecentFile.class);}catch(RuntimeException invalid){throw new ChatFailure(422,"INVALID_WORKBENCH_PREFERENCE");}
                    if(file==null||!c.attachments().recentFile(new com.agentplatform.domain.files.FileData.Actor(a.userId(),a.spaceId()),file))throw new ChatFailure(404,"RESOURCE_NOT_FOUND");recent.add(file);}
                if(recent.stream().distinct().count()!=recent.size())throw new ChatFailure(422,"INVALID_WORKBENCH_PREFERENCE");value=List.copyOf(recent);
            }
            if(value==null&&Set.of("defaultModelId","defaultCredentialSource","defaultPlanEnabled","defaultApprovalMode").contains(entry.getKey()))
                c.configuration().deleteUserPreference(a.userId(),entry.getKey());
            else c.configuration().insertUserPreference167(a.userId(), entry.getKey(), json(value), expected+1);
        }audit(c,a,"PREFERENCES_UPDATED",a.userId(),Uuid7.next());return preferences(c,a);
    });}
    public Map<String,Object> effective(Actor a){return tx(true,c->{auth(c,a);var p=preferences(c,a);var resolved=resolve(c,a,null,null);var values=Map.of("modelId",resolved.get("modelId").toString(),"credentialSource",resolved.get("credentialSource"));
        var result=new LinkedHashMap<String,Object>();result.put("values",values);result.put("sources",Map.of("modelId",p.get("defaultModelId")==null?"PLATFORM":"USER","credentialSource",preferenceDefined(c,a,"defaultCredentialSource")?"USER":"PLATFORM"));result.put("lockedFields",List.of());result.put("configHash",hash(json(new TreeMap<>(values))));
        result.put("context",Map.of("modelWindow",contextPolicy.modelWindow(),"window",contextPolicy.taskWindow(),"maxOutput",contextPolicy.maxOutput(),"safetyTokens",contextPolicy.safetyTokens(),"inputLimit",contextPolicy.inputLimit(),"estimation","SDK_MULTILINGUAL_ESTIMATE"));return result;
    });}
    public Map<String,Object> effective(Actor a,ConfigurationQuery query,
            com.agentplatform.infrastructure.dao.chat.WorkspaceConfigurationMapper workspaceMapper,ConfigurationResolver.Layer locked,
            boolean planEnabled,boolean permissionsEnabled){
        return tx(c->{
            auth(c,a);if(query==null||workspaceMapper==null)throw new ChatFailure(503,"CONFIGURATION_NOT_AVAILABLE");
            var defaults=c.configuration().selectModelDefinition12();
            if(defaults.isEmpty())throw new ChatFailure(503,"MODEL_UNAVAILABLE");
            var platform=new ConfigurationResolver.Layer(defaults.getFirst().value1(),ConfigurationResolver.CredentialSource.PLATFORM,false,com.agentplatform.types.TaskPermissionData.Mode.RISK_BASED);
            var scoped=new WorkspaceConfigurationRepository.Actor(a.userId(),a.spaceId());
            var workspaceRepository=new MyBatisWorkspaceConfigurationRepository(workspaceMapper);
            if(query.workspaceId()!=null){
                if(!workspaceRepository.authorized(scoped,query.workspaceId()))throw new ChatFailure(404,"RESOURCE_NOT_FOUND");
                workspaceRepository.lockConfiguration(scoped,query.workspaceId());
            } else c.configuration().lockPersonalConfiguration(a.userId());
            var preferences=preferences(c,a);
            @SuppressWarnings("unchecked") var overrides=(Map<String,Object>)preferences.get("overrides");
            var user=new ConfigurationResolver.Layer(overrides.get("defaultModelId")==null?null:UUID.fromString((String)overrides.get("defaultModelId")),
                    overrides.get("defaultCredentialSource")==null?null:ConfigurationResolver.CredentialSource.valueOf((String)overrides.get("defaultCredentialSource")),
                    (Boolean)overrides.get("defaultPlanEnabled"),overrides.get("defaultApprovalMode")==null?null:com.agentplatform.types.TaskPermissionData.Mode.valueOf((String)overrides.get("defaultApprovalMode")));
            ConfigurationResolver.Layer workspace=null;Long workspaceVersion=null;
            if(query.workspaceId()!=null){
                var state=workspaceRepository.find(scoped,query.workspaceId());workspaceVersion=state.version();var v=state.overrides();
                workspace=new ConfigurationResolver.Layer(v.modelId(),v.credentialSource(),v.planMode(),v.permissionMode());
            }
            var request=new ConfigurationResolver.Layer(query.modelId(),query.credentialSource(),query.planMode(),query.permissionMode());
            var resolved=ConfigurationResolver.resolve(platform,user,workspace,request,locked);
            Long modelVersion=c.configuration().modelVersion(resolved.modelId().value());
            var rows=modelVersion==null?List.<UserModelCredentialRow55>of():c.configuration().selectUserModelCredential55(a.spaceId(),a.userId(),resolved.modelId().value(),resolved.credentialSource().value()==ConfigurationResolver.CredentialSource.USER);
            var credential=rows.isEmpty()?null:rows.getFirst();
            var identity=new ResolvedTaskConfiguration(query.workspaceId(),resolved,modelVersion==null?0:modelVersion,
                    credential==null?null:credential.id(),credential==null?0:credential.revision(),
                    credential==null?"":credential.pricingVersion(),credential==null?0:credential.inputMicrosPerMillion(),credential==null?0:credential.outputMicrosPerMillion(),
                    "r61-policy-v1:plan="+planEnabled+":permissions="+permissionsEnabled);
            var blockers=new ArrayList<String>();if(modelVersion==null)blockers.add("MODEL_UNAVAILABLE");else if(credential==null)blockers.add("CREDENTIAL_SOURCE_UNAVAILABLE");
            if((resolved.planMode().value()&&!planEnabled)||((query.workspaceId()!=null||resolved.permissionMode().value()==com.agentplatform.types.TaskPermissionData.Mode.FULL)&&!permissionsEnabled))blockers.add("CAPABILITY_NOT_AVAILABLE");
            var result=new LinkedHashMap<String,Object>();
            result.put("values",Map.of("modelId",resolved.modelId().value().toString(),"credentialSource",resolved.credentialSource().value().name(),"planMode",resolved.planMode().value(),"permissionMode",resolved.permissionMode().value().name()));
            result.put("sources",Map.of("modelId",resolved.modelId().source().name(),"credentialSource",resolved.credentialSource().source().name(),"planMode",resolved.planMode().source().name(),"permissionMode",resolved.permissionMode().source().name()));
            result.put("lockedFields",resolved.lockedFields().stream().sorted().map(Enum::name).toList());result.put("configHash",identity.effectiveHash());
            result.put("canSubmit",blockers.isEmpty());result.put("blockers",blockers);result.put("preferenceRevision",preferences.get("version"));result.put("workspaceRevision",workspaceVersion);
            result.put("context",Map.of("modelWindow",contextPolicy.modelWindow(),"window",contextPolicy.taskWindow(),"maxOutput",contextPolicy.maxOutput(),"safetyTokens",contextPolicy.safetyTokens(),"inputLimit",contextPolicy.inputLimit(),"estimation","SDK_MULTILINGUAL_ESTIMATE"));
            return result;
        });
    }
    public Map<String,Object> configuration(Actor a,UUID id){return tx(true,c->{auth(c,a);task(c,a,id,false);
        { var rows10 = c.conversation().selectTask10(id, a.spaceId()); var row10 = rows10.isEmpty() ? null : rows10.getFirst();
            if(row10 == null)throw new ChatFailure(404,"RESOURCE_NOT_FOUND");var values=new TreeMap<>(object(row10.configJson()));
            values.keySet().retainAll(Set.of("modelId","modelName","modelVersion","credentialId","credentialRevision","credentialSource","window","maxOutput","modelWindow","contextSafety","contextPolicyVersion","stateBytes","checkpointBytes","thinking","pricingVersion","inputPrice","outputPrice","perCallMicros","bundleHash","workspaceId","permissionMode","planMode","resolution"));
            values.putIfAbsent("resolution",Map.of("resolverVersion","LEGACY"));
            var result=new LinkedHashMap<String,Object>();result.put("configSnapshotId",row10.id().toString());result.put("configHash",row10.configHash());result.put("bundleId",row10.bundleId().toString());result.put("values",values);result.put("restrictions",Map.of("credentialActive",row10.credentialActive(),"modelActive",row10.modelActive()));
            if(ContextPolicy.CURRENT_VERSION.equals(values.get("contextPolicyVersion"))||ContextPolicy.COMPACTION_VERSION.equals(values.get("contextPolicyVersion"))){var usage=new LinkedHashMap<String,Object>();usage.put("estimatedInputTokens",c.configuration().latestInputEstimate(id));usage.put("estimated",true);result.put("contextUsage",usage);}return result;
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
