package com.agentplatform.app.knowledge;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("platform.knowledge.model")
public record KnowledgeModelProperties(Path modelFile,Path tokenizerFile) {}
