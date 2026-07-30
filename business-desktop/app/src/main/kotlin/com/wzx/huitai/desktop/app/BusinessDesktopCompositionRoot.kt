package com.wzx.huitai.desktop.app

import com.wzx.huitai.action.ActionExecutionContextValidator
import com.wzx.huitai.action.ApplicationActionBus
import com.wzx.huitai.action.ActionResolution
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.agent.application.ApplicationActionRequestHandler
import com.wzx.huitai.agent.application.ApplicationAuthenticationGate
import com.wzx.huitai.agent.application.ApplicationAuthenticationSnapshot
import com.wzx.huitai.agent.application.TrustedApplicationIdentity
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.business.auth.BusinessAuthRpcClient
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.agent.client.KtorAgentTransport
import com.wzx.huitai.agent.conversation.BusinessAgentClient
import com.wzx.huitai.agent.business.workbench.BusinessWorkbenchRpcClient
import com.wzx.huitai.agent.business.workbench.BusinessScheduleRpcClient
import com.wzx.huitai.desktop.controller.BusinessConnectionLifecycle
import com.wzx.huitai.desktop.controller.BusinessContextPublicationPort
import com.wzx.huitai.desktop.controller.BusinessConversationController
import com.wzx.huitai.desktop.controller.BusinessDesktopCoordinator
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsController
import com.wzx.huitai.desktop.controller.BusinessRegistrationPort
import com.wzx.huitai.desktop.controller.BusinessWorkspaceController
import com.wzx.huitai.desktop.workbench.BusinessWorkbenchController
import com.wzx.huitai.desktop.workbench.BusinessAttachmentUploadClient
import com.wzx.huitai.desktop.workbench.BusinessLoopbackEndpoint
import com.wzx.huitai.desktop.workbench.BusinessScheduleController
import com.wzx.huitai.desktop.workbench.BusinessScheduleAttachmentPicker
import com.wzx.huitai.desktop.workbench.KtorBusinessAttachmentUploadTransport
import com.wzx.huitai.desktop.controller.DirectUserApplicationActionPort
import com.wzx.huitai.desktop.auth.BusinessAccessGateState
import com.wzx.huitai.desktop.auth.BusinessAuthenticationLifecycle
import com.wzx.huitai.desktop.auth.BusinessRpcAuthenticationOperations
import com.wzx.huitai.desktop.auth.BusinessIdentityRegistry
import com.wzx.huitai.desktop.auth.BusinessLoginController
import com.wzx.huitai.desktop.auth.BusinessLogoutController
import com.wzx.huitai.desktop.auth.BusinessLoginMessage
import com.wzx.huitai.desktop.auth.ReadyAgentUsageGate
import com.wzx.huitai.desktop.auth.config.BusinessLegalLinksConfiguration
import com.wzx.huitai.desktop.auth.config.BusinessLegalLinksLoader
import com.wzx.huitai.desktop.auth.config.BusinessBackendConnectionConfigurationLoader
import com.wzx.huitai.desktop.decision.ComposeActionDecisionCoordinator
import com.wzx.huitai.desktop.decision.ComposeApprovalPort
import com.wzx.huitai.desktop.decision.ComposeConfirmationPort
import com.wzx.huitai.desktop.logging.DesktopLoggingBootstrap
import com.wzx.huitai.desktop.runtime.AuthenticatedWebSocketProbe
import com.wzx.huitai.desktop.runtime.BusinessAgentConnectionSession
import com.wzx.huitai.desktop.runtime.BusinessAgentDevelopmentSessionFile
import com.wzx.huitai.desktop.runtime.BusinessAttachmentIdFactory
import com.wzx.huitai.desktop.runtime.BusinessAgentProcessLauncher
import com.wzx.huitai.desktop.runtime.BusinessAgentReadinessProbe
import com.wzx.huitai.desktop.runtime.BusinessAgentRuntimeSession
import com.wzx.huitai.desktop.runtime.BusinessBackendKeyStorePasswordVault
import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import com.wzx.huitai.desktop.runtime.ClipboardImageAttachmentStore
import com.wzx.huitai.desktop.runtime.DesktopInstallationIdentityStore
import com.wzx.huitai.desktop.runtime.BusinessAgentLaunchRequest
import com.wzx.huitai.desktop.runtime.ManagedBusinessAgentConnection
import com.wzx.huitai.desktop.smoke.PackagedSmokeEvidence
import com.wzx.huitai.desktop.security.BusinessLoginCredentialStore
import com.wzx.huitai.desktop.security.LegacyOaCredentialAliasCleanup
import com.wzx.huitai.desktop.security.LocalCredentialStoreUnavailableException
import com.wzx.huitai.desktop.state.BusinessDesktopReducer
import com.wzx.huitai.desktop.state.BusinessDesktopState
import com.wzx.huitai.desktop.state.BusinessDesktopStore
import com.wzx.huitai.desktop.state.BusinessFieldSuggestion
import com.wzx.huitai.desktop.state.BusinessIdentity
import com.wzx.huitai.desktop.ui.agent.BusinessAttachmentPicker
import com.wzx.huitai.demo.action.DemoActionCatalog
import com.wzx.huitai.demo.gateway.FakeHuitaiGateway
import com.wzx.huitai.demo.model.DemoFormState
import com.wzx.huitai.demo.model.DemoScreenModel
import com.wzx.huitai.presentation.form.FormPatch
import com.wzx.huitai.security.approval.SQLiteApprovalRecordStore
import com.wzx.huitai.security.audit.SQLiteActionAuditPort
import com.wzx.huitai.security.database.BusinessDesktopDatabase
import com.wzx.huitai.security.execution.SQLiteActionExecutionStore
import com.wzx.huitai.security.execution.ActionExecutionPolicies
import com.wzx.huitai.security.execution.ActionExecutionPolicyResolver
import com.wzx.huitai.security.instance.ProcessInstanceLock
import com.wzx.huitai.security.risk.DefaultActionRiskPolicy
import com.wzx.huitai.security.secret.JceksSecretStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.time.Instant
import java.util.Arrays
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 可挂起关闭的装配阶段资源，允许连接和控制器在收尾时完成协程清理。 */
fun interface CompositionResource {
    suspend fun close()
}

/** storage 阶段产出的唯一动作总线和统一关闭句柄。 */
class BusinessDesktopStorageAssembly(
    val applicationActionBus: ApplicationActionBus,
    val resource: CompositionResource,
    internal val production: ProductionStorageComponents? = null,
)

/** Main/Compose 只读取的生产运行视图；写操作仍通过明确 controller 回调进入。 */
class BusinessDesktopRuntimeView internal constructor(
    val desktopState: StateFlow<BusinessDesktopState>,
    val formState: StateFlow<DemoFormState>,
    val decisions: ComposeActionDecisionCoordinator,
    internal val production: ProductionUiComponents,
)

/** UI/controller 阶段显式声明用户点击与 Agent request 使用的总线引用。 */
class BusinessDesktopUiAssembly(
    val userActionBus: ApplicationActionBus,
    val agentRequestActionBus: ApplicationActionBus,
    val resource: CompositionResource,
    val runtimeView: BusinessDesktopRuntimeView? = null,
    internal val start: suspend () -> Unit = {},
)

/**
 * 业务桌面装配阶段端口。
 *
 * 生产实现创建真实 SQLite/JCEKS、内置 child、认证连接、协议客户端和控制器；测试实现只替换
 * 资源工厂以观察顺序，不能改变 [BusinessDesktopCompositionRoot] 的回滚和唯一总线约束。
 */
interface BusinessDesktopCompositionFactory {
    suspend fun acquireDesktopLock(): CompositionResource
    suspend fun openStorage(): BusinessDesktopStorageAssembly
    suspend fun launchChild(storage: BusinessDesktopStorageAssembly): CompositionResource
    suspend fun connectAgent(
        storage: BusinessDesktopStorageAssembly,
        child: CompositionResource,
    ): CompositionResource

    /** Compatibility phase hooks for test factories; production implementations are no-ops. */
    suspend fun initializeIdentity(connection: CompositionResource) = Unit
    suspend fun initializeCatalog(connection: CompositionResource) = Unit
    suspend fun initializeContext(connection: CompositionResource) = Unit

    suspend fun createUi(
        storage: BusinessDesktopStorageAssembly,
        connection: CompositionResource,
    ): BusinessDesktopUiAssembly
}

/**
 * 强制执行业务桌面启动与关闭顺序的唯一 composition root。
 *
 * 启动：lock -> storage -> child -> local authenticated connection -> UI；OA identity、catalog
 * and page context are installed by the local Spring Boot gateway after business authentication.
 * 关闭：controllers/UI -> connection -> child -> stores -> lock。任一启动阶段失败都会按同样逆序回滚。
 */
class BusinessDesktopCompositionRoot private constructor(
    val applicationActionBus: ApplicationActionBus,
    val userActionBus: ApplicationActionBus,
    val agentRequestActionBus: ApplicationActionBus,
    val runtimeView: BusinessDesktopRuntimeView?,
    internal val productionStorage: ProductionStorageComponents?,
    private val ui: CompositionResource,
    private val connection: CompositionResource,
    private val child: CompositionResource,
    private val storage: CompositionResource,
    private val desktopLock: CompositionResource,
) {
    private val closed = AtomicBoolean(false)

    /** 幂等按严格逆序关闭；第一个失败作为主异常，其余关闭失败附加 suppressed。 */
    suspend fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        withContext(NonCancellable) {
            closeAll(listOf(ui, connection, child, storage, desktopLock))?.let { throw it }
        }
    }

    companion object {
        /** 执行阶段工厂并建立唯一总线不变量；失败时完整回滚已获得资源。 */
        suspend fun start(factory: BusinessDesktopCompositionFactory): BusinessDesktopCompositionRoot {
            var desktopLock: CompositionResource? = null
            var storage: BusinessDesktopStorageAssembly? = null
            var child: CompositionResource? = null
            var connection: CompositionResource? = null
            var ui: BusinessDesktopUiAssembly? = null
            try {
                desktopLock = factory.acquireDesktopLock()
                storage = factory.openStorage()
                child = factory.launchChild(storage)
                connection = factory.connectAgent(storage, child)
                // Keep the observable phase seam for test factories; production hooks are local
                // no-ops because the Spring Boot gateway owns identity/catalog/context setup.
                factory.initializeIdentity(connection)
                factory.initializeCatalog(connection)
                factory.initializeContext(connection)
                ui = factory.createUi(storage, connection)
                check(ui.userActionBus === storage.applicationActionBus) {
                    "User actions must use the one application action bus"
                }
                check(ui.agentRequestActionBus === storage.applicationActionBus) {
                    "Agent action requests must use the one application action bus"
                }
                val root = BusinessDesktopCompositionRoot(
                    applicationActionBus = storage.applicationActionBus,
                    userActionBus = ui.userActionBus,
                    agentRequestActionBus = ui.agentRequestActionBus,
                    runtimeView = ui.runtimeView,
                    productionStorage = storage.production,
                    ui = ui.resource,
                    connection = connection,
                    child = child,
                    storage = storage.resource,
                    desktopLock = desktopLock,
                )
                ui.start()
                return root
            } catch (failure: Throwable) {
                val rollbackFailure = withContext(NonCancellable) {
                    closeAll(listOfNotNull(ui?.resource, connection, child, storage?.resource, desktopLock))
                }
                rollbackFailure?.let(failure::addSuppressed)
                throw failure
            }
        }

        /** 顺序关闭资源并聚合异常，确保后续锁和文件句柄仍有机会释放。 */
        private suspend fun closeAll(resources: List<CompositionResource>): Throwable? {
            var first: Throwable? = null
            resources.forEach { resource ->
                try {
                    resource.close()
                } catch (failure: Throwable) {
                    first?.addSuppressed(failure) ?: run { first = failure }
                }
            }
            return first
        }
    }
}

/** 生产桌面 KeyStore 主密码来源；实现必须返回调用方可擦除的新数组。 */
fun interface DesktopSecretBootstrap {
    fun load(): CharArray
}

/** 从明确环境变量读取桌面 JCEKS 主密码；缺失时 fail-closed，不内置任何默认凭据。 */
class EnvironmentDesktopSecretBootstrap(
    private val environment: () -> Map<String, String> = System::getenv,
) : DesktopSecretBootstrap {
    override fun load(): CharArray {
        val variables = environment()
        variables[ENV_NAME]
            ?.takeIf(String::isNotBlank)
            ?.let { return it.toCharArray() }
        if (variables[DIRECT_DEVELOPMENT_ENV] == "1") {
            val home = variables["HUITAI_DESKTOP_HOME"]
                ?.takeIf(String::isNotBlank)
                ?.let(Path::of)
                ?: Path.of(System.getProperty("user.home"))
            val passwordFile = home
                .toAbsolutePath()
                .normalize()
                .resolve(".huitai-agent-desktop/agent/backend-keystore-password")
            return runCatching {
                Files.readString(passwordFile, Charsets.US_ASCII)
                    .trim()
                    .takeIf(String::isNotBlank)
                    ?.toCharArray()
                    ?: throw LocalCredentialStoreUnavailableException()
            }.getOrElse { failure ->
                if (failure is LocalCredentialStoreUnavailableException) {
                    throw failure
                }
                throw LocalCredentialStoreUnavailableException()
            }
        }
        throw LocalCredentialStoreUnavailableException()
    }

    override fun toString(): String = "EnvironmentDesktopSecretBootstrap(value=[REDACTED])"

    companion object {
        const val ENV_NAME = "HUITAI_DESKTOP_KEYSTORE_PASSWORD"
        const val DIRECT_DEVELOPMENT_ENV = "HUITAI_BUSINESS_DIRECT_DEVELOPMENT"
    }
}

/** 生产装配的非敏感位置配置；密码只能由 [desktopSecretBootstrap] 按需提供。 */
class BusinessDesktopProductionConfiguration(
    val home: Path,
    val backendJar: Path,
    val desktopSecretBootstrap: DesktopSecretBootstrap = EnvironmentDesktopSecretBootstrap(),
    val agentLaunchMode: BusinessAgentLaunchMode = BusinessAgentLaunchMode.Embedded,
) {
    override fun toString(): String =
        "BusinessDesktopProductionConfiguration(home=[REDACTED], backendJar=[REDACTED], secret=[REDACTED])"

    companion object {
        /** Task35 安装包可通过资源根属性/环境覆盖，开发态才回退当前目录。 */
        fun resolveBundledBackendJar(
            systemProperties: Map<String, String> = System.getProperties().entries.associate {
                it.key.toString() to it.value.toString()
            },
            environment: Map<String, String> = System.getenv(),
            workingDirectory: Path = Path.of("").toAbsolutePath(),
        ): Path {
            val resourceRoot = systemProperties["huitai.business.resources.root"]
                ?: systemProperties["compose.application.resources.dir"]
                ?: environment["HUITAI_DESKTOP_RESOURCES_ROOT"]
            val root = resourceRoot?.let(Path::of) ?: workingDirectory
            return root.toAbsolutePath().normalize().resolve("backend/babiq-server.jar")
        }

        fun resolveHome(
            environment: Map<String, String> = System.getenv(),
            systemProperties: Map<String, String> = System.getProperties().entries.associate {
                it.key.toString() to it.value.toString()
            },
        ): Path = (environment["HUITAI_DESKTOP_HOME"]
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: Path.of(systemProperties.getValue("user.home")))
            .toAbsolutePath()
            .normalize()
    }
}

enum class BusinessAgentLaunchMode {
    Embedded,
    ExternalDevelopment,
}

/** child 阶段返回的稳定身份、计数器和进程资源。 */
class BusinessAgentChildHandle internal constructor(
    val identity: DesktopSessionIdentity,
    val sequenceTracker: ApplicationSequenceTracker,
    val resource: CompositionResource,
    internal val runtimeSession: BusinessAgentRuntimeSession? = null,
    internal val connectionSession: BusinessAgentConnectionSession? = runtimeSession?.connectionSession,
)

/** 测试可替换 child 启动，但不能替换 storage 和应用动作核心。 */
fun interface BusinessAgentChildLauncher {
    suspend fun launch(context: BusinessAgentChildLaunchContext): BusinessAgentChildHandle
}

/** child launcher 的敏感密码数组仅在调用期间有效，launcher 不得记录或长期持有。 */
class BusinessAgentChildLaunchContext internal constructor(
    val paths: BusinessDesktopRuntimePaths,
    val desktopInstanceId: String,
    val backendJar: Path,
    val backendKeyStorePassword: CharArray,
) {
    override fun toString(): String =
        "BusinessAgentChildLaunchContext(paths=[REDACTED], identity=[REDACTED], jar=[REDACTED], password=[REDACTED])"
}

/** 连接阶段资源；[connection] 是 JSON-RPC 唯一消费的 supervisor facade。 */
class BusinessAgentConnectionHandle(
    val connection: AgentConnection,
    val resource: CompositionResource,
)

/** 测试可注入已认证 fake connection；生产实现必须使用 child 的固定会话身份。 */
fun interface BusinessAgentConnector {
    suspend fun connect(child: BusinessAgentChildHandle): BusinessAgentConnectionHandle
}

/** 生产 storage 阶段的真实组件，只通过 root 的内部只读引用用于验收。 */
class ProductionStorageComponents internal constructor(
    val database: BusinessDesktopDatabase,
    val executionStore: SQLiteActionExecutionStore,
    val auditPort: SQLiteActionAuditPort,
    val approvalStore: SQLiteApprovalRecordStore,
    val secretStore: JceksSecretStore,
    /** Account-only remembered login state; OA credentials remain server-owned. */
    val screen: DemoScreenModel,
    val catalog: DemoActionCatalog,
    val decisions: ComposeActionDecisionCoordinator,
    val desktopStore: BusinessDesktopStore,
    val actionBus: ApplicationActionBus,
    val workspaceRoot: Path,
    internal val backendKeyStorePassword: CharArray,
)

/** 生产 UI/controller 阶段的真实组件，关闭责任由 UI CompositionResource 统一持有。 */
class ProductionUiComponents internal constructor(
    val conversationController: BusinessConversationController,
    val providerSettingsController: BusinessProviderSettingsController,
    val workspaceController: BusinessWorkspaceController,
    val desktopCoordinator: BusinessDesktopCoordinator,
    val actionRequestHandler: ApplicationActionRequestHandler,
    val businessAgentClient: BusinessAgentClient,
    val workbenchController: BusinessWorkbenchController,
    val scheduleController: BusinessScheduleController,
    val attachmentUploadClient: BusinessAttachmentUploadClient,
    val agentClipboardAttachmentRoot: Path,
    val clipboardImageAttachmentStore: ClipboardImageAttachmentStore,
    val attachmentPicker: BusinessAttachmentPicker,
    val scheduleAttachmentPicker: BusinessScheduleAttachmentPicker,
    val loginController: BusinessLoginController,
    val logoutController: BusinessLogoutController,
    internal val authenticationOperations: BusinessRpcAuthenticationOperations,
    val authenticationGate: StateFlow<BusinessAccessGateState>,
    val authenticationError: StateFlow<BusinessLoginMessage?>,
    val identityRegistry: BusinessIdentityRegistry,
    val serviceAgreementUrl: String,
    val privacyPolicyUrl: String,
)

/**
 * 使用真实 SQLite/JCEKS、演示动作、Compose 决策端口和 Agent 客户端的生产 stage factory。
 *
 * 唯一可替换边界是 child/connection seam，供自动化避免启动真实 jar；所有业务 storage、bus、
 * request handler 和 controllers 仍走生产实现。
 */
class ProductionBusinessDesktopCompositionFactory(
    configuration: BusinessDesktopProductionConfiguration,
    parentScope: CoroutineScope,
    private val childLauncher: BusinessAgentChildLauncher? = null,
    private val connector: BusinessAgentConnector? = null,
    private val legacyCredentialCleanup: ((JceksSecretStore) -> Unit)? = null,
) : BusinessDesktopCompositionFactory {
    private val configuration = configuration
    private val paths = BusinessDesktopRuntimePaths.create(configuration.home)
    private val scopeJob = SupervisorJob(parentScope.coroutineContext[Job])
    private val scope = CoroutineScope(parentScope.coroutineContext.minusKey(Job) + scopeJob)
    private val envelopeSequence = AtomicLong(0)
    private lateinit var storage: ProductionStorageComponents
    private lateinit var child: BusinessAgentChildHandle
    private lateinit var agentConnection: BusinessAgentConnectionHandle
    private lateinit var connectionLifecycle: BusinessConnectionLifecycle
    private lateinit var rpc: AgentJsonRpcClient
    private val identityRegistry = BusinessIdentityRegistry()
    private lateinit var legalLinks: BusinessLegalLinksConfiguration

    init {
        // 路径准备本身不创建 logger；紧接着安装独立 appender，后续组件才允许获取 logger。
        DesktopLoggingBootstrap.initialize(paths.desktopLog)
    }

    override suspend fun acquireDesktopLock(): CompositionResource {
        val lock = try {
            ProcessInstanceLock.acquire(paths.desktopInstanceLock)
        } catch (failure: Throwable) {
            scopeJob.cancel()
            throw failure
        }
        return CompositionResource {
            try {
                lock.close()
            } finally {
                scopeJob.cancel()
            }
        }
    }

    override suspend fun openStorage(): BusinessDesktopStorageAssembly {
        legalLinks = BusinessLegalLinksLoader().load(paths)
        val database = BusinessDesktopDatabase(paths.desktopDatabase)
        var desktopPassword: CharArray? = null
        var backendPassword: CharArray? = null
        var secretStore: JceksSecretStore? = null
        try {
            desktopPassword = configuration.desktopSecretBootstrap.load()
            require(desktopPassword.isNotEmpty()) { "desktop KeyStore password must not be empty" }
            secretStore = JceksSecretStore(paths.desktopKeyStore, desktopPassword)
            backendPassword = BusinessBackendKeyStorePasswordVault.loadOrCreate(secretStore)
            val screen = DemoScreenModel()
            val catalog = DemoActionCatalog(screen, FakeHuitaiGateway())
            val registry = catalog.createRegistry()
            val executionStore = SQLiteActionExecutionStore(
                database = database,
                policyResolver = ActionExecutionPolicyResolver { record ->
                    when (val resolution = registry.resolve(
                        record.command.actionId,
                        record.command.actionVersion,
                    )) {
                        is ActionResolution.Found -> ActionExecutionPolicies(
                            replayPolicy = resolution.action.descriptor.replayPolicy,
                            reconciliationPolicy = resolution.action.descriptor.reconciliationPolicy,
                        )
                        is ActionResolution.NotFound -> ActionExecutionPolicies(
                            replayPolicy = ActionReplayPolicy.NEVER,
                            reconciliationPolicy = ReconciliationPolicy.MANUAL,
                        )
                    }
                },
            )
            val decisions = ComposeActionDecisionCoordinator(
                actionTitleResolver = { actionId ->
                    catalog.actions.firstOrNull { it.descriptor.id == actionId }?.descriptor?.title ?: actionId
                },
            )
            val actionBus = ApplicationActionBus(
                registry = registry,
                riskPolicy = DefaultActionRiskPolicy(),
                confirmationPort = ComposeConfirmationPort(decisions),
                approvalPort = ComposeApprovalPort(decisions),
                executionStore = executionStore,
                clock = ActionClock(Instant::now),
                contextValidator = ActionExecutionContextValidator(),
            )
            storage = ProductionStorageComponents(
                database = database,
                executionStore = executionStore,
                auditPort = SQLiteActionAuditPort(database),
                approvalStore = SQLiteApprovalRecordStore(database),
                secretStore = secretStore,
                screen = screen,
                catalog = catalog,
                decisions = decisions,
                desktopStore = BusinessDesktopStore(BusinessDesktopReducer()),
                actionBus = actionBus,
                workspaceRoot = configuration.home.toAbsolutePath().normalize(),
                backendKeyStorePassword = backendPassword,
            )
            backendPassword = null // ownership transferred to storage close resource
            val close = CompositionResource {
                closeCompositionSteps(
                    { storage.decisions.shutdown() },
                    { Arrays.fill(storage.backendKeyStorePassword, '\u0000') },
                    { storage.secretStore.close() },
                    { storage.database.close() },
                )?.let { throw it }
            }
            return BusinessDesktopStorageAssembly(actionBus, close, storage)
        } catch (failure: Throwable) {
            backendPassword?.let { Arrays.fill(it, '\u0000') }
            closeCompositionSteps(
                { secretStore?.close() },
                { database.close() },
            )?.let(failure::addSuppressed)
            throw failure
        } finally {
            desktopPassword?.let { Arrays.fill(it, '\u0000') }
        }
    }

    override suspend fun launchChild(storage: BusinessDesktopStorageAssembly): CompositionResource {
        if (configuration.agentLaunchMode == BusinessAgentLaunchMode.ExternalDevelopment) {
            val backendConfiguration = BusinessBackendConnectionConfigurationLoader().load(paths)
            val connectionSession = BusinessAgentConnectionSession(
                BusinessAgentDevelopmentSessionFile.awaitRead(
                    paths.agentDevelopmentSession,
                    backendConfiguration,
                ),
            )
            child = BusinessAgentChildHandle(
                identity = connectionSession.identity,
                sequenceTracker = connectionSession.sequenceTracker,
                resource = CompositionResource { },
                connectionSession = connectionSession,
            )
            return child.resource
        }
        val installationId = DesktopInstallationIdentityStore(paths.desktopInstallationId).loadOrCreate()
        val launchPassword = this.storage.backendKeyStorePassword.copyOf()
        try {
            val context = BusinessAgentChildLaunchContext(
                paths = paths,
                desktopInstanceId = installationId,
                backendJar = configuration.backendJar,
                backendKeyStorePassword = launchPassword,
            )
            child = (childLauncher ?: defaultChildLauncher()).launch(context)
            return child.resource
        } finally {
            Arrays.fill(launchPassword, '\u0000')
        }
    }

    override suspend fun connectAgent(
        storage: BusinessDesktopStorageAssembly,
        child: CompositionResource,
    ): CompositionResource {
        agentConnection = (connector ?: defaultConnector()).connect(this.child)
        try {
            connectionLifecycle = AgentConnectionLifecycleProjection(agentConnection.connection, scope)
            rpc = AgentJsonRpcClient(connection = agentConnection.connection, scope = scope)
            return CompositionResource {
                closeCompositionSteps(
                    { rpc.close() },
                    { agentConnection.resource.close() },
                    { scopeJob.cancel() },
                )?.let { throw it }
            }
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                closeCompositionSteps(
                    { agentConnection.resource.close() },
                    { scopeJob.cancel() },
                )
            }?.let(failure::addSuppressed)
            throw failure
        }
    }

    override suspend fun createUi(
        storage: BusinessDesktopStorageAssembly,
        connection: CompositionResource,
    ): BusinessDesktopUiAssembly {
        var conversation: BusinessConversationController? = null
        var providerSettings: BusinessProviderSettingsController? = null
        var desktopCoordinator: BusinessDesktopCoordinator? = null
        var actionHandler: ApplicationActionRequestHandler? = null
        var decisionConnectionObserver: Job? = null
        var suggestionObserver: Job? = null
        var authenticationLifecycle: BusinessAuthenticationLifecycle? = null
        var loginController: BusinessLoginController? = null
        var attachmentHttpClient: HttpClient? = null
        try {
        val businessAgentClient = BusinessAgentClient(rpc, scope)
        val rpcAuthentication = BusinessRpcAuthenticationOperations(
            client = BusinessAuthRpcClient(rpc),
            identityRegistry = identityRegistry,
            desktopInstanceId = this.child.identity.desktopInstanceId,
            desktopSessionId = this.child.identity.desktopSessionId,
            platformId = 0,
            onReady = { identity ->
                desktopCoordinator?.onAuthenticated(
                    identity = identity,
                    catalogEpoch = 1,
                    initialPage = this.storage.screen.pageContext(),
                    initialContextSequence = 1,
                )
            },
            onSignedOut = {
                desktopCoordinator?.signOut()
            },
            onAuthenticationExpiredState = {
                desktopCoordinator?.onAuthenticationExpired()
            },
            onMembershipExpiredState = {
                desktopCoordinator?.onMembershipExpired()
            },
            onRecovering = {
                desktopCoordinator?.clearWorkspace()
            },
            currentConnectionId = {
                (connectionLifecycle.state.value as? AgentSupervisorState.Connected)?.connectionId
            },
        )
        val workbenchController = BusinessWorkbenchController(BusinessWorkbenchRpcClient(rpc))
        val scheduleRpc = BusinessScheduleRpcClient(rpc)
        var attachmentUploadClientRef: BusinessAttachmentUploadClient? = null
        val scheduleController = BusinessScheduleController(scheduleRpc) { epoch, generation ->
            attachmentUploadClientRef?.onIdentityVersionChanged(epoch, generation)
        }
        attachmentHttpClient = createBusinessHttpTransportClient(requestTimeoutMillis = 30_000)
        val attachmentUploadClient = BusinessAttachmentUploadClient(
            prepare = scheduleRpc,
            transport = KtorBusinessAttachmentUploadTransport(attachmentHttpClient),
            endpoint = BusinessLoopbackEndpoint(this.child.identity.localOrigin, this.child.identity),
            identityVersion = {
                scheduleController.state.value.let { state ->
                    if (state.identityEpoch > 0) state.identityEpoch to state.generation else null
                }
            },
        )
        attachmentUploadClientRef = attachmentUploadClient
        val agentUsageGate = ReadyAgentUsageGate(identityRegistry)
        conversation = BusinessConversationController(
            gateway = businessAgentClient,
            store = this.storage.desktopStore,
            usageGate = agentUsageGate,
            scope = scope,
            onAuthStateChanged = rpcAuthentication::reconcileAuthStateChanged,
        )
        val lifecycle = connectionLifecycle
        providerSettings = BusinessProviderSettingsController(
            gateway = businessAgentClient,
            supervisorState = lifecycle.state,
            desktopState = this.storage.desktopStore.state,
            accessGate = identityRegistry.gate,
            usageGate = agentUsageGate,
            scope = scope,
            onProvidersChanged = conversation::acceptProviders,
        )
        val workspace = BusinessWorkspaceController(
            store = this.storage.desktopStore,
            // The gateway owns remote page-context registration. Keep a local projection seam so
            // existing workspace/coordinator transactions remain atomic without emitting legacy RPC.
            contextPublication = BusinessContextPublicationPort { _, _, _, _ -> },
            actionPort = DirectUserApplicationActionPort(this.storage.actionBus),
            nextContextSequence = null,
        )
        val registration = object : BusinessRegistrationPort {
            // Identity, catalog and context are installed by the local Spring Boot gateway.
            override suspend fun bindIdentity(identity: BusinessIdentity) = Unit
            override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) = Unit
            override suspend fun publishSignedOut() = Unit
        }
        desktopCoordinator = BusinessDesktopCoordinator(
            store = this.storage.desktopStore,
            connection = lifecycle,
            registration = registration,
            workspace = workspace,
            scope = scope,
        )
        actionHandler = ApplicationActionRequestHandler(
            rpc = rpc,
            executor = com.wzx.huitai.agent.application.DirectApplicationActionExecutor(this.storage.actionBus),
            executionStore = this.storage.executionStore,
            scopedQuery = this.storage.executionStore,
            trustedIdentity = {
                val snapshot = identityRegistry.snapshot.value
                val active = requireNotNull(snapshot.identity?.takeIf { snapshot.gate == BusinessAccessGateState.READY }) {
                    "No authenticated business identity"
                }
                TrustedApplicationIdentity(active.actionScope(), active.permissions)
            },
            authenticationGate = object : ApplicationAuthenticationGate {
                override fun captureIfReady(): ApplicationAuthenticationSnapshot? =
                    agentUsageGate.captureIfReady()?.let { ready ->
                        ApplicationAuthenticationSnapshot(
                            identity = TrustedApplicationIdentity(
                                ready.identity.actionScope(),
                                ready.identity.permissions,
                            ),
                            generation = ready.generation,
                        )
                    }

                override fun isCurrent(snapshot: ApplicationAuthenticationSnapshot): Boolean {
                    val current = agentUsageGate.captureIfReady() ?: return false
                    return current.generation == snapshot.generation &&
                        current.identity.actionScope() == snapshot.identity.scope &&
                        current.identity.permissions == snapshot.identity.permissions
                }

                override suspend fun <T> withCurrentPermit(
                    snapshot: ApplicationAuthenticationSnapshot,
                    use: suspend () -> T,
                ): T? {
                    val current = agentUsageGate.captureIfReady() ?: return null
                    if (
                        current.generation != snapshot.generation ||
                        current.identity.actionScope() != snapshot.identity.scope ||
                        current.identity.permissions != snapshot.identity.permissions
                    ) return null
                    return agentUsageGate.withCurrentPermit(current, use)
                }
            },
            nextSequence = ::nextSequence,
            now = Instant::now,
            scope = scope,
        )
        desktopCoordinator.start()

        loginController = BusinessLoginController(
            authentication = rpcAuthentication,
            store = BusinessLoginCredentialStore(this.storage.secretStore),
        )
        authenticationLifecycle = BusinessAuthenticationLifecycle(rpcAuthentication, lifecycle.state, scope)
        suggestionObserver = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            this@ProductionBusinessDesktopCompositionFactory.storage.screen.state
                .map { state ->
                    state.suggestionPatch
                        ?.takeUnless { state.suggestionIsStale }
                        .toBusinessSuggestions()
                }
                .distinctUntilChanged()
                .collect(workspace::updateSuggestions)
        }
        decisionConnectionObserver = scope.launch {
            lifecycle.state.collect { state ->
                if (state is AgentSupervisorState.Connected) {
                    this@ProductionBusinessDesktopCompositionFactory.storage.decisions.onAgentConnected()
                } else {
                    this@ProductionBusinessDesktopCompositionFactory.storage.decisions.onAgentDisconnected()
                }
            }
        }
        val attachmentIdFactory = BusinessAttachmentIdFactory()
        val uiComponents = ProductionUiComponents(
            conversationController = conversation,
            providerSettingsController = providerSettings,
            workspaceController = workspace,
            desktopCoordinator = desktopCoordinator,
            actionRequestHandler = actionHandler,
            businessAgentClient = businessAgentClient,
            workbenchController = workbenchController,
            scheduleController = scheduleController,
            attachmentUploadClient = attachmentUploadClient,
            agentClipboardAttachmentRoot = paths.agentClipboardAttachmentRoot,
            clipboardImageAttachmentStore = ClipboardImageAttachmentStore(
                controlledRoot = paths.agentClipboardAttachmentRoot,
                idFactory = attachmentIdFactory,
            ),
            attachmentPicker = BusinessAttachmentPicker(idFactory = attachmentIdFactory),
            scheduleAttachmentPicker = BusinessScheduleAttachmentPicker(idFactory = attachmentIdFactory),
            loginController = loginController,
            logoutController = BusinessLogoutController(
                logout = rpcAuthentication::logout,
                clearSensitiveInput = loginController::clearSensitiveInput,
            ),
            authenticationOperations = rpcAuthentication,
            authenticationGate = identityRegistry.gate,
            authenticationError = rpcAuthentication.lastError,
            identityRegistry = identityRegistry,
            serviceAgreementUrl = legalLinks.serviceAgreementUrl,
            privacyPolicyUrl = legalLinks.privacyPolicyUrl,
        )
        val view = BusinessDesktopRuntimeView(
            desktopState = this.storage.desktopStore.state,
            formState = this.storage.screen.state,
            decisions = this.storage.decisions,
            production = uiComponents,
        )
        return BusinessDesktopUiAssembly(
            userActionBus = this.storage.actionBus,
            agentRequestActionBus = this.storage.actionBus,
            runtimeView = view,
            resource = CompositionResource {
                closeUiStage(
                    desktopCoordinator,
                    suggestionObserver,
                    decisionConnectionObserver,
                    providerSettings,
                    conversation,
                    actionHandler,
                    loginController,
                    authenticationLifecycle,
                    attachmentHttpClient,
                )
            },
            start = {
                rpcAuthentication.prepareStartup()
                val cleanup = legacyCredentialCleanup
                if (cleanup == null) {
                    LegacyOaCredentialAliasCleanup(this.storage.secretStore).cleanup()
                } else {
                    cleanup(this.storage.secretStore)
                }
                loginController.initialize()
                authenticationLifecycle.start()
            },
        )
        } catch (failure: Throwable) {
            withContext(NonCancellable) {
                runCatching {
                    closeUiStage(
                        desktopCoordinator,
                        suggestionObserver,
                        decisionConnectionObserver,
                        providerSettings,
                        conversation,
                        actionHandler,
                        loginController,
                        authenticationLifecycle,
                        attachmentHttpClient,
                    )
                }.exceptionOrNull()
            }?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** 单个 UI stage 内也保证全量逆序清理，避免 stage 尚未返回 root 时泄漏 reader/controller。 */
    private suspend fun closeUiStage(
        desktopCoordinator: BusinessDesktopCoordinator?,
        suggestionObserver: Job?,
        decisionConnectionObserver: Job?,
        providerSettings: BusinessProviderSettingsController?,
        conversation: BusinessConversationController?,
        actionHandler: ApplicationActionRequestHandler?,
        loginController: BusinessLoginController?,
        authenticationLifecycle: BusinessAuthenticationLifecycle?,
        attachmentHttpClient: HttpClient?,
    ) {
        var first: Throwable? = null
        suspend fun close(block: suspend () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                first?.addSuppressed(failure) ?: run { first = failure }
            }
        }
        close { loginController?.close() }
        close { authenticationLifecycle?.shutdown() }
        close { desktopCoordinator?.shutdown() }
        close { suggestionObserver?.cancelAndJoin() }
        close { decisionConnectionObserver?.cancelAndJoin() }
        close { providerSettings?.close() }
        close { conversation?.close() }
        close { actionHandler?.close() }
        close { attachmentHttpClient?.close() }
        close { storage.decisions.shutdown() }
        first?.let { throw it }
    }

    private fun FormPatch?.toBusinessSuggestions(): List<BusinessFieldSuggestion> =
        this?.changes.orEmpty().mapNotNull { change ->
            val value = change.newValue ?: return@mapNotNull null
            val source = change.sourceReferences.firstOrNull()?.let { reference ->
                reference.label ?: reference.type
            } ?: "小律"
            BusinessFieldSuggestion(
                fieldId = change.fieldId,
                value = value,
                source = source,
                confidence = change.confidence,
            )
        }

    private fun nextSequence(): Long = envelopeSequence.incrementAndGet()

    suspend fun packagedSmokeEvidence(): PackagedSmokeEvidence {
        val session = requireNotNull(child.runtimeSession) { "packaged smoke requires the real child process" }
        val activeConnection = agentConnection.connection
        val unauthorizedRejected = unauthorizedHandshakeRejected(session)
        return PackagedSmokeEvidence(
            profile = PackagedSmokeEvidence.PROFILE,
            address = session.address,
            port = session.port,
            runtimeRoot = paths.root,
            desktopRoot = paths.desktopRoot,
            agentRoot = paths.agentRoot,
            desktopDatabase = paths.desktopDatabase,
            agentDatabase = paths.agentDatabase,
            desktopKeyStore = paths.desktopKeyStore,
            agentKeyStore = paths.agentKeyStore,
            tokenFile = paths.agentSessionToken,
            tokenFileDeleted = Files.notExists(paths.agentSessionToken, LinkOption.NOFOLLOW_LINKS),
            unauthorizedHandshakeRejected = unauthorizedRejected,
            authenticatedConnection = activeConnection.state.value == AgentConnectionState.Connected,
             signedOutIdentityBound = identityRegistry.snapshot.value.gate != BusinessAccessGateState.READY &&
                storage.desktopStore.state.value.authenticationStatus ==
                com.wzx.huitai.desktop.state.BusinessAuthenticationStatus.SIGNED_OUT,
            childPid = session.childPid,
        )
    }

    private suspend fun unauthorizedHandshakeRejected(session: BusinessAgentRuntimeSession): Boolean {
        val probeJob = SupervisorJob(scope.coroutineContext[Job])
        val probeScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + probeJob)
        val client = HttpClient(CIO) { install(WebSockets) }
        val transport = KtorAgentTransport(client, probeScope)
        var connection: AgentConnection? = null
        return try {
            val valid = session.connectRequest.identity
            val invalid = DesktopSessionIdentity(
                desktopInstanceId = valid.desktopInstanceId,
                desktopSessionId = valid.desktopSessionId,
                desktopSessionToken = "invalid-smoke-token-${UUID.randomUUID()}",
                localOrigin = valid.localOrigin,
            )
            val unauthorizedConnection = transport.connect(AgentConnectRequest(session.connectRequest.url, invalid))
            connection = unauthorizedConnection
            withTimeout(5_000) {
                unauthorizedConnection.state.first { state ->
                    state is AgentConnectionState.AuthenticationFailed ||
                        state is AgentConnectionState.TransportFailure ||
                        state is AgentConnectionState.Closed ||
                        state == AgentConnectionState.Connected
                }
            } is AgentConnectionState.AuthenticationFailed
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            false
        } finally {
            withContext(NonCancellable) {
                closeCompositionSteps(
                    { connection?.close() },
                    { transport.close() },
                    { probeJob.cancel() },
                    { client.close() },
                )
            }
        }
    }

    private fun defaultChildLauncher(): BusinessAgentChildLauncher = BusinessAgentChildLauncher { context ->
        require(Files.isRegularFile(context.backendJar)) { "bundled business Agent jar is unavailable" }
        val request = BusinessAgentLaunchRequest.create(
            paths = context.paths,
            desktopInstanceId = context.desktopInstanceId,
            backendJar = context.backendJar,
            backendKeyStorePassword = context.backendKeyStorePassword,
        )
        val launcher = BusinessAgentProcessLauncher(
            readinessProbe = BusinessAgentReadinessProbe(productionAuthenticatedProbe()),
        )
        val session = launcher.launch(request)
        BusinessAgentChildHandle(
            identity = session.identity,
            sequenceTracker = session.sequenceTracker,
            resource = CompositionResource { session.close() },
            runtimeSession = session,
        )
    }

    private fun defaultConnector(): BusinessAgentConnector = BusinessAgentConnector { child ->
        val session = requireNotNull(child.connectionSession) {
            "business Agent connection session is missing"
        }
        val httpClient = HttpClient(CIO) { install(WebSockets) }
        try {
            val transport = KtorAgentTransport(httpClient, scope)
            val facade = session.connect(transport, scope)
            BusinessAgentConnectionHandle(
                connection = facade,
                resource = CompositionResource {
                    closeCompositionSteps(
                        { facade.close() },
                        { httpClient.close() },
                    )?.let { throw it }
                },
            )
        } catch (failure: Throwable) {
            runCatching { httpClient.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    /** 每次 readiness 尝试都执行真实带 token 的 Ktor WebSocket 握手并立即释放预约。 */
    private fun productionAuthenticatedProbe(): AuthenticatedWebSocketProbe = AuthenticatedWebSocketProbe { request ->
        val client = HttpClient(CIO) { install(WebSockets) }
        val probeScopeJob = SupervisorJob()
        val probeScope = CoroutineScope(probeScopeJob)
        val transport = KtorAgentTransport(client, probeScope)
        var connection: AgentConnection? = null
        var primaryFailure: Throwable? = null
        try {
            connection = transport.connect(request)
            val state = withTimeout(2_000) {
                connection.state.first { it != AgentConnectionState.Connecting }
            }
            state == AgentConnectionState.Connected
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            withContext(NonCancellable) {
                closeCompositionSteps(
                    { connection?.close() },
                    { transport.close() },
                    { probeScope.cancel() },
                    { client.close() },
                )
            }?.let { cleanupFailure ->
                primaryFailure?.addSuppressed(cleanupFailure) ?: throw cleanupFailure
            }
        }
    }

}

internal fun createBusinessHttpTransportClient(requestTimeoutMillis: Long): HttpClient = HttpClient(CIO) {
    expectSuccess = false
    followRedirects = false
    install(HttpTimeout) { this.requestTimeoutMillis = requestTimeoutMillis }
}

/**
 * 把 supervisor facade 已过滤的真实状态投影给 DesktopCoordinator。
 * lifecycle.shutdown 只收束 UI 状态，底层连接仍由下一阶段 connection resource 关闭。
 */
internal class AgentConnectionLifecycleProjection(
    private val connection: AgentConnection,
    scope: CoroutineScope,
) : BusinessConnectionLifecycle {
    private val stopped = AtomicBoolean(false)
    private val source =
        (connection as? ManagedBusinessAgentConnection)?.supervisorState
            ?: connection.state.map { connectionState ->
                when (connectionState) {
                    AgentConnectionState.Connecting -> AgentSupervisorState.Connecting
                    AgentConnectionState.Connected -> AgentSupervisorState.Connected(connection.connectionId)
                    AgentConnectionState.AuthenticationFailed -> AgentSupervisorState.AuthenticationFailed
                    is AgentConnectionState.TransportFailure,
                    is AgentConnectionState.Closed,
                    -> AgentSupervisorState.Reconnecting(consecutiveFailures = 1, delayMillis = 0)
                }
            }
    private val mutableState = MutableStateFlow<AgentSupervisorState>(AgentSupervisorState.Connecting)
    private val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        source.collect { next -> if (!stopped.get()) mutableState.value = next }
    }
    override val state: StateFlow<AgentSupervisorState> = mutableState

    override suspend fun start() = Unit
    override suspend fun manualRetry(): Boolean =
        (connection as? ManagedBusinessAgentConnection)?.manualRetry() ?: false

    override suspend fun reconnect(expectedConnectionId: String): Boolean =
        (connection as? ManagedBusinessAgentConnection)?.reconnect(expectedConnectionId) ?: false

    override suspend fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        mutableState.value = AgentSupervisorState.Shutdown
        observer.cancelAndJoin()
    }
}

/**
 * Exposes Connected only after identity, catalog, and initial context have all been registered on
 * that exact transport connection. Registration failures are bounded and require an explicit
 * manual retry instead of allowing UI actions through a partially registered connection.
 */
internal class RegisteredAgentConnectionLifecycle(
    private val source: BusinessConnectionLifecycle,
    initialRegisteredConnectionId: String,
    scope: CoroutineScope,
    private val maximumRegistrationAttempts: Int = 3,
    private val retryDelayMillis: suspend (Long) -> Unit = { delay(it) },
    private val register: suspend (String) -> Unit,
) : BusinessConnectionLifecycle {
    private val stopped = AtomicBoolean(false)
    private val registrationMutex = Mutex()
    private val mutableState = MutableStateFlow<AgentSupervisorState>(
        AgentSupervisorState.Connected(initialRegisteredConnectionId),
    )
    @Volatile
    private var registeredConnectionId: String? = initialRegisteredConnectionId
    private var registrationFailureCount: Int = 0
    private val observer = scope.launch(start = CoroutineStart.UNDISPATCHED) {
        source.state.collect { next ->
            if (stopped.get()) return@collect
            when (next) {
                is AgentSupervisorState.Connected -> handleConnected(next.connectionId)
                else -> {
                    registeredConnectionId = null
                    mutableState.value = next
                }
            }
        }
    }

    init {
        require(initialRegisteredConnectionId.isNotBlank()) { "initial registered connection id is required" }
        require(maximumRegistrationAttempts > 0) { "maximum registration attempts must be positive" }
    }

    override val state: StateFlow<AgentSupervisorState> = mutableState

    override suspend fun start() = source.start()

    override suspend fun manualRetry(): Boolean {
        if (stopped.get()) return false
        val connected = source.state.value as? AgentSupervisorState.Connected
        if (mutableState.value == AgentSupervisorState.ManualRetryRequired && connected != null) {
            registrationFailureCount = 0
            mutableState.value = AgentSupervisorState.Connecting
            val accepted = source.reconnect(connected.connectionId)
            if (!accepted) mutableState.value = AgentSupervisorState.ManualRetryRequired
            return accepted
        }
        if (mutableState.value == AgentSupervisorState.ManualRetryRequired) registrationFailureCount = 0
        return source.manualRetry()
    }

    override suspend fun shutdown() {
        if (!stopped.compareAndSet(false, true)) return
        mutableState.value = AgentSupervisorState.Shutdown
        observer.cancelAndJoin()
        source.shutdown()
    }

    private suspend fun handleConnected(connectionId: String) {
        if (registeredConnectionId == connectionId) {
            mutableState.value = AgentSupervisorState.Connected(connectionId)
            return
        }
        if (mutableState.value !is AgentSupervisorState.Reconnecting) {
            mutableState.value = AgentSupervisorState.Connecting
        }
        registerConnection(connectionId)
    }

    private suspend fun registerConnection(connectionId: String): Boolean = registrationMutex.withLock {
        if (stopped.get()) return@withLock false
        if (registeredConnectionId == connectionId) {
            mutableState.value = AgentSupervisorState.Connected(connectionId)
            return@withLock true
        }
        val active = source.state.value as? AgentSupervisorState.Connected
        if (active?.connectionId != connectionId || stopped.get()) return@withLock false
        try {
            register(connectionId)
            val stillActive = source.state.value as? AgentSupervisorState.Connected
            if (stillActive?.connectionId != connectionId || stopped.get()) return@withLock false
            registrationFailureCount = 0
            registeredConnectionId = connectionId
            mutableState.value = AgentSupervisorState.Connected(connectionId)
            true
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            registrationFailureCount += 1
            if (registrationFailureCount >= maximumRegistrationAttempts) {
                mutableState.value = AgentSupervisorState.ManualRetryRequired
                return@withLock false
            }
            mutableState.value = AgentSupervisorState.Reconnecting(
                consecutiveFailures = registrationFailureCount,
                delayMillis = REGISTRATION_RETRY_DELAY_MILLIS,
            )
            retryDelayMillis(REGISTRATION_RETRY_DELAY_MILLIS)
            val stillActive = source.state.value as? AgentSupervisorState.Connected
            if (stillActive?.connectionId != connectionId || stopped.get()) return@withLock false
            if (!source.reconnect(connectionId)) {
                registrationFailureCount = maximumRegistrationAttempts
                mutableState.value = AgentSupervisorState.ManualRetryRequired
            }
            false
        }.also {
            if (!it && registrationFailureCount >= maximumRegistrationAttempts && !stopped.get()) {
                registeredConnectionId = null
            }
        }
    }

    private companion object {
        const val REGISTRATION_RETRY_DELAY_MILLIS = 250L
    }
}

private suspend fun closeCompositionSteps(
    vararg steps: suspend () -> Unit,
): Throwable? {
    var first: Throwable? = null
    steps.forEach { step ->
        try {
            step()
        } catch (failure: Throwable) {
            first?.addSuppressed(failure) ?: run { first = failure }
        }
    }
    return first
}
