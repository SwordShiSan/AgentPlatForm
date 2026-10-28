package com.agentplatform.app.chat;

import com.agentplatform.app.workspace.EnvironmentProperties;
import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.files.*;
import com.agentplatform.domain.workspace.*;
import com.agentplatform.infrastructure.adapter.repository.chat.MyBatisWorkspaceToolJobs;
import com.agentplatform.infrastructure.dao.chat.WorkspaceToolJobMapper;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false) @Profile("chat-worker")
@ConditionalOnProperty(name="platform.workspace.tools.enabled",havingValue="true")
public class WorkspaceToolsConfiguration {
    @Bean WorkspaceToolJobs workspaceToolJobs(WorkspaceToolJobMapper mapper){return new MyBatisWorkspaceToolJobs(mapper);}
    @Bean(destroyMethod="shutdown") ExecutorService workspaceToolExecutor(){return new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(1),Thread.ofPlatform().name("workspace-tool-",0).factory(),new ThreadPoolExecutor.AbortPolicy());}
    @Bean WorkspaceToolRuntime workspaceToolRuntime(ChatRepository chat,FileRepository files,AssetStore assets,
            EnvironmentLifecycleRepository environments,EnvironmentUseRepository uses,WorkspaceShellRunnerPort runner,WorkspaceToolJobs jobs,
            TransactionTemplate transactions,@Qualifier("workspaceToolExecutor")ExecutorService executor,
            @Qualifier("executionTimers")org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler timers,EnvironmentProperties properties){
        return new WorkspaceToolsApplicationService(chat,files,assets,environments,uses,runner,jobs,transactions,executor,timers.getScheduledExecutor(),properties.imageId(),properties.policyHash());
    }
}
