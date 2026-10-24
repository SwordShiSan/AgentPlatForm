package com.agentplatform.infrastructure.workspace;

import com.agentplatform.domain.workspace.*;
import com.agentplatform.domain.workspace.WorkspaceData.*;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Set;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.Flow;
import tools.jackson.databind.json.JsonMapper;

public final class SignedRunnerHttpClient implements RunnerControlPort,EnvironmentRunnerPort,EnvironmentUseRunnerPort,com.agentplatform.domain.files.FileRunnerPort,AutoCloseable {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final URI base;
    private final RunnerSignatures signatures;
    private final HttpClient client;
    private final Duration timeout;
    public SignedRunnerHttpClient(URI base,RunnerSignatures signatures) {
        this(base,signatures,Duration.ofSeconds(15));
    }
    SignedRunnerHttpClient(URI base,RunnerSignatures signatures,Duration timeout) {
        if(!"http".equals(base.getScheme())||!Set.of("runner","127.0.0.1","localhost").contains(base.getHost())
                ||base.getUserInfo()!=null||base.getQuery()!=null||base.getFragment()!=null
                ||base.getPath()!=null&&!base.getPath().isEmpty()&&!base.getPath().equals("/"))throw new IllegalArgumentException("Fixed internal runner origin required");
        if(timeout.isZero()||timeout.isNegative()||timeout.compareTo(Duration.ofSeconds(15))>0)throw new IllegalArgumentException("Bounded runner timeout required");
        this.base=base;this.signatures=signatures;this.timeout=timeout;
        client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }
    private static RunnerSignatures.Binding binding(Command c,String method,String path) {
        return new RunnerSignatures.Binding(method,path,c.commandId(),c.operationId(),c.spaceId(),c.workspaceId(),c.generation(),c.fence());
    }
    private Receipt request(Command command,boolean query) {
        String path="/internal/operations"+(query?"/"+command.operationId():"");
        byte[] body=query?new byte[0]:JSON.writeValueAsBytes(command);
        return exchange(binding(command,query?"GET":"POST",path),binding(command,"RESPONSE",path),body,query,Receipt.class);
    }
    private <T> T exchange(RunnerSignatures.Binding requestBinding,RunnerSignatures.Binding responseBinding,
            byte[] body,boolean query,Class<T> type) {
        String path=requestBinding.path();
        var request=HttpRequest.newBuilder(base.resolve(path)).timeout(timeout)
                .header("Authorization","Bearer "+signatures.sign(requestBinding,body))
                .header("Accept","application/json");
        if(query)request.GET();else request.header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        var pending=client.sendAsync(request.build(),info->new BoundedBody());
        try {
            var response=pending.get(timeout.toMillis(),TimeUnit.MILLISECONDS);
                byte[] bytes=response.body();
                if(response.statusCode()!=200||bytes.length>65536)throw new WorkspaceFailure(503,"RUNNER_RESULT_UNAVAILABLE");
                String proof=response.headers().firstValue("X-Runner-Signature").orElseThrow(()->new WorkspaceFailure(503,"RUNNER_RESULT_UNAUTHENTICATED"));
                signatures.verify(proof,responseBinding,bytes);
                return JSON.readValue(bytes,type);
        } catch(InterruptedException interrupted){pending.cancel(true);Thread.currentThread().interrupt();throw new WorkspaceFailure(503,"RUNNER_RESULT_UNKNOWN");}
        catch(ExecutionException|TimeoutException unavailable){pending.cancel(true);throw new WorkspaceFailure(503,"RUNNER_RESULT_UNKNOWN");}
    }
    /** A bounded JDK body subscriber: no unbounded buffering or header-only timeout. */
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> completed=new CompletableFuture<>();
        private final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody(){return completed;}
        @Override public void onSubscribe(Flow.Subscription subscription){this.subscription=subscription;subscription.request(1);}
        @Override public void onNext(List<ByteBuffer> chunks) {
            if(completed.isDone())return;
            for(var chunk:chunks) {
                if(chunk.remaining()>65536-bytes.size()) {subscription.cancel();completed.completeExceptionally(new IllegalStateException("Runner body exceeds limit"));return;}
                byte[] part=new byte[chunk.remaining()];chunk.get(part);bytes.writeBytes(part);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable failure){completed.completeExceptionally(failure);}
        @Override public void onComplete(){completed.complete(bytes.toByteArray());}
    }
    @Override public Receipt provision(Command command){return request(command,false);}
    @Override public Receipt query(Command command){return request(command,true);}
    private com.agentplatform.domain.files.FileData.Receipt file(com.agentplatform.domain.files.FileData.Command command,boolean query) {
        String path="/internal/file-operations/v1"+(query?"/"+command.operation().id():"");var op=command.operation();
        byte[] body=query?new byte[0]:JSON.writeValueAsBytes(command);
        var request=new RunnerSignatures.Binding(query?"GET":"POST",path,op.commandId(),op.id(),op.spaceId(),op.workspaceId(),op.generation(),op.fence());
        var response=new RunnerSignatures.Binding("RESPONSE",path,op.commandId(),op.id(),op.spaceId(),op.workspaceId(),op.generation(),op.fence());
        return exchange(request,response,body,query,com.agentplatform.domain.files.FileData.Receipt.class);
    }
    @Override public com.agentplatform.domain.files.FileData.Receipt applyFile(com.agentplatform.domain.files.FileData.Command command){return file(command,false);}
    @Override public com.agentplatform.domain.files.FileData.Receipt queryFile(com.agentplatform.domain.files.FileData.Command command){return file(command,true);}
    private com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt environment(
            com.agentplatform.domain.workspace.EnvironmentExecutionData.Command command,boolean query) {
        String path="/internal/operations/v2/environments"+(query?"/"+command.operationId():"");
        var requestBinding=new RunnerSignatures.Binding(query?"GET":"POST",path,command.commandId(),command.operationId(),command.spaceId(),command.workspaceId(),command.generation(),command.fence());
        var responseBinding=new RunnerSignatures.Binding("RESPONSE",path,command.commandId(),command.operationId(),command.spaceId(),command.workspaceId(),command.generation(),command.fence());
        return exchange(requestBinding,responseBinding,query?new byte[0]:JSON.writeValueAsBytes(command),query,
                com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt.class);
    }
    @Override public com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt prepareEnvironment(
            com.agentplatform.domain.workspace.EnvironmentExecutionData.Command command) {return environment(command,false);}
    @Override public com.agentplatform.domain.workspace.EnvironmentExecutionData.Receipt queryEnvironment(
            com.agentplatform.domain.workspace.EnvironmentExecutionData.Command command) {return environment(command,true);}
    private com.agentplatform.domain.workspace.EnvironmentUseData.Receipt use(
            com.agentplatform.domain.workspace.EnvironmentUseData.Command command,boolean query) {
        String path="/internal/workspace-uses/v1"+(query?"/"+command.id():"");
        var requestBinding=new RunnerSignatures.Binding(query?"GET":"POST",path,command.id(),command.id(),command.spaceId(),command.workspaceId(),command.generation(),command.fence());
        var responseBinding=new RunnerSignatures.Binding("RESPONSE",path,command.id(),command.id(),command.spaceId(),command.workspaceId(),command.generation(),command.fence());
        var receipt=exchange(requestBinding,responseBinding,query?new byte[0]:JSON.writeValueAsBytes(command),query,
                com.agentplatform.domain.workspace.EnvironmentUseData.Receipt.class);
        if(!command.id().equals(receipt.useId())||!Set.of("CLAIMED","RUNNING","COMMITTED","FAILED","UNKNOWN","STOPPED").contains(receipt.state())
                ||!Set.of("PENDING","KNOWN","UNKNOWN").contains(receipt.resultState())
                ||receipt.resultHash()!=null&&!receipt.resultHash().matches("[0-9a-f]{64}"))throw new WorkspaceFailure(503,"RUNNER_RECEIPT_MISMATCH");
        return receipt;
    }
    @Override public com.agentplatform.domain.workspace.EnvironmentUseData.Receipt executeUse(com.agentplatform.domain.workspace.EnvironmentUseData.Command command) {return use(command,false);}
    @Override public com.agentplatform.domain.workspace.EnvironmentUseData.Receipt queryUse(com.agentplatform.domain.workspace.EnvironmentUseData.Command command) {return use(command,true);}
    @Override public void close(){client.shutdownNow();}
}
