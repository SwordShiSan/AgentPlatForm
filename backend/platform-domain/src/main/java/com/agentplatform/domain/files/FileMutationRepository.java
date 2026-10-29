package com.agentplatform.domain.files;

import com.agentplatform.domain.files.FileData.*;
import java.util.UUID;

public interface FileMutationRepository {
    Operation move(Actor actor,UUID file,UUID request,String hash,long revision,UUID parent,String name);
    Operation delete(Actor actor,UUID file,UUID request,String hash,long revision);
    Upload prepareRestore(Actor actor,UUID file,UUID request,String hash,long revision,UUID sourceVersion,UUID parent,String name);
    FileMutationData.Request request(Actor actor,UUID file,UUID request);
    Operation restoreTree(Actor actor,UUID file,UUID request,String hash,long revision,UUID deletion,UUID parent,String name);
    java.util.List<FileMutationData.Deleted> deleted(Actor actor,UUID workspace,UUID before,int limit);
    FileMutationData.Preview preview(Actor actor,UUID file);
}
