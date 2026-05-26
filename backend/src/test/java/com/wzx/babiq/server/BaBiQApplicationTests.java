package com.wzx.babiq.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 应用启动冒烟测试。
 *
 * <p>这里必须使用独立临时 SQLite 文件，避免全量测试时启动恢复器或长期记忆调度器误写真实用户库。</p>
 */
@SpringBootTest(properties = {
		"babiq.persistence.database-path=target/test-db/context-load-${random.uuid}.db"
})
class BaBiQApplicationTests {

	@Test
	void contextLoads() {
	}

}
