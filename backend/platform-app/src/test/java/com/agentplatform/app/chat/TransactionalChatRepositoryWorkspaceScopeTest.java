package com.agentplatform.app.chat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.agentplatform.domain.chat.ChatRepository;
import com.agentplatform.domain.chat.ChatData.Lease;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

final class TransactionalChatRepositoryWorkspaceScopeTest {
    @Test void delegatesBoundWorkspaceInsideManagedTransaction() {
        ChatRepository delegate=mock(ChatRepository.class);
        TransactionTemplate transactions=mock(TransactionTemplate.class);
        PlatformTransactionManager manager=mock(PlatformTransactionManager.class);
        TransactionStatus status=mock(TransactionStatus.class);
        when(transactions.getTransactionManager()).thenReturn(manager);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") TransactionCallback<UUID> callback=invocation.getArgument(0);
            return callback.doInTransaction(status);
        });

        UUID workspace=UUID.randomUUID();
        Lease lease=mock(Lease.class);
        when(delegate.boundWorkspace(lease)).thenReturn(workspace);

        assertEquals(workspace,new TransactionalChatRepository(delegate,transactions).boundWorkspace(lease));
    }
}
