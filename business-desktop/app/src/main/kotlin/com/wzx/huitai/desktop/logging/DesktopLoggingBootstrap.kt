package com.wzx.huitai.desktop.logging

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.rolling.RollingFileAppender
import ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy
import ch.qos.logback.core.util.FileSize
import com.wzx.huitai.desktop.runtime.RuntimeFilePermissions
import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

/**
 * 在业务桌面任何 logger 创建前安装独立滚动文件 appender。
 *
 * 调用方先创建安全日志目录再调用本类；初始化只允许一次，重复调用同一路径幂等，不允许在进程
 * 中途把业务日志切换到其他目录。
 */
object DesktopLoggingBootstrap {
    private const val APPENDER_NAME = "HUITAI_BUSINESS_DESKTOP_FILE"
    private var configuredPath: Path? = null

    /** 将桌面 SLF4J 根日志写入 `desktop/logs/desktop.log`，并收紧目录和文件权限。 */
    @Synchronized
    fun initialize(logPath: Path) {
        val normalized = logPath.toAbsolutePath().normalize()
        configuredPath?.let { existing ->
            check(existing == normalized) { "desktop logging is already configured" }
            return
        }
        Files.createDirectories(normalized.parent)
        RuntimeFilePermissions.applyOwnerOnly(normalized.parent, directory = true)
        val context = LoggerFactory.getILoggerFactory() as? LoggerContext
            ?: error("Logback is required for business desktop logging")
        val root = context.getLogger(Logger.ROOT_LOGGER_NAME)

        val encoder = PatternLayoutEncoder().apply {
            this.context = context
            pattern = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %-5level [%thread] %logger{40} - %msg%n"
            start()
        }
        val appender = RollingFileAppender<ILoggingEvent>().apply {
            this.context = context
            name = APPENDER_NAME
            file = normalized.toString()
            isAppend = true
            this.encoder = encoder
        }
        val policy = SizeAndTimeBasedRollingPolicy<ILoggingEvent>().apply {
            this.context = context
            setParent(appender)
            fileNamePattern = "$normalized.%d{yyyy-MM-dd}.%i.gz"
            setMaxFileSize(FileSize.valueOf("10MB"))
            maxHistory = 7
            setTotalSizeCap(FileSize.valueOf("100MB"))
            start()
        }
        appender.rollingPolicy = policy
        appender.start()
        check(appender.isStarted) { "desktop file logging could not start" }
        root.addAppender(appender)
        configuredPath = normalized
        runCatching { RuntimeFilePermissions.applyOwnerOnly(normalized, directory = false) }
    }
}
