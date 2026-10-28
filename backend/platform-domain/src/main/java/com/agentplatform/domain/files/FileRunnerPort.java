package com.agentplatform.domain.files;

import com.agentplatform.domain.files.FileData.Command;
import com.agentplatform.domain.files.FileData.Receipt;

public interface FileRunnerPort {
    default Receipt captureFile(FileCaptureData.Command command){throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");}
    default Receipt queryCapture(Command command){throw new FileFailure(422,"CAPTURE_NOT_AVAILABLE");}
    Receipt applyFile(Command command);
    Receipt queryFile(Command command);
}
