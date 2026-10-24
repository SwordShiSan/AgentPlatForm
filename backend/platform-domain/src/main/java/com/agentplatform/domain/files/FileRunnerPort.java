package com.agentplatform.domain.files;

import com.agentplatform.domain.files.FileData.Command;
import com.agentplatform.domain.files.FileData.Receipt;

public interface FileRunnerPort {
    Receipt applyFile(Command command);
    Receipt queryFile(Command command);
}
