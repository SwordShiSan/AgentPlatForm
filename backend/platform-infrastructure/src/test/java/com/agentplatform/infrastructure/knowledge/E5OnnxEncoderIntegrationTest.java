package com.agentplatform.infrastructure.knowledge;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/** Runs against the immutable local artifacts when R63_MODEL_DIR is supplied. */
@EnabledIfEnvironmentVariable(named = "R63_MODEL_DIR", matches = ".+")
class E5OnnxEncoderIntegrationTest {
    @Test
    void loadsPinnedMultilingualModelAndReturnsNormalized384DimensionalEmbeddings() throws Exception {
        Path root = Path.of(System.getenv("R63_MODEL_DIR"));
        try (var encoder = new E5OnnxEncoder(root.resolve("onnx/model_quantized.onnx"), root.resolve("tokenizer.json"))) {
            assertEquals(KnowledgeModelIdentity.MODEL_SHA256, encoder.modelSha256());
            assertEquals(KnowledgeModelIdentity.TOKENIZER_SHA256, encoder.tokenizerSha256());
            assertTrue(encoder.tokenCount("query: multilingual retrieval 测试🙂") < 512);

            float[] query = encoder.embed("跨语言检索是否能找到 multilingual passages", true);
            float[] passage = encoder.embed("多语言语义检索测试 multilingual passages", false);
            assertEquals(384, query.length);
            assertEquals(384, passage.length);
            assertTrue(Arrays.stream(toDouble(query)).allMatch(Double::isFinite));
            assertTrue(Arrays.stream(toDouble(passage)).allMatch(Double::isFinite));
            assertEquals(1d, norm(query), 1e-4);
            assertEquals(1d, norm(passage), 1e-4);
            assertNotEquals(Arrays.toString(query), Arrays.toString(passage));

            float[] crossLanguageQuery = encoder.embed("What is the north star process for checking permissions?", true);
            float[] relevantChinesePassage = encoder.embed("北极星流程：先确认实时权限，再执行持久化修改。", false);
            float[] unrelatedPassage = encoder.embed("A recipe for baking sourdough bread in a wood oven.", false);
            double relevantDistance = cosineDistance(crossLanguageQuery, relevantChinesePassage);
            double unrelatedDistance = cosineDistance(crossLanguageQuery, unrelatedPassage);
            System.out.println("R63_E5_CROSS_LANGUAGE_DISTANCE=" + relevantDistance + " UNRELATED_DISTANCE=" + unrelatedDistance);
            assertTrue(relevantDistance < unrelatedDistance,
                    "The fixed cross-language relevant passage should rank ahead of the unrelated control");
        }
    }

    private static double[] toDouble(float[] values) {
        double[] result = new double[values.length];
        for (int i = 0; i < values.length; i++) result[i] = values[i];
        return result;
    }

    private static double norm(float[] values) {
        double sum = 0;
        for (float value : values) sum += (double) value * value;
        return Math.sqrt(sum);
    }

    private static double cosineDistance(float[] left, float[] right) {
        double dot = 0;
        for (int i = 0; i < left.length; i++) dot += (double) left[i] * right[i];
        return 1 - dot;
    }
}
