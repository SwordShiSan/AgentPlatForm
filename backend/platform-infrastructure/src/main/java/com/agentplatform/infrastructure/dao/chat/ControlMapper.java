package com.agentplatform.infrastructure.dao.chat;
import com.agentplatform.infrastructure.dao.po.ControlRows.*;
import com.agentplatform.domain.chat.ChatData.*;
import com.agentplatform.domain.chat.CancellationLedger.Command;
import com.agentplatform.domain.chat.ExecutionLedger.StoppedOwner;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.*;
@Mapper
public interface ControlMapper {
    ControlTaskRow taskState(@Param("task") UUID task);
    ControlCallRow call(@Param("id") UUID id);
    ControlCallRow lockedCall(@Param("id") UUID id);
    List<ControlCallRow> calls(@Param("task") UUID task, @Param("space") UUID space);
    String commandHash(@Param("id") UUID id);
    int stopCount(@Param("lease") Lease lease);
    int latestStopCount(@Param("task") UUID task);
    int attemptCount(@Param("task") UUID task);
    int unresolved(@Param("task") UUID task);
    int recordCancel(@Param("actor") Actor actor, @Param("task") UUID task, @Param("command") UUID command);
    int recordStop(@Param("lease") Lease lease, @Param("hash") String hash);
    int recordHostStop(@Param("proof") StoppedOwner proof, @Param("space") UUID space);
    int providerKind(@Param("call") UUID call, @Param("kind") String kind);
    int markUnconfirmed(@Param("task") UUID task);
    int revision(@Param("call") UUID call, @Param("expected") long expected);
    int release(@Param("call") UUID call, @Param("space") UUID space, @Param("user") UUID user);
    int notExecuted(@Param("call") UUID call);
    int settledCall(@Param("call") UUID call, @Param("input") long input, @Param("output") long output, @Param("cost") long cost, @Param("request") String request);
    int settledBudget(@Param("call") UUID call);
    int settledAccount(@Param("space") UUID space, @Param("user") UUID user, @Param("allowance") long allowance, @Param("tokens") long tokens, @Param("cost") long cost);
    int audit(@Param("command") Command command, @Param("space") UUID space, @Param("task") UUID task, @Param("requestHash") String requestHash, @Param("before") String before, @Param("after") String after);
}
