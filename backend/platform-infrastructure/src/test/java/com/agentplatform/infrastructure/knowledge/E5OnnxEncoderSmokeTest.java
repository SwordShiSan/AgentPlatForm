package com.agentplatform.infrastructure.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named="r63.model.dir",matches=".+")
final class E5OnnxEncoderSmokeTest {
    @Test void pinnedOfflineModelProducesNormalized384DimensionalQueryAndPassageVectors() throws Exception {
        Path root=Path.of(System.getProperty("r63.model.dir"));
        try(var encoder=new E5OnnxEncoder(root.resolve("onnx/model_quantized.onnx"),root.resolve("tokenizer.json"))){
            assertEquals("f80102d3f2a1229f387d3c81909990d8945513e347b0eab049f7de3c6f98c193",encoder.modelSha256());
            assertEquals("0b44a9d7b51c3c62626640cda0e2c2f70fdacdc25bbbd68038369d14ebdf4c39",encoder.tokenizerSha256());
            float[] query=encoder.embed("query: 如何保留正在构建的旧索引？",true);
            float[] passage=encoder.embed("如何保留正在构建的旧索引？",false);
            assertEquals(384,query.length);assertEquals(384,passage.length);
            assertEquals(1d,norm(query),1e-4);assertEquals(1d,norm(passage),1e-4);
            assertTrue(encoder.tokenCount("query: 知识索引与上下文压缩")<512);
            assertThrows(IllegalArgumentException.class,()->encoder.embed("x ".repeat(4000),false));
        }
    }
    private static double norm(float[] vector){double sum=0;for(float value:vector)sum+=(double)value*value;return Math.sqrt(sum);}
}
