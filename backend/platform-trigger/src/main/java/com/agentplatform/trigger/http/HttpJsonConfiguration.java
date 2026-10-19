package com.agentplatform.trigger.http;

import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.cfg.CoercionAction;
import tools.jackson.databind.cfg.CoercionInputShape;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.type.LogicalType;

/** Configure Jackson's native coercion rules only for public HTTP JSON. SDK Jackson2 stays private. */
@Configuration(proxyBeanMethods = false)
public class HttpJsonConfiguration {
    @Bean JsonMapperBuilderCustomizer strictHttpScalars() { return HttpJsonConfiguration::configure; }
    static void configure(JsonMapper.Builder builder) {
        builder.withCoercionConfig(LogicalType.Textual, config -> {
            config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        });
        builder.withCoercionConfig(LogicalType.Boolean, config -> {
            config.setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Integer, CoercionAction.Fail);
            config.setCoercion(CoercionInputShape.Float, CoercionAction.Fail);
        });
    }
}
