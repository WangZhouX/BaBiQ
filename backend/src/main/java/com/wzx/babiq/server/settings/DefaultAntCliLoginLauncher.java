package com.wzx.babiq.server.settings;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 独立启动 `ant auth login`。
 *
 * <p>这是可能打开浏览器或等待用户操作的交互式命令，不能复用带短超时的 {@link DefaultAntCliRunner}。</p>
 */
@Component
public class DefaultAntCliLoginLauncher implements AntCliLoginLauncher {

    private final String cliPath;

    public DefaultAntCliLoginLauncher(@Value("${babiq.anthropic.oauth.cli-path:ant}") String cliPath) {
        this.cliPath = cliPath == null || cliPath.isBlank() ? "ant" : cliPath;
    }

    @Override
    public AntCliLoginStartResult startLogin() {
        try {
            Process process = new ProcessBuilder(cliPath, "auth", "login")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return new AntCliLoginStartResult(true, process.pid(), "已启动 ant auth login");
        } catch (IOException exception) {
            return new AntCliLoginStartResult(false, null, "启动 ant auth login 失败，请确认已安装 Anthropic CLI");
        }
    }
}
