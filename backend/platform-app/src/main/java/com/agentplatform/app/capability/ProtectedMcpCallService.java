package com.agentplatform.app.capability;

import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.CapabilityBindings.Selection;
import com.agentplatform.domain.capability.ConnectorCredentialRepository;
import com.agentplatform.domain.capability.McpVersionRepository;
import com.agentplatform.domain.chat.ChatData.Lease;
import com.agentplatform.domain.chat.ChatFailure;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.infrastructure.capability.McpDiscovery;
import com.agentplatform.infrastructure.capability.McpDiscoveryProcess;
import com.agentplatform.infrastructure.chat.CredentialCipher;
import com.agentplatform.types.harness.HarnessData;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Acceptance-only MCP invocation adapter. It accepts only the original SDK operation after the
 * platform has advanced that exact operation to RUNNING; it is deliberately not a Tool catalog.
 */
public final class ProtectedMcpCallService {
    private record Frozen(McpVersionRepository.Version version, McpVersionRepository.Tool tool,
                          McpVersionRepository.Activation activation,
                          com.agentplatform.domain.capability.ConnectorCiphertext credential) {}

    private final ChatRepository chat;
    private final CapabilityBindingService bindings;
    private final McpVersionRepository versions;
    private final ConnectorCredentialRepository credentials;
    private final CredentialCipher cipher;
    private final McpDiscoveryProcess process;
    private final TransactionTemplate transactions;
    private final String policyHash;

    public ProtectedMcpCallService(ChatRepository chat, CapabilityBindingService bindings,
            McpVersionRepository versions, ConnectorCredentialRepository credentials,
            CredentialCipher cipher, McpDiscoveryProcess process, TransactionTemplate transactions,
            String policyHash) {
        this.chat=Objects.requireNonNull(chat); this.bindings=Objects.requireNonNull(bindings);
        this.versions=Objects.requireNonNull(versions); this.credentials=Objects.requireNonNull(credentials);
        this.cipher=Objects.requireNonNull(cipher); this.process=Objects.requireNonNull(process);
        this.transactions=Objects.requireNonNull(transactions);
        if(policyHash==null||!policyHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("MCP_POLICY_INVALID");
        this.policyHash=policyHash;
    }

    public McpDiscovery.CallResult invoke(Lease lease, UUID workspaceId, UUID connectorId, UUID versionId,
            String toolName, String schemaSha256, Map<String,Object> arguments, HarnessData.Call operation) {
        if(lease==null||workspaceId==null||connectorId==null||versionId==null||toolName==null
                ||schemaSha256==null||arguments==null||operation==null
                ||!toolName.equals(operation.name())||!arguments.equals(operation.arguments()))
            throw new ChatFailure(400,"MCP_TOOL_CALL_INVALID");
        var frozen=transactions.execute(status -> authorize(lease,workspaceId,connectorId,versionId,
                toolName,schemaSha256,arguments,operation));
        String secret=null;
        McpDiscovery.Authentication authentication;
        try {
            if("NONE".equals(frozen.version().authKind())) authentication=new McpDiscovery.Authentication(McpDiscovery.AuthKind.NONE,null,null);
            else {
                if(frozen.credential()==null)throw new ChatFailure(403,"CONNECTOR_CREDENTIAL_REVOKED");
                secret=cipher.decryptConnector(frozen.credential());
                if("BEARER".equals(frozen.version().authKind())) authentication=new McpDiscovery.Authentication(McpDiscovery.AuthKind.BEARER,null,secret);
                else if("API_KEY".equals(frozen.version().authKind())) authentication=new McpDiscovery.Authentication(McpDiscovery.AuthKind.API_KEY,frozen.version().apiKeyHeader(),secret);
                else throw new ChatFailure(403,"MCP_AUTHENTICATION_UNAVAILABLE");
            }
            // Recheck immediately before crossing the network boundary. No database transaction
            // remains open while the bounded child performs MCP initialization or invocation.
            transactions.execute(status -> { authorize(lease,workspaceId,connectorId,versionId,
                    toolName,schemaSha256,arguments,operation); return null; });
            var result=process.invoke(frozen.version().endpoint(),authentication,toolName,schemaSha256,arguments);
            transactions.execute(status -> { authorize(lease,workspaceId,connectorId,versionId,
                    toolName,schemaSha256,arguments,operation); return null; });
            return result;
        } catch(com.agentplatform.domain.files.FileFailure failure) {
            throw new ChatFailure(failure.status(),failure.getMessage());
        } finally {
            // Java's HTTP header API requires an immutable String. Keep its lifetime within this
            // method and never include it in exceptions, diagnostics or toString projections.
            secret=null;
        }
    }

    private Frozen authorize(Lease lease,UUID workspaceId,UUID connectorId,UUID versionId,String toolName,
            String schemaSha256,Map<String,Object> arguments,HarnessData.Call operation) {
        chat.check(lease);
        if(!chat.toolRunning(lease,operation)||chat.operationDecision(lease,operation)!=HarnessData.Decision.ALLOW)
            throw new ChatFailure(403,"MCP_OPERATION_NOT_APPROVED");
        var actor=new Actor(lease.userId(),lease.spaceId());
        var permission=chat.permissions(new com.agentplatform.domain.chat.ChatData.Actor(lease.userId(),lease.spaceId()),lease.taskId());
        if(permission==null||!workspaceId.toString().equals(permission.workspaceId()))
            throw new ChatFailure(403,"MCP_TASK_SCOPE_REJECTED");
        var selection=new Selection(connectorId,versionId);
        var effective=bindings.effective(actor,workspaceId);
        if(!effective.selections().contains(selection)||effective.unavailable().contains(selection))
            throw new ChatFailure(403,"MCP_BINDING_UNAVAILABLE");
        var version=versions.find(actor,connectorId,versionId,false);
        if(version==null||!"PUBLISHED".equals(version.state())||!versions.eligible(actor,version,policyHash))
            throw new ChatFailure(409,"MCP_VERSION_UNAVAILABLE");
        var activation=versions.activation(actor,connectorId);
        if(activation==null||!versionId.equals(activation.enabledVersionId()))
            throw new ChatFailure(403,"MCP_VERSION_NOT_ENABLED");
        var tool=versions.tools(actor,connectorId,versionId).stream().filter(value->toolName.equals(value.name())).findFirst().orElse(null);
        if(tool==null||!schemaSha256.equals(tool.schemaSha256()))throw new ChatFailure(409,"MCP_TOOL_VERSION_STALE");
        ConnectorCredentialRepository.Use credentialUse=version.credentialId()==null?null:
                credentials.forUse(actor,version.credentialId(),version.credentialRevision());
        var credential=credentialUse==null?null:credentialUse.encrypted();
        if(!"NONE".equals(version.authKind())&&credential==null)throw new ChatFailure(403,"CONNECTOR_CREDENTIAL_REVOKED");
        return new Frozen(version,tool,activation,credential);
    }
}
