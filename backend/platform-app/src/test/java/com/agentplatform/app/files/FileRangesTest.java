package com.agentplatform.app.files;

import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.files.FileRules.Range;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FileRangesTest {
    @Test void springRangesAreRestrictedToSingleBoundedAndSuffixAwareResponse() {
        assertEquals(new Range(2,4,3),FileRanges.parse("bytes=2-4",10));
        assertEquals(new Range(8,9,2),FileRanges.parse("bytes=-2",10));
        assertEquals(new Range(0,9,10),FileRanges.parse("bytes=-20",10));
        assertEquals(new Range(7,9,3),FileRanges.parse("bytes=7-",10));
        for(String header:new String[]{"bytes=10-","bytes=3-2","bytes=-0","bytes=1-2,4-5","bytes=999999999999999999999-","items=1-2"})
            assertEquals(416,assertThrows(FileFailure.class,()->FileRanges.parse(header,10)).status());
        assertThrows(FileFailure.class,()->FileRanges.parse("bytes=0-",0));
    }
}
