package com.agentplatform.app.chat;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.chat.DispatchTransport.Delivery;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;

class WorkerHandoffTest {
    private Lease lease(UUID owner,long fence){return new Lease(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),owner,fence,UUID.randomUUID(),UUID.randomUUID(),1,UUID.randomUUID());}
    private Delivery delivery(Lease lease){var delivery=mock(Delivery.class);when(delivery.envelope()).thenReturn(new Envelope(1,UUID.randomUUID(),lease.spaceId(),lease.taskId(),1,"execution.ready",Instant.now(),null,lease.taskId()));return delivery;}

    @Test void reconnectCallbackCannotClaimWhilePriorCallbackStillCleansUp()throws Exception{
        ChatRepository repository=mock(ChatRepository.class);ChatExecution execution=mock(ChatExecution.class);UUID owner=UUID.randomUUID();
        when(execution.accepting()).thenReturn(true);
        Lease first=lease(owner,1),second=lease(owner,2);Delivery one=delivery(first),two=delivery(second);
        CountDownLatch entered=new CountDownLatch(1),finish=new CountDownLatch(1),secondDispatched=new CountDownLatch(1),secondClaimed=new CountDownLatch(1);
        when(repository.claim(one.envelope(),owner)).thenReturn(first);
        when(repository.claim(two.envelope(),owner)).thenAnswer(invocation->{secondClaimed.countDown();return second;});
        doAnswer(invocation->{entered.countDown();assertTrue(finish.await(5,TimeUnit.SECONDS));return null;}).when(execution).run(first);
        try(var callbacks=Executors.newFixedThreadPool(2)){
            var a=callbacks.submit(()->ChatConfiguration.handoff(repository,execution,owner,one));assertTrue(entered.await(5,TimeUnit.SECONDS));
            var b=callbacks.submit(()->{secondDispatched.countDown();ChatConfiguration.handoff(repository,execution,owner,two);});assertTrue(secondDispatched.await(5,TimeUnit.SECONDS));
            try{assertFalse(secondClaimed.await(200,TimeUnit.MILLISECONDS),"A reconnect must not durably claim the next attempt before the old callback exits");}
            finally{finish.countDown();}
            a.get(5,TimeUnit.SECONDS);b.get(5,TimeUnit.SECONDS);
        }
        assertEquals(0,secondClaimed.getCount());verify(one).ack();verify(two).ack();verify(execution).run(first);verify(execution).run(second);
    }
    @Test void unconfirmedStopRejectsNewDeliveryWithoutClaimOrAck(){
        ChatRepository repository=mock(ChatRepository.class);ChatExecution execution=mock(ChatExecution.class);UUID owner=UUID.randomUUID();
        var pending=delivery(lease(owner,1));when(execution.accepting()).thenReturn(false);
        ChatConfiguration.handoff(repository,execution,owner,pending);
        verify(pending).retryAfterDisconnect();verify(pending,never()).ack();verifyNoInteractions(repository);
    }
}
