package com.agentplatform.infrastructure.dao.files;

import com.agentplatform.domain.attachment.AttachmentData.*;
import com.agentplatform.domain.files.FileData.Actor;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface AttachmentMapper {
    record UsageRow(long objectLimitBytes,long usedBytes,long reservedBytes,long retainedBytes,String category,long categoryBytes,long objects) {}
    java.util.List<UsageRow> usage(@Param("actor") Actor actor);
    boolean authorized(@Param("actor") Actor actor);
    Upload upload(@Param("id") UUID id, @Param("lock") boolean lock);
    Upload request(@Param("actor") Actor actor, @Param("request") UUID request);
    Attachment attachment(@Param("id") UUID id, @Param("lock") boolean lock);
    int reserve(@Param("space") UUID space, @Param("bytes") long bytes);
    void insertAsset(@Param("row") Attachment row);
    void insertAttachment(@Param("row") Attachment row);
    void insertUpload(@Param("row") Upload row);
    void insertReservation(@Param("row") Upload row);
    int begin(@Param("id") UUID id, @Param("owner") UUID owner);
    boolean live(@Param("id") UUID id, @Param("owner") UUID owner);
    int finishTransfer(@Param("id") UUID id, @Param("owner") UUID owner, @Param("state") String state);
    int recoverVerified(@Param("id")UUID id,@Param("revision")long revision);
    int settle(@Param("id") UUID id);
    int reservationCommitted(@Param("id") UUID id);
    int assetAvailable(@Param("id") UUID id);
    int attachmentAvailable(@Param("id") UUID id);
    int uploadCommitted(@Param("id") UUID id);
}
