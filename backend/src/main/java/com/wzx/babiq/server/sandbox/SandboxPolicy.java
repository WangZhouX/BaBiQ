package com.wzx.babiq.server.sandbox;

import java.nio.file.Path;
import java.util.List;

/**
 * 沙箱策略快照。
 *
 * <p>它只描述模式和可写白名单，不负责做路径判断。</p>
 *
 * @param mode 当前沙箱模式
 * @param writableRoots 可写根目录白名单
 */
public record SandboxPolicy(SandboxMode mode, List<Path> writableRoots) {

    public SandboxPolicy {
        writableRoots = writableRoots == null ? List.of() : List.copyOf(writableRoots);
    }

    /**
     * 当前是否只读。
     *
     * @return 只读时返回 true
     */
    public boolean isReadOnly() {
        return mode == SandboxMode.READ_ONLY;
    }

    /**
     * 当前是否完全放行。
     *
     * @return 全放行时返回 true
     */
    public boolean isFullAccess() {
        return mode == SandboxMode.DANGER_FULL_ACCESS;
    }
}
