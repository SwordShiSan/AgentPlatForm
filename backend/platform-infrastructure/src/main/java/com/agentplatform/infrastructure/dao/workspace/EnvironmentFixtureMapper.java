package com.agentplatform.infrastructure.dao.workspace;

import com.agentplatform.infrastructure.dao.po.EnvironmentFixtureScope;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EnvironmentFixtureMapper {
    EnvironmentFixtureScope scope();
}
