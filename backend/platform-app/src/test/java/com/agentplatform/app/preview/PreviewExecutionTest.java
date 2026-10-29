package com.agentplatform.app.preview;

import com.agentplatform.domain.files.AssetStore;
import com.agentplatform.domain.preview.*;
import com.agentplatform.domain.preview.PreviewData.*;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class PreviewExecutionTest {
    @Test void lostPostResponseQueriesOriginalCommandAndPublishesOnlyDurableReceipt() {
        var repository=mock(PreviewRepository.class);var runner=mock(PreviewRunnerPort.class);var assets=mock(AssetStore.class);
        var c=new Command(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"a.pdf","PDF","AUTO","private-source",3,"a".repeat(64),"sha256:"+"b".repeat(64),"r53","c".repeat(64),1,UUID.randomUUID(),1,Instant.now().plusSeconds(10),"private-output");
        var receipt=new Receipt(c.commandId(),c.previewId(),1,1,UUID.randomUUID(),"FAILED",null,null,0,null,null,null,false,"PREVIEW_DOCUMENT_INVALID",false,new Stop(null,null,c.imageId(),null,true,true,true,Instant.now()));
        when(runner.render(c)).thenThrow(new IllegalStateException("Response lost"));when(runner.query(c)).thenReturn(receipt);
        var timer=Executors.newSingleThreadScheduledExecutor();
        try(var execution=new PreviewExecution(repository,runner,assets,timer)){execution.run(c);verify(runner,times(1)).render(c);verify(repository).complete(c,receipt);verifyNoInteractions(assets);}
        finally{timer.shutdownNow();}
    }
}
