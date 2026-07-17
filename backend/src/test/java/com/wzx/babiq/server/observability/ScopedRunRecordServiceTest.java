package com.wzx.babiq.server.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.context.ContextStatusService;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.persistence.entity.TurnEntity;
import com.wzx.babiq.server.persistence.mapper.ItemMapper;
import com.wzx.babiq.server.persistence.service.ApprovalPersistenceService;
import com.wzx.babiq.server.persistence.service.ToolCallPersistenceService;
import com.wzx.babiq.server.persistence.service.TurnPersistenceService;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScopedRunRecordServiceTest {

    private static final BusinessIdentityScope SCOPE = BusinessIdentityScope.scoped(
            "desktop", "session", "auth", 1, "user", "tenant", "platform");

    @Test
    void scopedGetDoesNotRepeatAnUnscopedTurnLookupAfterAuthorization() {
        TurnPersistenceService turns = mock(TurnPersistenceService.class);
        TurnEntity turn = new TurnEntity();
        turn.setTurnId("turn");
        turn.setThreadId("thread");
        when(turns.findTurn("turn", SCOPE)).thenReturn(Optional.of(turn));
        ConversationRepository conversations = mock(ConversationRepository.class);
        ItemMapper items = mock(ItemMapper.class);
        ApprovalPersistenceService approvals = mock(ApprovalPersistenceService.class);
        ToolCallPersistenceService tools = mock(ToolCallPersistenceService.class);
        ContextStatusService context = mock(ContextStatusService.class);
        when(items.selectAuthorizedTurnItems(
                org.mockito.ArgumentMatchers.eq("turn"), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
        when(approvals.listByTurnId("turn", SCOPE)).thenReturn(List.of());
        when(tools.listByTurnId("turn", SCOPE)).thenReturn(List.of());
        RunRecordService service = new RunRecordService(
                turns, conversations, items, approvals, tools, context, new ObjectMapper());

        service.getTurn("turn", SCOPE);

        verify(turns).findTurn("turn", SCOPE);
        verify(turns, never()).findTurn("turn");
        verify(conversations).findTurnSummary("turn", SCOPE);
        verify(conversations, never()).findTurnSummary("turn");
        verify(approvals).listByTurnId("turn", SCOPE);
        verify(approvals, never()).listByTurnId("turn");
        verify(tools).listByTurnId("turn", SCOPE);
        verify(tools, never()).listByTurnId("turn");
        verify(context).latestForTurn("turn", SCOPE);
        verify(context, never()).latestForTurn("turn");
    }
}
