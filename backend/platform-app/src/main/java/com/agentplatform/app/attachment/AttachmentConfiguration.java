package com.agentplatform.app.attachment;

import com.agentplatform.api.files.AttachmentApi;
import com.agentplatform.domain.attachment.AttachmentRepository;
import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisAttachmentRepository;
import com.agentplatform.infrastructure.dao.files.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;

@Configuration(proxyBeanMethods=false)
@Profile({"workspace-api","workspace-worker"})
@ConditionalOnProperty(name="platform.attachments.enabled",havingValue="true")
public class AttachmentConfiguration {
    @Bean AttachmentRepository attachmentRepository(AttachmentMapper mapper,FileMapper files,TransactionTemplate transactions){
        return new TransactionalAttachmentRepository(new MyBatisAttachmentRepository(mapper,files),transactions);
    }
    @Bean @Profile("workspace-api") AttachmentApi attachmentApi(AttachmentRepository repository,AssetStore assets){return new AttachmentService(repository,assets);}
    @Bean @Profile("workspace-api") AttachmentSubmission attachmentSubmission(TaskAttachmentMapper mapper,TransactionTemplate transactions,
            com.agentplatform.domain.chat.ChatRepository chat,AssetStore assets,
            @org.springframework.beans.factory.annotation.Value("${platform.chat.context-v2.enabled:false}")boolean contextEnabled,
            @org.springframework.beans.factory.annotation.Value("${platform.chat.context.window-tokens:262144}")int window,
            @org.springframework.beans.factory.annotation.Value("${platform.chat.context.max-output-tokens:4096}")int output,
            @org.springframework.beans.factory.annotation.Value("${platform.workspace.tools.enabled:false}")boolean tools){
        if(!contextEnabled)throw new IllegalArgumentException("Attachments require the validated context-v2 bundle");
        return new AttachmentSubmission(mapper,transactions,new AttachmentAdmission(mapper,transactions,chat,assets,com.agentplatform.domain.chat.ContextPolicy.current(window,output),tools));
    }
}
