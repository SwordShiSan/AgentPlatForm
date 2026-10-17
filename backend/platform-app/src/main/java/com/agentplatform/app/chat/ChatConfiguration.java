package com.agentplatform.app.chat;

import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.harness.HarnessPorts.StateStorage;
import com.agentplatform.domain.health.ReadinessProbe;
import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.infrastructure.chat.*;
import com.agentplatform.infrastructure.storage.PostgresStateStorage;
import com.rabbitmq.client.ConnectionFactory;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.ObjectProvider;
import javax.sql.DataSource;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.*;

@Configuration(proxyBeanMethods=false) @Profile({"chat-local","chat-worker","chat-ops"})
public class ChatConfiguration {
    @Bean(destroyMethod="close") @Profile({"chat-local","chat-worker"})
    HikariDataSource chatDataSource(@Value("${platform.storage.jdbc-url}")String url,@Value("${platform.storage.user}")String user,@Value("${platform.storage.password-file}")String file)throws Exception{
        var config=new HikariConfig();config.setPoolName("chat-jdbc");config.setJdbcUrl(url);config.setUsername(user);config.setPassword(Files.readString(Path.of(file)).strip());
        config.setMaximumPoolSize(4);config.setMinimumIdle(1);config.setConnectionTimeout(2000);config.setValidationTimeout(1000);config.setIdleTimeout(120000);config.setMaxLifetime(600000);config.setInitializationFailTimeout(-1);
        return new HikariDataSource(config);
    }
    @Bean ChatRepository chatRepository(ObjectProvider<DataSource> pool,@Value("${platform.storage.jdbc-url}")String url,@Value("${platform.storage.user}")String user,@Value("${platform.storage.password-file}")String password)throws Exception{
        var source=pool.getIfAvailable();return source==null?new JdbcChatRepository(url,user,Files.readString(Path.of(password)).strip()):new JdbcChatRepository(source);
    }
    @Bean @Profile({"chat-local","chat-worker"})
    DispatchTransport chatTransport(ChatRepository repository,@Value("${platform.chat.mq-host}")String host,@Value("${platform.chat.mq-user}")String user,@Value("${platform.chat.mq-password-file}")String file)throws Exception{
        var factory=new ConnectionFactory();factory.setHost(host);factory.setVirtualHost("/agentplatform");factory.setUsername(user);factory.setPassword(Files.readString(Path.of(file)).strip());factory.setConnectionTimeout(3000);factory.setRequestedHeartbeat(15);factory.setAutomaticRecoveryEnabled(false);
        return new RabbitDispatchTransport(factory,repository);
    }
    @Bean @Profile("chat-local") ChatApi chatApi(ChatRepository repository,@Value("${platform.chat.cursor-key-file}")String file,CredentialCipher cipher)throws Exception{return new ChatService(repository,new ChatCursor(Files.readAllBytes(Path.of(file))),cipher);}
    @Bean @Profile("chat-local") ReadinessProbe chatReadiness(ChatRepository repository,IdentityStore identity,DispatchTransport transport){return ()->identity.ready()&&repository.ready()&&transport.connected();}
    @Bean @Profile({"chat-local","chat-worker","chat-ops"}) CredentialCipher credentialCipher(@Value("${platform.chat.master-key-file}")String file)throws Exception{return new CredentialCipher(Files.readAllBytes(Path.of(file)));}
    @Bean @Profile("chat-worker") ChatProvider chatProvider(@Value("${platform.chat.provider-base:https://api.deepseek.com}")String base,@Value("${platform.chat.fixture-provider:false}")boolean fixture){return new DeepSeekChatProvider(base,fixture);}
    @Bean @Profile("chat-worker") StateStorage chatState(DataSource pool){return new PostgresStateStorage(pool);}
    @Bean @Profile("chat-worker") ChatExecution chatExecution(ChatRepository repository,ChatProvider provider,CredentialCipher cipher,StateStorage storage,@Value("${platform.chat.workspace-root:/tmp/chat-runtime}")String root){return new ChatExecution(repository,provider,cipher,storage,Path.of(root));}
    @Bean(destroyMethod="shutdownNow") @Profile("chat-local")
    ScheduledExecutorService outboxRelay(ChatRepository repository,DispatchTransport transport){
        var timer=Executors.newSingleThreadScheduledExecutor();UUID relay=UUID.randomUUID();
        timer.scheduleWithFixedDelay(()->{try{repository.wakeDeferred();for(var item:repository.publications(relay)){boolean ok=false;try{transport.publish(item.envelope());ok=true;}catch(ChatFailure failed){}repository.published(relay,item,ok);}}catch(Exception error){System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Outbox relay waiting for dependency");}},0,500,TimeUnit.MILLISECONDS);return timer;
    }
    @Bean(destroyMethod="shutdownNow") @Profile("chat-worker")
    ScheduledExecutorService workerConsumer(ChatRepository repository,DispatchTransport transport,ChatExecution execution){
        var timer=Executors.newSingleThreadScheduledExecutor();UUID owner=UUID.randomUUID();
        timer.scheduleWithFixedDelay(()->{try{
            if(!repository.ready())throw new ChatFailure(503,"CHAT_STORAGE_UNAVAILABLE");
            transport.consume(delivery->{
                try{var lease=repository.claim(delivery.envelope(),owner);delivery.ack();if(lease!=null)execution.run(lease);}
                catch(ChatFailure error){if(error.status()==400){repository.quarantine(error.code());delivery.ack();}else {delivery.retryAfterDisconnect();System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,"Claim not acknowledged: "+error.code());}}
            });
            Files.writeString(Path.of("/tmp/chat-worker-ready"),owner.toString());
        }catch(Exception error){try{Files.deleteIfExists(Path.of("/tmp/chat-worker-ready"));}catch(Exception ignored){}}},0,2,TimeUnit.SECONDS);return timer;
    }
}
