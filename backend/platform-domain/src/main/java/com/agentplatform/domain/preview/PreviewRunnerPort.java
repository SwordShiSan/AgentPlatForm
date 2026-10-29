package com.agentplatform.domain.preview;

import com.agentplatform.domain.preview.PreviewData.*;

public interface PreviewRunnerPort {
    Receipt render(Command command);
    Receipt query(Command command);
}
