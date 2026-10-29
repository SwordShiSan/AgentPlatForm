package com.agentplatform.app.files;

import com.agentplatform.api.files.FileApi;
import com.agentplatform.domain.files.*;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileRepository;
import com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileTransferPermit;
import com.agentplatform.infrastructure.dao.files.FileTransferMapper;
import com.agentplatform.infrastructure.dao.files.FileMapper;
import com.agentplatform.infrastructure.dao.files.FileCaptureMapper;
import com.agentplatform.infrastructure.dao.workspace.*;
import com.agentplatform.infrastructure.storage.*;
import java.net.URI;
import java.nio.file.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.transaction.support.TransactionTemplate;
import software.amazon.awssdk.auth.credentials.*;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration(proxyBeanMethods=false)
@Profile({"workspace-api","workspace-worker","workspace-runner"})
@ConditionalOnProperty(name={"platform.workspace.enabled","platform.workspace.files.enabled"},havingValue="true")
@EnableConfigurationProperties({FileConfiguration.Properties.class,FileExecutionProperties.class})
public class FileConfiguration {
    @Bean @Profile("workspace-api") com.agentplatform.api.files.FileMutationApi fileMutationApi(
            com.agentplatform.infrastructure.dao.files.FileMutationMapper mutations,FileMapper files,WorkspaceMapper workspaces,
            EnvironmentPreparationMapper environments,TransactionTemplate transactions,FileExecutionProperties execution,FileRepository repository,AssetStore assets,FileApi api) {
        return new FileMutationService(new com.agentplatform.infrastructure.adapter.repository.files.MyBatisFileMutationRepository(
                mutations,files,workspaces,environments,execution.helperImage(),execution.policyHash()),transactions,repository,assets,api);
    }
    @ConfigurationProperties("platform.files.assets")
    public record Properties(URI endpoint,String bucket,Path accessKeyFile,Path secretKeyFile) {
        public Properties {
            if(endpoint==null||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getQuery()!=null
                    ||!("http".equals(endpoint.getScheme())||"https".equals(endpoint.getScheme()))||!endpoint.getPath().isEmpty()
                    ||bucket==null||!bucket.matches("[a-z0-9][a-z0-9.-]{1,61}[a-z0-9]")||accessKeyFile==null||secretKeyFile==null)
                throw new IllegalArgumentException("Fixed private asset configuration required");
        }
    }
    @Bean(destroyMethod="close") S3Client fileS3Client(Properties properties) throws java.io.IOException {
        return S3Clients.create(properties.endpoint(),StaticCredentialsProvider.create(AwsBasicCredentials.create(
                Files.readString(properties.accessKeyFile()).strip(),Files.readString(properties.secretKeyFile()).strip())));
    }
    @Bean FileTransferPermit fileTransferPermit(FileTransferMapper mapper,TransactionTemplate transactions,org.springframework.core.env.Environment environment,
            @org.springframework.beans.factory.annotation.Qualifier("runnerInstanceNonce")org.springframework.beans.factory.ObjectProvider<java.util.UUID> instance) {
        String role=environment.acceptsProfiles(org.springframework.core.env.Profiles.of("workspace-runner"))?"RUNNER":
                environment.acceptsProfiles(org.springframework.core.env.Profiles.of("workspace-worker"))?"WORKER":"API";
        return new TransactionalFileTransferPermit(new MyBatisFileTransferPermit(mapper,role,"RUNNER".equals(role)?instance.getObject():java.util.UUID.randomUUID()),transactions);
    }
    @Bean AssetStore fileAssetStore(S3Client fileS3Client,Properties properties,FileTransferPermit permits,org.springframework.core.env.Environment environment) {
        return new SerialAssetStore(new S3AssetStore(fileS3Client,properties.bucket()),permits,environment.acceptsProfiles(org.springframework.core.env.Profiles.of("workspace-runner")));
    }
    @Bean @Profile({"workspace-api","workspace-worker"}) FileRepository fileRepository(FileMapper mapper,WorkspaceMapper workspaces,
            EnvironmentPreparationMapper environments,FileCaptureMapper captures,TransactionTemplate transactions,FileExecutionProperties execution) {
        return new TransactionalFileRepository(new MyBatisFileRepository(mapper,workspaces,environments,execution.helperImage(),execution.policyHash(),captures),transactions);
    }
    @Bean @Profile("workspace-api") FileApi fileApi(FileRepository repository,AssetStore assets,com.agentplatform.api.identity.IdentityApi identity) { return new FileApplicationService(repository,assets); }
}
