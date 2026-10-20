package com.agentplatform.app.chat;

import com.agentplatform.infrastructure.chat.DeepSeekChatProvider;
import com.agentplatform.types.harness.HarnessData.Message;
import com.agentplatform.types.harness.HarnessData.Role;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;

/** Real AgentScope OpenAI extension and local HTTP, never a supplier. */
@Timeout(15)
class ProviderTerminationHttpTest {
    private static final byte[] FRAME=("data: {\"id\":\"fixture-cancel\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"x\"},\"finish_reason\":null}]}\n\n").getBytes(StandardCharsets.UTF_8);

    @Test void cancellationClosesRealHttpAndDoesNotTreatFutureCancellationAsProof() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var serverThreads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(serverThreads);
        var disconnected=new CountDownLatch(1);var received=new CountDownLatch(1);var callbacks=new AtomicInteger();
        server.createContext("/chat/completions",exchange->{
            try {
                exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);
                for(int i=0;i<500;i++){exchange.getResponseBody().write(FRAME);exchange.getResponseBody().flush();Thread.sleep(10);}
            } catch(Exception expectedDisconnect){disconnected.countDown();} finally{exchange.close();}
        });server.start();
        try(var provider=new DeepSeekChatProvider("http://127.0.0.1:"+server.getAddress().getPort(),true)) {
            var call=provider.open("deepseek-flash","fixture-not-real",List.of(Message.text(Role.USER,"hello")),text->{callbacks.incrementAndGet();received.countDown();});
            assertTrue(received.await(5,TimeUnit.SECONDS));
            assertFalse(call.terminated().toCompletableFuture().isDone());
            call.cancel();assertTrue(call.result().toCompletableFuture().isCancelled());
            call.terminated().toCompletableFuture().get(5,TimeUnit.SECONDS);
            assertTrue(disconnected.await(5,TimeUnit.SECONDS));
            int after=callbacks.get();Thread.sleep(100);assertEquals(after,callbacks.get());
        } finally {server.stop(0);serverThreads.close();}
    }

    @Test void blockedTeardownFailsTheBarrierInsteadOfCertifyingFutureCancellation() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var serverThreads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(serverThreads);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        server.createContext("/chat/completions",exchange->{
            try {exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(FRAME);exchange.getResponseBody().flush();release.await(10,TimeUnit.SECONDS);}
            catch(Exception ignored){}finally{exchange.close();}
        });server.start();
        try(var provider=new DeepSeekChatProvider("http://127.0.0.1:"+server.getAddress().getPort(),true)) {
            var call=provider.open("deepseek-flash","fixture-not-real",List.of(Message.text(Role.USER,"hello")),text->{
                entered.countDown();boolean done=false;while(!done){try{done=release.await(10,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));call.cancel();
            var failure=assertThrows(ExecutionException.class,()->call.terminated().toCompletableFuture().get(5,TimeUnit.SECONDS));
            assertInstanceOf(TimeoutException.class,failure.getCause());
            assertTrue(call.result().toCompletableFuture().isCancelled());
        } finally {release.countDown();server.stop(0);serverThreads.close();}
    }

    @Test void blockedSdkCallbackPreventsTerminationConfirmationUntilItExits() throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var serverThreads=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(serverThreads);
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        server.createContext("/chat/completions",exchange->{
            try {exchange.getRequestBody().readAllBytes();exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,0);exchange.getResponseBody().write(FRAME);exchange.getResponseBody().flush();release.await(5,TimeUnit.SECONDS);}
            catch(Exception ignored){}finally{exchange.close();}
        });server.start();
        try(var provider=new DeepSeekChatProvider("http://127.0.0.1:"+server.getAddress().getPort(),true)) {
            var call=provider.open("deepseek-flash","fixture-not-real",List.of(Message.text(Role.USER,"hello")),text->{
                entered.countDown();boolean done=false;while(!done){try{done=release.await(5,TimeUnit.SECONDS);}catch(InterruptedException ignored){}}
            });
            assertTrue(entered.await(5,TimeUnit.SECONDS));call.cancel();
            Thread.sleep(100);assertFalse(call.terminated().toCompletableFuture().isDone(),"Disposed subscription is not proof that its callback exited");
            release.countDown();call.terminated().toCompletableFuture().get(5,TimeUnit.SECONDS);
        } finally {release.countDown();server.stop(0);serverThreads.close();}
    }
}
