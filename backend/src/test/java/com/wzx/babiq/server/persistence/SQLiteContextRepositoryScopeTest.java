package com.wzx.babiq.server.persistence;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.wzx.babiq.server.application.scope.BusinessIdentityScope;
import com.wzx.babiq.server.persistence.entity.ContextSnapshotEntity;
import com.wzx.babiq.server.persistence.entity.ContextWindowEntity;
import com.wzx.babiq.server.persistence.mapper.ContextSnapshotMapper;
import com.wzx.babiq.server.persistence.mapper.ContextWindowMapper;
import com.wzx.babiq.server.persistence.service.SQLiteContextSnapshotRepository;
import com.wzx.babiq.server.persistence.service.SQLiteContextWindowRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SQLiteContextRepositoryScopeTest {

    private static final BusinessIdentityScope SCOPE = BusinessIdentityScope.scoped(
            "desktop", "session", "auth", 1, "user", "tenant", "platform");

    @Test
    void scopedWindowLookupBuildsExactIdentityPredicateBeforeMapperRead() {
        initializeTable(ContextWindowEntity.class);
        ContextWindowMapper mapper = mock(ContextWindowMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        new SQLiteContextWindowRepository(mapper).findByThreadId("thread", SCOPE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ContextWindowEntity>> query = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectOne(query.capture());
        assertExactScopeSql(query.getValue().getSqlSegment());
    }

    @Test
    void scopedSnapshotLookupsBuildExactIdentityPredicateBeforeMapperRead() {
        initializeTable(ContextSnapshotEntity.class);
        ContextSnapshotMapper mapper = mock(ContextSnapshotMapper.class);
        when(mapper.selectOne(any())).thenReturn(null);
        SQLiteContextSnapshotRepository repository = new SQLiteContextSnapshotRepository(mapper);

        repository.findBySnapshotId("snapshot", SCOPE);
        repository.findLatestByTurnId("turn", SCOPE);
        repository.findLatestByThreadId("thread", SCOPE);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<ContextSnapshotEntity>> queries = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper, org.mockito.Mockito.times(3)).selectOne(queries.capture());
        queries.getAllValues().forEach(query -> assertExactScopeSql(query.getSqlSegment()));
    }

    private static void assertExactScopeSql(String sql) {
        assertThat(sql).contains(
                "desktop_instance_id", "desktop_session_id", "auth_session_id", "identity_epoch",
                "user_id", "tenant_id", "platform_id");
    }

    private static void initializeTable(Class<?> entityType) {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), entityType.getName()), entityType);
    }
}
