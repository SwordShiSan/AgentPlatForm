package com.agentplatform.app.chat;

import com.agentplatform.types.PlanInteractionData.Decision;
import java.nio.file.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Select these three methods explicitly; shared inherited tests are not a second acceptance run. */
class ApprovalAnswerCrashPostgresTest extends ApprovalPostgresTest {
    @Test void processStopsBeforeAnswerThenOriginalCommandCanComplete()throws Exception { exercise("before-answer"); }
    @Test void processDiesDuringAnswerAndTransactionRollsBack()throws Exception { exercise("during-answer"); }
    @Test void processStopsAfterCommitAndOriginalCommandIsNotDuplicated()throws Exception { exercise("after-answer"); }

    private void exercise(String mode)throws Exception {
        var task=task();var calls=new AtomicInteger();var effects=new AtomicInteger();
        var provider=provider(calls,java.util.List.of(tool("crash-original-call"),done()));
        var pending=waiting(task,provider,effects);UUID command=UUID.randomUUID();
        assertEquals(0,count("SELECT count(*) FROM pf.execution_slot WHERE task_id IS NOT NULL"));
        assertEquals(1,count("SELECT count(*) FROM pf.execution_stop_receipt WHERE task_id='"+task.id()+"'"));
        if("during-answer".equals(mode)){
            sql("CREATE FUNCTION pf.r43_crash_answer_pause() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.task_id='"+task.id()+"'::uuid THEN PERFORM pg_sleep(3); END IF; RETURN NEW; END $$;"
                    +"CREATE TRIGGER r43_crash_answer_pause BEFORE INSERT ON pf.interaction_answer FOR EACH ROW EXECUTE FUNCTION pf.r43_crash_answer_pause()");
        }
        String executable=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        Path log=work.resolve(mode+"-answer-process.log");
        Process process=new ProcessBuilder(executable,"-Xmx160m","-cp",System.getProperty("java.class.path"),
                ApprovalAnswerCrashProbeProcess.class.getName(),mode,task.id().toString(),pending.interactionId().toString(),command.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try{
            if("during-answer".equals(mode)){
                boolean paused=false;long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
                while(System.nanoTime()<until&&process.isAlive()){
                    if(count("SELECT count(*) FROM pg_stat_activity WHERE application_name='r43-approval-crash-"+command+"' AND wait_event='PgSleep'")>0){paused=true;break;}
                    Thread.sleep(40);
                }
                assertTrue(paused,"The separate JVM must enter the actual answer transaction before being stopped; see "+log);
                process.destroyForcibly();assertTrue(process.waitFor(5,TimeUnit.SECONDS),"Answer client did not terminate");
                long disconnected=System.nanoTime()+TimeUnit.SECONDS.toNanos(8);
                while(System.nanoTime()<disconnected&&count("SELECT count(*) FROM pg_stat_activity WHERE application_name='r43-approval-crash-"+command+"'")>0)Thread.sleep(40);
                assertEquals(0,count("SELECT count(*) FROM pg_stat_activity WHERE application_name='r43-approval-crash-"+command+"'"),"Killed client's transaction must be gone before checking rollback");
            }else{
                assertTrue(process.waitFor(20,TimeUnit.SECONDS),"Answer crash probe timed out; see "+log);
                assertEquals("before-answer".equals(mode)?41:43,process.exitValue(),"Unexpected crash-probe result; see "+log);
            }
        }finally{
            if(process.isAlive()){process.destroyForcibly();process.waitFor(5,TimeUnit.SECONDS);}
            if("during-answer".equals(mode))sql("DROP TRIGGER r43_crash_answer_pause ON pf.interaction_answer;DROP FUNCTION pf.r43_crash_answer_pause()");
        }
        long committed="after-answer".equals(mode)?1:0;
        assertEquals(committed,count("SELECT count(*) FROM pf.interaction_answer WHERE task_id='"+task.id()+"'"));
        assertEquals(committed,count("SELECT count(*) FROM pf.interaction_resume WHERE task_id='"+task.id()+"'"));
        assertEquals(committed,count("SELECT count(*) FROM pf.event_outbox WHERE aggregate_id='"+task.id()+"' AND generation=2"));
        assertEquals("PREPARED",scalar("SELECT status FROM pf.tool_operation WHERE task_id='"+task.id()+"'"));
        var accepted=answer(task,pending,command,Decision.ALLOW_ONCE);
        assertEquals(accepted,answer(task,pending,command,Decision.ALLOW_ONCE));
        assertEquals(1,count("SELECT count(*) FROM pf.interaction_answer WHERE task_id='"+task.id()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.interaction_resume WHERE task_id='"+task.id()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.event_outbox WHERE aggregate_id='"+task.id()+"' AND generation=2"));
        run(claim(task),provider,effects);
        assertEquals("SUCCEEDED",repository.task(user,task.id()).status());assertEquals(1,effects.get());assertEquals(2,calls.get());
        assertEquals(1,count("SELECT count(*) FROM pf.tool_operation WHERE task_id='"+task.id()+"'"));
        assertEquals(1,count("SELECT count(*) FROM pf.tool_approval_consumption WHERE task_id='"+task.id()+"' AND outcome='ALLOW_ONCE'"));
    }
}
