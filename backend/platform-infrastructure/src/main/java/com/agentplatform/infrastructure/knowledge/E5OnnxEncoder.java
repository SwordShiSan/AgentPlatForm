package com.agentplatform.infrastructure.knowledge;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtSession;
import com.agentplatform.domain.knowledge.KnowledgeEncoder;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** CPU-only offline encoder. It never resolves model ids or downloads artifacts at runtime. */
public final class E5OnnxEncoder implements KnowledgeEncoder {
    private static final int MAX_TOKENS=512;
    private final OrtEnvironment environment;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final String modelHash;
    private final String tokenizerHash;

    public E5OnnxEncoder(Path model,Path tokenizerFile) throws Exception {
        if(model==null||tokenizerFile==null||!Files.isRegularFile(model)||!Files.isRegularFile(tokenizerFile))
            throw new IllegalArgumentException("Pinned offline knowledge artifacts are missing");
        modelHash=sha256(model);tokenizerHash=sha256(tokenizerFile);
        if(!KnowledgeModelIdentity.MODEL_SHA256.equals(modelHash)||!KnowledgeModelIdentity.TOKENIZER_SHA256.equals(tokenizerHash))
            throw new IllegalArgumentException("Pinned knowledge artifact checksum mismatch");
        environment=OrtEnvironment.getEnvironment();
        var options=new OrtSession.SessionOptions();options.setIntraOpNumThreads(1);options.setInterOpNumThreads(1);
        options.setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL);
        session=environment.createSession(model.toString(),options);
        tokenizer=HuggingFaceTokenizer.builder().optTokenizerPath(tokenizerFile).optTruncation(false).build();
        Set<String> names=session.getInputNames();
        if(!names.contains("input_ids")||!names.contains("attention_mask")||session.getOutputNames().isEmpty())
            throw new IllegalArgumentException("Unexpected E5 ONNX input/output contract");
    }

    @Override public int tokenCount(String text){return encode(text).getIds().length;}
    @Override public synchronized float[] embed(String text,boolean query){
        long[] ids=encode((query?"query: ":"passage: ")+text).getIds();
        if(ids.length>MAX_TOKENS)throw new IllegalArgumentException("KNOWLEDGE_CHUNK_TOKEN_LIMIT");
        long[] mask=new long[ids.length];java.util.Arrays.fill(mask,1L);long[] types=new long[ids.length];
        Map<String,OnnxTensor> inputs=new HashMap<>();
        try {
            inputs.put("input_ids",OnnxTensor.createTensor(environment,new long[][]{ids}));
            inputs.put("attention_mask",OnnxTensor.createTensor(environment,new long[][]{mask}));
            if(session.getInputNames().contains("token_type_ids"))inputs.put("token_type_ids",OnnxTensor.createTensor(environment,new long[][]{types}));
            try(OrtSession.Result result=session.run(inputs)){
                OnnxTensor output=(OnnxTensor)result.get(0);
                long[] shape=output.getInfo().getShape();FloatBuffer buffer=output.getFloatBuffer();
                float[] pooled=new float[384];
                if(shape.length==2&&shape[0]==1&&shape[1]==384){buffer.get(pooled);}
                else if(shape.length==3&&shape[0]==1&&shape[2]==384&&shape[1]==ids.length){
                    for(int token=0;token<ids.length;token++)for(int dim=0;dim<384;dim++)pooled[dim]+=buffer.get(token*384+dim);
                    float denom=ids.length;for(int d=0;d<384;d++)pooled[d]/=denom;
                } else throw new IllegalStateException("Unexpected E5 output tensor shape");
                double norm=0;for(float x:pooled)norm+=(double)x*x;if(norm==0||!Double.isFinite(norm))throw new IllegalStateException("Invalid E5 embedding");
                float scale=(float)(1d/Math.sqrt(norm));for(int i=0;i<pooled.length;i++){pooled[i]*=scale;if(!Float.isFinite(pooled[i]))throw new IllegalStateException("Invalid E5 embedding");}
                return pooled;
            }
        }catch(Exception failure){throw failure instanceof RuntimeException runtime?runtime:new IllegalStateException("E5 inference failed",failure);}
        finally{inputs.values().forEach(OnnxTensor::close);}
    }
    private Encoding encode(String text){Encoding encoded=tokenizer.encode(text);if(encoded.exceedMaxLength()||encoded.getIds().length>MAX_TOKENS)throw new IllegalArgumentException("KNOWLEDGE_CHUNK_TOKEN_LIMIT");return encoded;}
    @Override public String modelSha256(){return modelHash;}
    @Override public String tokenizerSha256(){return tokenizerHash;}
    @Override public void close(){try{tokenizer.close();}finally{try{session.close();}catch(Exception ignored){}}}
    private static String sha256(Path file)throws Exception{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(file)){byte[] block=new byte[65536];int n;while((n=input.read(block))>=0)if(n>0)digest.update(block,0,n);}return HexFormat.of().formatHex(digest.digest());}
}
