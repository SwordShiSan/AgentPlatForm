package com.agentplatform.infrastructure.dao.files;

import com.agentplatform.domain.files.FileData.*;
import com.agentplatform.domain.files.FileMutationData.*;
import com.agentplatform.domain.files.FileMutationData.Input;
import java.util.List;
import java.util.UUID;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface FileMutationMapper {
    boolean active(@Param("id")UUID id);
    List<Entry> tree(@Param("space")UUID space,@Param("workspace")UUID workspace,@Param("path")String path);
    Intent request(@Param("actor")Actor actor,@Param("request")UUID request);
    void intent(@Param("row")Intent row);
    void item(@Param("operation")UUID operation,@Param("space")UUID space,@Param("workspace")UUID workspace,
            @Param("ordinal")int ordinal,@Param("row")Item item);
    Input input(@Param("operation")UUID operation);
    List<Item> items(@Param("operation")UUID operation);
    int moveEntry(@Param("operation")UUID operation,@Param("row")Item item);
    void restoreUpload(@Param("row")Restore row);
    Intent intentById(@Param("id")UUID id);
    List<Entry> deletedTree(@Param("space")UUID space,@Param("workspace")UUID workspace,@Param("deletion")UUID deletion);
    void treeAsset(@Param("row")TreeFile row,@Param("actor")UUID actor);
    void treeItem(@Param("row")TreeFile row);
    void treeReservation(@Param("operation")UUID operation,@Param("space")UUID space,@Param("bytes")long bytes);
    void treeIntent(@Param("row")Intent row,@Param("deletion")UUID deletion);
    List<Deleted> deleted(@Param("space")UUID space,@Param("workspace")UUID workspace,@Param("before")UUID before,@Param("limit")int limit);
    Preview preview(@Param("space")UUID space,@Param("file")UUID file);
}
