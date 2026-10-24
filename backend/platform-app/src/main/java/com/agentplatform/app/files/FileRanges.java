package com.agentplatform.app.files;

import com.agentplatform.domain.files.FileFailure;
import com.agentplatform.domain.files.FileRules.Range;
import org.springframework.http.HttpRange;

/** Spring owns HTTP parsing; the product supports exactly one bounded byte range. */
final class FileRanges {
    private FileRanges() {}
    static Range parse(String header,long bytes) {
        if(header==null)return null;
        try {
            var ranges=HttpRange.parseRanges(header);
            if(bytes==0||ranges.size()!=1)throw new IllegalArgumentException();
            long first=ranges.getFirst().getRangeStart(bytes),last=ranges.getFirst().getRangeEnd(bytes);
            if(first<0||first>=bytes||last<first)throw new IllegalArgumentException();
            return new Range(first,last,last-first+1);
        } catch(IllegalArgumentException invalid) {throw new FileFailure(416,"INVALID_FILE_RANGE");}
    }
}
