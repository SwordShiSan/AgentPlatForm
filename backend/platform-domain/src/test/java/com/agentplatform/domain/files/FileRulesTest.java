package com.agentplatform.domain.files;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileRulesTest {
    @Test void pathsHaveOneNfcIdentityAndRejectTraversalAndReservedTemporaryNames() {
        assertEquals("é/😀.txt", FileRules.path("e\u0301/😀.txt", false));
        for (String path : new String[]{"", "/etc/passwd", "a//b", "a/../b", "a/./b", "a\\b", "a/", ".ap-upload-x", "a\0b", "a".repeat(256),"\uD800x","\uDC00"})
            assertThrows(FileFailure.class, () -> FileRules.path(path, false), path);
        assertEquals("", FileRules.path("", true));
    }
}
