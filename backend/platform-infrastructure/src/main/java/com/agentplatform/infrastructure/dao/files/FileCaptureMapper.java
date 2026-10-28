package com.agentplatform.infrastructure.dao.files;

import com.agentplatform.domain.files.*;
import java.util.UUID;
import org.apache.ibatis.annotations.*;

@Mapper
public interface FileCaptureMapper {
    UUID sourceUse(@Param("actor")FileData.Actor actor,@Param("operation")UUID operation);
    boolean parentAllowed(@Param("use")UUID use,@Param("locks")boolean locks);
    java.util.List<com.agentplatform.infrastructure.dao.po.FileCaptureRows.Reservation> reservations(@Param("actor")FileData.Actor actor,@Param("operation")UUID operation,@Param("locks")boolean locks);
    int bind(@Param("capture")UUID capture,@Param("operation")UUID operation);
    int bindSource(@Param("capture")UUID capture,@Param("id")UUID id);
    FileCaptureData.Target target(@Param("command")FileData.Command command);
    FileCaptureData.Descriptor descriptor(@Param("command")FileData.Command command);
    boolean mayEnter(@Param("operation")UUID operation);
    int describe(@Param("command")FileData.Command command,@Param("bytes")long bytes,@Param("hash")String hash);
    int createAsset(@Param("command")FileData.Command command);
    int stop(@Param("command")FileData.Command command,@Param("text")String text,@Param("hash")String hash);
    boolean assetMatches(@Param("command")FileData.Command command);
    int claimed(@Param("operation")UUID operation);
    boolean stoppedMatches(@Param("command")FileData.Command command,@Param("hash")String hash);
    int insertVersion(@Param("row")com.agentplatform.infrastructure.dao.po.FileCaptureRows.Projection row);
    int publishEntry(@Param("row")com.agentplatform.infrastructure.dao.po.FileCaptureRows.Projection row);
    int available(@Param("row")com.agentplatform.infrastructure.dao.po.FileCaptureRows.Projection row);
    int reference(@Param("row")com.agentplatform.infrastructure.dao.po.FileCaptureRows.Projection row);
    int settle(@Param("command")FileData.Command command,@Param("bytes")long bytes);
    int complete(@Param("row")com.agentplatform.infrastructure.dao.po.FileCaptureRows.Projection row);
    int refund(@Param("command")FileData.Command command);
    int failed(@Param("command")FileData.Command command);
    int reconciling(@Param("operation")UUID operation);
    int markDescendantsMissing(@Param("space")UUID space,@Param("workspace")UUID workspace,@Param("path")String path);
    int event(@Param("row")com.agentplatform.infrastructure.dao.po.FileCaptureRows.Projection row,@Param("id")UUID id,@Param("revision")long revision,@Param("json")String json);
}
