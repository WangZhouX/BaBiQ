-- 工具调用短标识会在不同 turn 中重复；其业务身份必须由 turn_id 与 tool_call_id 共同确定。
DROP INDEX IF EXISTS bd_action_executions_tool_call_id_unique;

CREATE UNIQUE INDEX bd_action_executions_turn_tool_call_id_unique
    ON bd_action_executions(turn_id, tool_call_id)
    WHERE turn_id IS NOT NULL AND tool_call_id IS NOT NULL;

UPDATE bd_schema_comments
SET comment_text = '动作所属轮次标识；与工具调用标识共同确定 Agent 动作身份'
WHERE object_type = 'COLUMN'
  AND object_name = 'bd_action_executions'
  AND column_name = 'turn_id';

UPDATE bd_schema_comments
SET comment_text = '轮次内工具调用标识；与轮次标识共同确定 Agent 动作身份'
WHERE object_type = 'COLUMN'
  AND object_name = 'bd_action_executions'
  AND column_name = 'tool_call_id';
