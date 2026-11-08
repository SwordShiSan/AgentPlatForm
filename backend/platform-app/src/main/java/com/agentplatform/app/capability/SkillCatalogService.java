package com.agentplatform.app.capability;

import com.agentplatform.api.capability.SkillCatalogApi;
import com.agentplatform.api.chat.ChatApi;
import com.agentplatform.domain.capability.CapabilityBindingRepository.Actor;
import com.agentplatform.domain.capability.SkillCatalogRepository;
import com.agentplatform.domain.files.FileFailure;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.transaction.support.TransactionTemplate;

public final class SkillCatalogService implements SkillCatalogApi {
    private final SkillCatalogRepository repository;
    private final TransactionTemplate transactions;
    public SkillCatalogService(SkillCatalogRepository repository, TransactionTemplate transactions) {
        this.repository = Objects.requireNonNull(repository); this.transactions = Objects.requireNonNull(transactions);
    }
    @Override public Page skills(ChatApi.Caller caller, String cursor, int limit) {
        Actor actor = actor(caller); checkLimit(limit); UUID after = cursor == null ? null : uuid(cursor);
        return transactions.execute(status -> {
            access(actor); var rows = repository.skills(actor, after, limit + 1);
            var page = rows.subList(0, Math.min(limit, rows.size()));
            return new Page(project(page), rows.size() > limit ? page.getLast().capabilityId().toString() : null);
        });
    }
    @Override public Page versions(ChatApi.Caller caller, String capabilityId, String cursor, int limit) {
        Actor actor = actor(caller); checkLimit(limit); UUID capability = uuid(capabilityId); Long before = null;
        if (cursor != null) try { before = Long.parseLong(cursor); if (before < 1) throw new NumberFormatException(); }
        catch (NumberFormatException invalid) { throw new FileFailure(400, "INVALID_CURSOR"); }
        Long position = before;
        return transactions.execute(status -> {
            access(actor); if (!repository.exists(actor, capability)) throw new FileFailure(404, "RESOURCE_NOT_FOUND");
            var rows = repository.versions(actor, capability, position, limit + 1);
            var page = rows.subList(0, Math.min(limit, rows.size()));
            return new Page(project(page), rows.size() > limit ? Long.toString(page.getLast().versionNumber()) : null);
        });
    }
    private void access(Actor actor) { if (!repository.authorized(actor)) throw new FileFailure(404, "RESOURCE_NOT_FOUND"); }
    private static void checkLimit(int limit) { if (limit < 1 || limit > 100) throw new FileFailure(400, "INVALID_PAGE_SIZE"); }
    private static UUID uuid(String value) { try { return UUID.fromString(value); } catch (RuntimeException invalid) { throw new FileFailure(400, "INVALID_UUID"); } }
    private static Actor actor(ChatApi.Caller caller) {
        if (caller == null) throw new FileFailure(401, "AUTHENTICATION_REQUIRED");
        return new Actor(uuid(caller.userId()), uuid(caller.spaceId()));
    }
    private static List<Item> project(List<SkillCatalogRepository.Item> rows) {
        return rows.stream().map(v -> new Item(v.capabilityId().toString(), v.versionId().toString(), v.versionNumber(),
                v.name(), v.description(), v.state(), v.revision())).toList();
    }
}
