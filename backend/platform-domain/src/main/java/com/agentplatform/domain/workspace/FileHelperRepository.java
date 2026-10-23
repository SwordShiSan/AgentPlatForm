package com.agentplatform.domain.workspace;

import com.agentplatform.domain.workspace.EnvironmentUseData.Command;
import com.agentplatform.domain.workspace.FileHelperData.*;
import java.util.UUID;

/** Short guarded facts only. No container or filesystem I/O belongs in these transactions. */
public interface FileHelperRepository {
    void intent(Command command, UUID runner, Intent intent);
    Snapshot find(Command command, UUID observer);
    void created(Command command, UUID runner, Physical physical);
    void requestStart(Command command, UUID runner);
    void started(Command command, UUID runner, Physical physical);
    void recordExec(Command command, UUID runner, Physical physical);
    void stopped(Command command, UUID observer, Stopped stop);
    /** Read immutable retirement evidence; never grants execution authority. */
    Stopped retirement(Command command);
}
