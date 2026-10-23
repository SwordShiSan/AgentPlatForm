package com.agentplatform.infrastructure.dao.po;

import java.util.UUID;

/** Test-bootstrap marker; never created by a production migration. */
public record EnvironmentFixtureScope(String databaseName,UUID fixtureId,UUID actorId,UUID spaceId,UUID workspaceId,String policyHash,int migration) {}
