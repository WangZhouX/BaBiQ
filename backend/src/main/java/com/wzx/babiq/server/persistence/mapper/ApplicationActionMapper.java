package com.wzx.babiq.server.persistence.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzx.babiq.server.persistence.entity.ApplicationActionEntity;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface ApplicationActionMapper extends BaseMapper<ApplicationActionEntity> {

    /** 仅当当前状态仍等于调用方读取值时更新，跨实例保护 first-terminal-wins。 */
    @Update("""
            UPDATE bq_application_actions
            SET status = #{next.status},
                result_summary_redacted = #{next.resultSummaryRedacted},
                error_code = #{next.errorCode},
                error_message_redacted = #{next.errorMessageRedacted},
                updated_at = #{next.updatedAt},
                terminal_at = #{next.terminalAt}
            WHERE execution_id = #{executionId} AND status = #{expectedStatus}
            """)
    int updateStateIfCurrent(
            @Param("executionId") String executionId,
            @Param("expectedStatus") String expectedStatus,
            @Param("next") ApplicationActionEntity next);
}
