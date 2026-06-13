package com.wzx.babiq.server.persistence;

import com.wzx.babiq.server.agent.flow.OrchestrationNodeRecord;
import com.wzx.babiq.server.agent.flow.OrchestrationRecord;
import com.wzx.babiq.server.agent.flow.OrchestrationRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrchestrationRepositoryTest {

    private static final Path TEST_DB = Path.of("target", "test-db",
            "orchestration-" + UUID.randomUUID() + ".db").toAbsolutePath();

    @DynamicPropertySource
    static void persistenceProperties(DynamicPropertyRegistry registry) {
        registry.add("babiq.persistence.database-path", () -> TEST_DB.toString());
    }

    @Autowired
    private OrchestrationRepository repository;

    @Test
    void repository_should_persist_run_and_update_node_status() {
        repository.save(new OrchestrationRecord(
                        "orch_repo",
                        "thr_repo",
                        "turn_repo",
                        "整理项目",
                        "parallel",
                        "running",
                        "H:\\aaa",
                        "WORKSPACE_WRITE",
                        true,
                        true,
                        "{\"root\":{\"groupId\":\"g_root\",\"topology\":\"parallel\",\"children\":[{\"nodeId\":\"node_scan\"},{\"nodeId\":\"node_write\"}]}}",
                        "两个节点并行执行",
                        null),
                List.of(
                        new OrchestrationNodeRecord(
                                "orch_repo", "node_scan", "scan", "读取节点", "READ_ONLY_TOOL",
                                "read_file,list_dir", "pending", 1, 0, 0, null),
                        new OrchestrationNodeRecord(
                                "orch_repo", "node_write", "write", "写入节点", "WORKSPACE_TOOL",
                                "write_file", "pending", 2, 0, 0, null)));

        repository.updateNode("orch_repo", "node_scan", "completed", 2, 128, "已读取目录");
        Optional<OrchestrationRecord> run = repository.findByOrchestrationId("orch_repo");
        List<OrchestrationNodeRecord> nodes = repository.listNodes("orch_repo");

        assertThat(run).isPresent();
        assertThat(run.get().approved()).isTrue();
        assertThat(run.get().structureJson()).contains("\"groupId\":\"g_root\"");
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).status()).isEqualTo("completed");
        assertThat(nodes.get(0).toolCallCount()).isEqualTo(2);
        assertThat(nodes.get(0).summary()).contains("目录");
    }
}
