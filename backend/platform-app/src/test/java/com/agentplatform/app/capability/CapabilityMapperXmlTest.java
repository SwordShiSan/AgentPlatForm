package com.agentplatform.app.capability;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import com.agentplatform.infrastructure.dao.typehandler.UuidTypeHandler;

class CapabilityMapperXmlTest {
    @Test void allApplicationMapperXmlLoadsWithoutConnectingToPostgres() throws IOException {
        var resources=new PathMatchingResourcePatternResolver().getResources("classpath*:mybatis/mapper/**/*.xml");
        assertFalse(resources.length==0,"Mapper XML resources must be packaged in the application");
        var configuration=new Configuration();
        configuration.getTypeHandlerRegistry().register(new UuidTypeHandler());
        for(Resource resource:resources) {
            try(var input=resource.getInputStream()) {
                new XMLMapperBuilder(input,configuration,resource.toString(),configuration.getSqlFragments()).parse();
            }
        }
    }
}
