package com.wzx.babiq.server.conversation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.conversation.repository.ConversationRepository;
import com.wzx.babiq.server.persistence.entity.ThreadEntity;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ScopedConversationApplicationServiceTest {

    private static final BusinessIdentityScope SCOPE = BusinessIdentityScope.scoped(
            "desktop", "session", "auth", 1, "user", "tenant", "platform");

    @Test
    void scopedLoadDoesNotRepeatAnUnscopedThreadLookupAfterAuthorization() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ThreadEntity thread = ThreadEntity.active(
                "thread", "title", "cwd", "provider", "model", "sandbox", "approval", Instant.now());
        when(repository.findThread("thread", SCOPE)).thenReturn(Optional.of(thread));
        when(repository.listItems("thread", 201, null, SCOPE)).thenReturn(List.of());
        ConversationApplicationService service = new ConversationApplicationService(
                repository, mock(ConversationService.class), new ObjectMapper());

        service.loadThread("thread", 200, null, SCOPE);

        verify(repository).findThread("thread", SCOPE);
        verify(repository, never()).findThread("thread");
        verify(repository).listItems("thread", 201, null, SCOPE);
        verify(repository, never()).listItems("thread", 201, null);
    }

    @Test
    void scopedArchiveChecksOwnershipBeforeActiveTurnState() {
        ConversationRepository repository = mock(ConversationRepository.class);
        ConversationService live = mock(ConversationService.class);
        when(repository.findThread("thread", SCOPE)).thenReturn(Optional.empty());
        when(live.hasActiveTurn("thread", SCOPE)).thenReturn(true);
        ConversationApplicationService service = new ConversationApplicationService(
                repository, live, new ObjectMapper());

        assertThatThrownBy(() -> service.archiveThread("thread", SCOPE))
                .isInstanceOf(IllegalArgumentException.class);

        verify(repository).findThread("thread", SCOPE);
        verify(live, never()).hasActiveTurn("thread", SCOPE);
    }
}
