package com.agentplatform.infrastructure.dao.chat;
/** Typed Mapper collaborators, all using the same Spring SqlSessionTemplate. */
public record ChatMappers(LedgerMapper ledger, ConversationMapper conversation, DispatchMapper dispatch, AccountingMapper accounting, ConfigurationMapper configuration, EventMapper event) {}
