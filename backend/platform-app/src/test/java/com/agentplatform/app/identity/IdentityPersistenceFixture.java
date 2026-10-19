package com.agentplatform.app.identity;

import com.agentplatform.domain.identity.IdentityStore;
import com.agentplatform.infrastructure.adapter.repository.identity.MyBatisIdentityStore;
import com.agentplatform.infrastructure.dao.identity.IdentityMapper;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;
import javax.sql.DataSource;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MyBatis/Spring adapter fixture; SQL assertions may still use JDBC in tests. */
public final class IdentityPersistenceFixture {
    private IdentityPersistenceFixture() {}
    public static IdentityStore create(String url, String user, String password) {
        return create(new DriverManagerDataSource(url, user, password));
    }
    public static IdentityStore create(DataSource source) {
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
            factory.setMapperLocations(new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/identity/*.xml"));
            var session = new SqlSessionTemplate(factory.getObject());
            return new TransactionalIdentityStore(new MyBatisIdentityStore(session.getMapper(IdentityMapper.class)),
                    new TransactionTemplate(new JdbcTransactionManager(source)));
        } catch (Exception invalidConfiguration) { throw new IllegalStateException(invalidConfiguration); }
    }
}
