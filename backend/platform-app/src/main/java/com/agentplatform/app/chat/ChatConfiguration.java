package com.agentplatform.app.chat;

import com.agentplatform.infrastructure.adapter.repository.chat.MyBatisChatRepository;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.health.ReadinessProbe;
import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.infrastructure.chat.*;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false) @Profile({"chat-local","chat-worker","chat-ops"})
public class ChatConfiguration {
    @Bean com.agentplatform.infrastructure.dao.chat.ChatMappers chatMappers(
            com.agentplatform.infrastructure.dao.chat.LedgerMapper ledger,
            com.agentplatform.infrastructure.dao.chat.ConversationMapper conversation,
            com.agentplatform.infrastructure.dao.chat.DispatchMapper dispatch,
            com.agentplatform.infrastructure.dao.chat.AccountingMapper accounting,
            com.agentplatform.infrastructure.dao.chat.ConfigurationMapper configuration,
            com.agentplatform.infrastructure.dao.chat.EventMapper event) {
        return new com.agentplatform.infrastructure.dao.chat.ChatMappers(ledger, conversation, dispatch, accounting, configuration, event);
    }
    @Bean ChatRepository chatRepository(com.agentplatform.infrastructure.dao.chat.ChatMappers mappers,
            org.springframework.transaction.support.TransactionTemplate transactions) {
        return new TransactionalChatRepository(new MyBatisChatRepository(mappers), transactions);
    }
    @Configuration(proxyBeanMethods=false)
    @Profile({"chat-local","chat-worker"})
    @org.springframework.boot.context.properties.EnableConfigurationProperties(ChatMessagingProperties.class)
    static class MessagingConfiguration {
        @Bean(destroyMethod="destroy")
        org.springframework.amqp.rabbit.connection.CachingConnectionFactory chatConnections(ChatMessagingProperties properties) throws Exception {
            var connections = new org.springframework.amqp.rabbit.connection.CachingConnectionFactory(properties.mqHost());
            connections.setVirtualHost("/agentplatform");
            connections.setUsername(properties.mqUser());
            connections.setPassword(Files.readString(properties.mqPasswordFile()).strip());
            connections.setConnectionTimeout(3000);
            connections.setRequestedHeartBeat(15);
            return connections;
        }
        @Bean(destroyMethod="close")
        DispatchTransport chatTransport(ChatRepository repository, org.springframework.amqp.rabbit.connection.CachingConnectionFactory connections) {
            return new SpringAmqpDispatchTransport(connections,repository);
        }
    }
    @Bean @Profile("chat-local") ChatApi chatApi(ChatRepository repository,@Value("${platform.chat.cursor-key-file}")String file,CredentialCipher cipher)throws Exception{return new ChatService(repository,new ChatCursor(Files.readAllBytes(Path.of(file))),cipher);}
    @Bean @Profile("chat-local") ReadinessProbe chatReadiness(ChatRepository repository,IdentityStore identity,DispatchTransport transport){return ()->identity.ready()&&repository.ready()&&transport.connected();}
    @Bean @Profile({"chat-local","chat-worker","chat-ops"}) CredentialCipher credentialCipher(@Value("${platform.chat.master-key-file}")String file)throws Exception{return new CredentialCipher(Files.readAllBytes(Path.of(file)));}
    @Bean @Profile("chat-worker") ChatProvider chatProvider(@Value("${platform.chat.provider-base:https://api.deepseek.com}")String base,@Value("${platform.chat.fixture-provider:false}")boolean fixture){return new DeepSeekChatProvider(base,fixture);}
    @Bean @Profile("chat-worker") ChatExecution chatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,DispatchTransport transport,
            @org.springframework.beans.factory.annotation.Qualifier("executionTimers") org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler timers,
            @Value("${platform.chat.workspace-root:/tmp/chat-runtime}")String root){return new ChatExecution(repository,provider,cipher,Path.of(root),timers.getScheduledExecutor());}
    static void handoff(ChatRepository repository,ChatExecution execution,UUID owner,DispatchTransport.Delivery delivery){
        // Different AMQP channels can dispatch concurrently during reconnect. Keep
        // claim/ACK/run cleanup serial inside one worker, in addition to the DB fence.
        synchronized(execution){
            try{var lease=repository.claim(delivery.envelope(),owner);if(lease!=null)FixtureFaults.pause("claim-before-ack");delivery.ack();if(lease!=null){FixtureFaults.pause("ack-before-model");execution.run(lease);}}
            catch(ChatFailure error){if(error.status()==400){repository.quarantine(error.code());delivery.ack();}else {delivery.retryAfterDisconnect();System.getLogger(ChatConfiguration.class.getName()).log(System.Logger.Level.WARNING,"Claim not acknowledged: "+error.code());}}
        }
    }
}
