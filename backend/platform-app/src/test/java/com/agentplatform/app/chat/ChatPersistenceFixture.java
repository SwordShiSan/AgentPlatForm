package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.infrastructure.adapter.repository.chat.MyBatisChatRepository;
import com.agentplatform.infrastructure.dao.chat.*;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class ChatPersistenceFixture {
    private ChatPersistenceFixture() {}
    public static ChatRepository create(DataSource source) {
        return create(source,false);
    }
    public static ChatRepository create(DataSource source,boolean workspaceTools) {
        try {
            var factory = new SqlSessionFactoryBean();
            factory.setDataSource(source);
            var configuration = new org.apache.ibatis.session.Configuration();
            configuration.setMapUnderscoreToCamelCase(true);
            configuration.setLocalCacheScope(org.apache.ibatis.session.LocalCacheScope.STATEMENT);
            configuration.setCacheEnabled(false);
            configuration.setDefaultStatementTimeout(5);
            configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());
            factory.setConfiguration(configuration);
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/chat/*.xml"));
            var session = new SqlSessionTemplate(factory.getObject());
            var mappers = new ChatMappers(session.getMapper(LedgerMapper.class), session.getMapper(ConversationMapper.class),
                    session.getMapper(DispatchMapper.class), session.getMapper(AccountingMapper.class), session.getMapper(ConfigurationMapper.class), session.getMapper(EventMapper.class), session.getMapper(ControlMapper.class), session.getMapper(RecoveryMapper.class), session.getMapper(QuotaMapper.class), session.getMapper(ExecutionPolicyMapper.class),session.getMapper(PlanMapper.class),session.getMapper(PermissionMapper.class),session.getMapper(ApprovalMapper.class),session.getMapper(WorkspaceToolMapper.class));
            return new TransactionalChatRepository(new MyBatisChatRepository(mappers,workspaceTools), new TransactionTemplate(new JdbcTransactionManager(source)));
        } catch (Exception invalidConfiguration) { throw new IllegalStateException(invalidConfiguration); }
    }
}
