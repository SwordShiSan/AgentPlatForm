package com.agentplatform.infrastructure.chat;

import com.agentplatform.domain.chat.*;
import com.agentplatform.domain.chat.ChatData.Envelope;
import com.rabbitmq.client.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

public final class RabbitDispatchTransport implements DispatchTransport {
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private final ConnectionFactory factory;
    private final ChatRepository repository;
    private Connection connection;
    private Channel publisher,consumer;
    private final AtomicBoolean returned=new AtomicBoolean();
    public RabbitDispatchTransport(ConnectionFactory factory,ChatRepository repository){this.factory=factory;this.repository=repository;}
    private synchronized void connect()throws Exception{
        if(connection==null||!connection.isOpen()){connection=factory.newConnection("agentplatform-chat");publisher=null;consumer=null;}
    }
    @Override public synchronized void publish(Envelope envelope){
        try{connect();if(publisher==null||!publisher.isOpen()){publisher=connection.createChannel();publisher.confirmSelect();publisher.addReturnListener((code,reply,exchange,key,props,body)->returned.set(true));}
            returned.set(false);publisher.basicPublish("ap.commands.v1","execution.ready",true,new AMQP.BasicProperties.Builder().deliveryMode(2).contentType("application/json").messageId(envelope.eventId().toString()).build(),JSON.writeValueAsBytes(envelope));
            publisher.waitForConfirmsOrDie(2500);if(returned.get())throw new ChatFailure(503,"DISPATCH_UNROUTABLE");
        }catch(Exception error){throw new ChatFailure(503,"DISPATCH_NOT_CONFIRMED");}
    }
    @Override public synchronized void consume(Consumer<Delivery> handler){
        try{connect();if(consumer!=null&&consumer.isOpen())return;consumer=connection.createChannel();consumer.basicQos(1);Channel current=consumer;
            current.basicConsume("ap.execution.v1",false,(tag,message)->{
                Envelope envelope;
                try{if(message.getBody().length>65536)throw new IllegalArgumentException();var node=JSON.readTree(message.getBody());if(!node.isObject()||node.size()!=9)throw new IllegalArgumentException();envelope=JSON.readValue(message.getBody(),Envelope.class);
                    if(envelope.eventId()==null||envelope.spaceId()==null||envelope.aggregateId()==null||envelope.payloadRef()==null||envelope.occurredAt()==null||envelope.kind()==null)throw new IllegalArgumentException();}
                catch(Exception invalid){try{repository.quarantine("INVALID_MESSAGE_SCHEMA");current.basicAck(message.getEnvelope().getDeliveryTag(),false);}catch(Exception dependency){current.abort();}return;}
                try{handler.accept(new Delivery(){public Envelope envelope(){return envelope;}public void ack(){try{current.basicAck(message.getEnvelope().getDeliveryTag(),false);}catch(Exception lost){/* Durable owner already accepted responsibility; redelivery is fenced by inbox. */}}
                    public void retryAfterDisconnect(){try{current.abort();}catch(Exception ignored){}}
                });}catch(RuntimeException dependency){current.abort();}
            },tag->{});
        }catch(Exception error){throw new ChatFailure(503,"DISPATCH_CONSUMER_UNAVAILABLE");}
    }
    @Override public synchronized boolean connected(){try{connect();return connection.isOpen();}catch(Exception unavailable){return false;}}
    @Override public synchronized void close(){try{if(connection!=null)connection.close();}catch(Exception ignored){}}
}
