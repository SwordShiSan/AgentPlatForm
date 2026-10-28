package com.agentplatform.app.chat;

import com.agentplatform.domain.chat.ChatData.Actor;
import com.agentplatform.types.PlanInteractionData.Answer;
import com.agentplatform.types.PlanInteractionData.Decision;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;

/** A separate answer client, not an execution owner. Credentials are read only from the test environment. */
public final class ApprovalAnswerCrashProbeProcess {
    private ApprovalAnswerCrashProbeProcess() {}
    public static void main(String[] args)throws Exception {
        if(args.length!=4||!Set.of("before-answer","during-answer","after-answer").contains(args[0]))throw new IllegalArgumentException("INVALID_CRASH_PROBE_ARGUMENTS");
        String mode=args[0];UUID task=UUID.fromString(args[1]),interaction=UUID.fromString(args[2]),command=UUID.fromString(args[3]);
        String url=System.getenv("R43_TEST_JDBC_URL");
        if(!FixtureDatabaseScope.r44(url)&&!"jdbc:postgresql://127.0.0.1:15456/r43_test?connectTimeout=2&socketTimeout=5".equals(url))throw new IllegalArgumentException("ISOLATED_R43_DATABASE_REQUIRED");
        if("before-answer".equals(mode))Runtime.getRuntime().halt(41);
        String password=Files.readString(Path.of(Objects.requireNonNull(System.getenv("R43_TEST_PASSWORD_FILE")))).strip();
        Actor actor;long version;
        try(var connection=DriverManager.getConnection(url,"postgres",password);
            var statement=connection.prepareStatement("SELECT t.creator_id,t.space_id,i.version FROM pf.task t JOIN pf.task_interaction i ON i.task_id=t.id AND i.space_id=t.space_id WHERE t.id=? AND i.id=? AND i.kind='TOOL_APPROVAL'")){
            statement.setObject(1,task);statement.setObject(2,interaction);
            try(var row=statement.executeQuery()){
                if(!row.next())throw new IllegalStateException("ORIGINAL_APPROVAL_REQUIRED");
                actor=new Actor(row.getObject(1,UUID.class),row.getObject(2,UUID.class));version=row.getLong(3);
            }
        }
        var config=new com.zaxxer.hikari.HikariConfig();config.setJdbcUrl(url);config.setUsername("postgres");config.setPassword(password);
        config.setMaximumPoolSize(2);config.setMinimumIdle(1);config.addDataSourceProperty("ApplicationName","r43-approval-crash-"+command);
        try(var pool=new com.zaxxer.hikari.HikariDataSource(config)){
            var repository=ChatPersistenceFixture.create(pool);
            repository.answer(actor,task,interaction,command,new Answer(version,Decision.ALLOW_ONCE,"",List.of()));
            if("after-answer".equals(mode))Runtime.getRuntime().halt(43);
            throw new IllegalStateException("DURING_ANSWER_PROBE_WAS_NOT_INTERRUPTED");
        }
    }
}
