package com.agentplatform.infrastructure.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.types.harness.HarnessData.*;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import io.agentscope.core.message.*;
import io.agentscope.core.model.*;
import io.agentscope.core.model.transport.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

public final class DeepSeekChatProvider implements ChatProvider {
    private final String endpoint;
    private final boolean real;
    public DeepSeekChatProvider(String endpoint,boolean fixture){
        URI uri=URI.create(endpoint);this.real=!fixture;
        if((!fixture&&!"https://api.deepseek.com".equals(endpoint))||(fixture&&(!"http".equals(uri.getScheme())||!List.of("fixture","127.0.0.1","localhost").contains(uri.getHost()))))throw new IllegalArgumentException("Provider endpoint is not allowlisted");
        this.endpoint=endpoint;
    }
    @Override public boolean real(){return real;}
    @Override public CompletionStage<Result> stream(String key,List<Message> input,Consumer<String> onText){
        var http=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        var transport=new JdkHttpTransport(http,HttpTransportConfig.defaults());
        var options=GenerateOptions.builder().stream(true).maxTokens(1024)
                .executionConfig(ExecutionConfig.builder().maxAttempts(1).retryOn(error->false).timeout(Duration.ofSeconds(120)).build())
                // AgentScope 2.0.4 emits stream_options.include_usage itself for
                // streaming. Repeating it as an extra body param creates duplicate JSON keys.
                .additionalBodyParam("thinking",java.util.Map.of("type","disabled")).build();
        var model=OpenAIChatModel.builder().apiKey(key).baseUrl(endpoint).endpointPath("/chat/completions").modelName("deepseek-flash")
                .httpTransport(transport).stream(true).generateOptions(options).build();
        StringBuilder text=new StringBuilder();AtomicReference<String> finish=new AtomicReference<>(),id=new AtomicReference<>();
        AtomicLong in=new AtomicLong(),out=new AtomicLong();AtomicBoolean usage=new AtomicBoolean();
        var result=new CompletableFuture<Result>();
        var subscription=model.stream(input.stream().map(m->Msg.builder().role(MsgRole.valueOf(m.role().name())).textContent(m.text()).build()).toList(),List.of(),options)
                .timeout(Duration.ofSeconds(120)).doFinally(signal->{transport.close();http.close();})
                .subscribe(response->{
                    if(response.getId()!=null)id.set(response.getId());if(response.getFinishReason()!=null)finish.set(response.getFinishReason());
                    for(var block:response.getContent()){
                        if(block instanceof TextBlock b&&!b.getText().isEmpty()){if(text.length()+b.getText().length()>65536)throw new ChatFailure(422,"MODEL_OUTPUT_LIMIT");text.append(b.getText());onText.accept(b.getText());}
                        else if(block instanceof ToolUseBlock)throw new ChatFailure(422,"MODEL_CAPABILITY_MISMATCH");
                        // Thinking blocks are deliberately not copied into any platform buffer.
                    }
                    if(response.getUsage()!=null){usage.set(true);in.set(response.getUsage().getInputTokens());out.set(response.getUsage().getOutputTokens());}
                },error->result.completeExceptionally(classify(error)),()->result.complete(new Result(new Round(text.toString(),List.of(),finish.get()==null?"unknown":finish.get(),in.get(),out.get()),usage.get(),id.get())));
        result.whenComplete((value,error)->{if(result.isCancelled())subscription.dispose();});return result;
    }
    private static RuntimeException classify(Throwable error){
        Integer status=null;boolean timeout=false;String lastType=error.getClass().getName();
        // Read typed status only. SDK messages/response bodies can contain secrets
        // and must never participate in logging or the public failure surface.
        for(Throwable cause=error;cause!=null;cause=cause.getCause()){
            if(cause instanceof ChatFailure failure)return failure;
            if(cause instanceof HttpTransportException http&&http.getStatusCode()!=null)status=http.getStatusCode();
            if(cause instanceof ModelHttpException http&&http.getStatusCode()!=null)status=http.getStatusCode();
            timeout|=cause.getClass().getSimpleName().contains("Timeout");lastType=cause.getClass().getName();
        }
        System.getLogger(DeepSeekChatProvider.class.getName()).log(System.Logger.Level.WARNING,
                "Provider failure type="+error.getClass().getName()+", cause="+lastType+", httpStatus="+status);
        if(status!=null&&status==429)return new ChatFailure(503,"PROVIDER_RATE_LIMITED");
        if(status!=null&&(status==401||status==403))return new ChatFailure(503,"PROVIDER_AUTH_REJECTED");
        if(status!=null&&status>=500&&status<=599)return new ChatFailure(503,"PROVIDER_SERVER_REJECTED");
        if(status!=null&&status>=400&&status<=499)return new ChatFailure(503,"PROVIDER_REQUEST_REJECTED");
        if(timeout)return new ChatFailure(504,"PROVIDER_TIMEOUT");
        return new ChatFailure(503,"PROVIDER_REQUEST_FAILED");
    }
}
