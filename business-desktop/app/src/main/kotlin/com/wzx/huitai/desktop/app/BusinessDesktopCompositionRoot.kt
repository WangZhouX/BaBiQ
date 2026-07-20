package com.wzx.huitai.desktop.app

import com.wzx.huitai.action.ActionExecutionContextValidator
import com.wzx.huitai.action.ApplicationActionBus
import com.wzx.huitai.action.ActionResolution
import com.wzx.huitai.action.model.ActionReplayPolicy
import com.wzx.huitai.action.model.ReconciliationPolicy
import com.wzx.huitai.action.port.ActionClock
import com.wzx.huitai.agent.application.ApplicationActionRequestHandler
import com.wzx.huitai.agent.application.ApplicationCatalogClient
import com.wzx.huitai.agent.application.ApplicationContextClient
import com.wzx.huitai.agent.application.ApplicationIdentityClient
import com.wzx.huitai.agent.application.TrustedApplicationIdentity
import com.wzx.huitai.agent.client.AgentConnection
import com.wzx.huitai.agent.client.AgentConnectRequest
import com.wzx.huitai.agent.client.AgentConnectionState
import com.wzx.huitai.agent.client.AgentJsonRpcClient
import com.wzx.huitai.agent.client.AgentSupervisorState
import com.wzx.huitai.agent.client.ApplicationSequenceTracker
import com.wzx.huitai.agent.client.DesktopSessionIdentity
import com.wzx.huitai.agent.client.KtorAgentTransport
import com.wzx.huitai.agent.conversation.BusinessAgentClient
import com.wzx.huitai.agent.protocol.ApplicationProtocol
import com.wzx.huitai.agent.protocol.CatalogEnvelope
import com.wzx.huitai.agent.protocol.CommonApplicationFields
import com.wzx.huitai.agent.protocol.ContextEnvelope
import com.wzx.huitai.agent.protocol.IdentityEnvelope
import com.wzx.huitai.desktop.controller.BusinessConnectionLifecycle
import com.wzx.huitai.desktop.controller.BusinessContextPublicationPort
import com.wzx.huitai.desktop.controller.BusinessConversationController
import com.wzx.huitai.desktop.controller.BusinessDesktopCoordinator
import com.wzx.huitai.desktop.controller.BusinessProviderSettingsController
import com.wzx.huitai.desktop.controller.BusinessRegistrationPort
import com.wzx.huitai.desktop.controller.BusinessWorkspaceController
import com.wzx.huitai.desktop.controller.DirectUserApplicationActionPort
import com.wzx.huitai.desktop.decision.ComposeActionDecisionCoordinator
import com.wzx.huitai.desktop.decision.ComposeApprovalPort
import com.wzx.huitai.desktop.decision.ComposeConfirmationPort
import com.wzx.huitai.desktop.logging.DesktopLoggingBootstrap
import com.wzx.huitai.desktop.runtime.AuthenticatedWebSocketProbe
import com.wzx.huitai.desktop.runtime.BusinessAttachmentIdFactory
import com.wzx.huitai.desktop.runtime.BusinessAgentProcessLauncher
import com.wzx.huitai.desktop.runtime.BusinessAgentReadinessProbe
import com.wzx.huitai.desktop.runtime.BusinessAgentRuntimeSession
import com.wzx.huitai.desktop.runtime.BusinessDesktopRuntimePaths
import com.wzx.huitai.desktop.runtime.ClipboardImageAttachmentStore
import com.wzx.huitai.desktop.runtime.DesktopInstallationIdentityStore
import com.wzx.huitai.desktop.runtime.BusinessAgentLaunchRequest
import com.wzx.huitai.desktop.runtime.ManagedBusinessAgentConnection
import com.wzx.huitai.desktop.smoke.PackagedSmokeEvidence
import com.wzx.huitai.desktop.security.JceksAuthCredentialPersistence
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
import com.wzx.huitai.presentation.context.PageContextPublisher
import com.wzx.huitai.presentation.context.PageContextSanitizer
import com.wzx.huitai.presentation.context.TrustedPageContextIdentity
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
import com.wzx.huitai.security.secret.SecretRef
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Path
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.SecureRandom
import java.time.Instant
import java.util.Arrays
import java.util.Base64
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.encodeToJsonElement

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

    suspend fun initializeIdentity(connection: CompositionResource)
    suspend fun initializeCatalog(connection: CompositionResource)
    suspend fun initializeContext(connection: CompositionResource)
    suspend fun createUi(
        storage: BusinessDesktopStorageAssembly,
        connection: CompositionResource,
    ): BusinessDesktopUiAssembly
}

/**
 * 强制执行业务桌面启动与关闭顺序的唯一 composition root。
 *
 * 启动：lock -> storage -> child -> authenticated connection -> identity -> catalog -> context -> UI。
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
                return BusinessDesktopCompositionRoot(
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
        val value = environment()[ENV_NAME]
        require(!value.isNullOrBlank()) { "$ENV_NAME is required" }
        return value.toCharArray()
    }

    override fun toString(): String = "EnvironmentDesktopSecretBootstrap(value=[REDACTED])"

    companion object {
        const val ENV_NAME = "HUITAI_DESKTOP_KEYSTORE_PASSWORD"
    }
}

/** 生产装配的非敏感位置配置；密码只能由 [desktopSecretBootstrap] 按需提供。 */
class BusinessDesktopProductionConfiguration(
    val home: Path,
    val backendJar: Path,
    val desktopSecretBootstrap: DesktopSecretBootstrap = EnvironmentDesktopSecretBootstrap(),
    val frameworkDemoIdentity: Boolean = false,
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

/** child 阶段返回的稳定身份、计数器和进程资源。 */
class BusinessAgentChildHandle internal constructor(
    val identity: DesktopSessionIdentity,
    val sequenceTracker: ApplicationSequenceTracker,
    val resource: CompositionResource,
    internal val runtimeSession: BusinessAgentRuntimeSession? = null,
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
    val credentialPersistence: JceksAuthCredentialPersistence,
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
    val agentClipboardAttachmentRoot: Path,
    val clipboardImageAttachmentStore: ClipboardImageAttachmentStore,
    val attachmentPicker: BusinessAttachmentPicker,
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
    private lateinit var catalogClient: ApplicationCatalogClient
    private lateinit var contextClient: ApplicationContextClient
    private lateinit var identityClient: ApplicationIdentityClient
    private var identity: BusinessIdentity? = null
    private var startupRegistrationConnectionId: String? = null
    private var lastRegisteredConnectionId: String? = null
    private val contextPublisherLock = Any()
    private var contextPublisherConnectionId: String? = null
    private var pageContextPublisher: PageContextPublisher? = null
    private val registrationPublicationMutex = Mutex()
    private val catalogEpoch = 1L

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
        val database = BusinessDesktopDatabase(paths.desktopDatabase)
        var desktopPassword: CharArray? = null
        var backendPassword: CharArray? = null
        var secretStore: JceksSecretStore? = null
        try {
            desktopPassword = configuration.desktopSecretBootstrap.load()
            require(desktopPassword.isNotEmpty()) { "desktop KeyStore password must not be empty" }
            secretStore = JceksSecretStore(paths.desktopKeyStore, desktopPassword)
            backendPassword = loadOrCreateBackendPassword(secretStore)
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
                credentialPersistence = JceksAuthCredentialPersistence(secretStore),
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
            rpc = AgentJsonRpcClient(agentConnection.connection, scope)
            catalogClient = ApplicationCatalogClient(rpc, this.child.sequenceTracker)
            contextClient = ApplicationContextClient(rpc, this.child.sequenceTracker)
            identityClient = ApplicationIdentityClient(rpc, this.child.sequenceTracker, catalogClient, contextClient)
            identity = if (configuration.frameworkDemoIdentity) {
                BusinessIdentity(
                    desktopInstanceId = this.child.identity.desktopInstanceId,
                    desktopSessionId = this.child.identity.desktopSessionId,
                    authSessionId = UUID.randomUUID().toString(),
                    identityEpoch = 1,
                    userId = "framework-demo-user",
                    tenantId = "framework-demo-tenant",
                    platformId = "framework-demo",
                    roles = setOf("framework-demo"),
                    permissions = setOf("demo.write", "demo.submit"),
                )
            } else {
                null
            }
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

    override suspend fun initializeIdentity(connection: CompositionResource) {
        val connectionId = rpc.connectionId
        identity?.let { identityClient.bind(identityEnvelope(it)) }
            ?: identityClient.update(signedOutIdentityEnvelope())
        check(rpc.connectionId == connectionId) { "Agent connection changed during identity registration" }
        startupRegistrationConnectionId = connectionId
    }

    override suspend fun initializeCatalog(connection: CompositionResource) {
        val connectionId = requireNotNull(startupRegistrationConnectionId) {
            "identity registration must complete first"
        }
        if (identity == null) return
        check(rpc.connectionId == connectionId) { "Agent connection changed before catalog registration" }
        catalogClient.register(catalogEnvelope(requireNotNull(identity)))
        check(rpc.connectionId == connectionId) { "Agent connection changed during catalog registration" }
    }

    override suspend fun initializeContext(connection: CompositionResource) {
        val connectionId = requireNotNull(startupRegistrationConnectionId) {
            "identity registration must complete first"
        }
        if (identity == null) {
            lastRegisteredConnectionId = connectionId
            return
        }
        check(rpc.connectionId == connectionId) { "Agent connection changed before context registration" }
        contextClient.publish(contextEnvelope(contextSequence = 1, storage.screen.pageContext()))
        check(rpc.connectionId == connectionId) { "Agent connection changed during context registration" }
        lastRegisteredConnectionId = connectionId
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
        try {
        val businessAgentClient = BusinessAgentClient(rpc, scope)
        conversation = BusinessConversationController(businessAgentClient, this.storage.desktopStore, scope)
        val lifecycle = RegisteredAgentConnectionLifecycle(
            source = connectionLifecycle,
            initialRegisteredConnectionId = requireNotNull(lastRegisteredConnectionId) {
                "initial Agent registration was not committed"
            },
            scope = scope,
            register = { connectionId -> registerActiveConnection(connectionId) },
        )
        providerSettings = BusinessProviderSettingsController(
            gateway = businessAgentClient,
            supervisorState = lifecycle.state,
            desktopState = this.storage.desktopStore.state,
            scope = scope,
            onProvidersChanged = conversation::acceptProviders,
        )
        val workspace = BusinessWorkspaceController(
            store = this.storage.desktopStore,
            contextPublication = BusinessContextPublicationPort { _, _, contextSequence, snapshot ->
                registrationPublicationMutex.withLock {
                    check(lastRegisteredConnectionId == rpc.connectionId) {
                        "Agent connection is not fully registered"
                    }
                    contextClient.publish(contextEnvelope(contextSequence, snapshot))
                }
            },
            actionPort = DirectUserApplicationActionPort(this.storage.actionBus),
        )
        val registration = object : BusinessRegistrationPort {
            override suspend fun bindIdentity(identity: BusinessIdentity) {
                identityClient.bind(identityEnvelope(identity))
            }

            override suspend fun registerCatalog(identity: BusinessIdentity, catalogEpoch: Long) {
                catalogClient.register(catalogEnvelope(identity, catalogEpoch))
            }
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
                val active = requireNotNull(this.storage.desktopStore.state.value.identity) {
                    "No authenticated business identity"
                }
                TrustedApplicationIdentity(active.actionScope(), active.permissions)
            },
            nextSequence = ::nextSequence,
            now = Instant::now,
            scope = scope,
        )
        identity?.let { activeIdentity ->
            workspace.attachPublishedIdentity(
                identity = activeIdentity,
                catalogEpoch = catalogEpoch,
                snapshot = this.storage.screen.pageContext(),
                lifecycleGeneration = 1,
                publishedContextSequence = 1,
            )
        }
        desktopCoordinator.start()
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
            agentClipboardAttachmentRoot = paths.agentClipboardAttachmentRoot,
            clipboardImageAttachmentStore = ClipboardImageAttachmentStore(
                controlledRoot = paths.agentClipboardAttachmentRoot,
                idFactory = attachmentIdFactory,
            ),
            attachmentPicker = BusinessAttachmentPicker(idFactory = attachmentIdFactory),
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
                )
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
    ) {
        var first: Throwable? = null
        suspend fun close(block: suspend () -> Unit) {
            try {
                block()
            } catch (failure: Throwable) {
                first?.addSuppressed(failure) ?: run { first = failure }
            }
        }
        close { desktopCoordinator?.shutdown() }
        close { suggestionObserver?.cancelAndJoin() }
        close { decisionConnectionObserver?.cancelAndJoin() }
        close { providerSettings?.close() }
        close { conversation?.close() }
        close { actionHandler?.close() }
        close { storage.decisions.shutdown() }
        first?.let { throw it }
    }

    private fun FormPatch?.toBusinessSuggestions(): List<BusinessFieldSuggestion> =
        this?.changes.orEmpty().mapNotNull { change ->
            val value = change.newValue ?: return@mapNotNull null
            val source = change.sourceReferences.firstOrNull()?.let { reference ->
                reference.label ?: reference.type
            } ?: "Agent"
            BusinessFieldSuggestion(
                fieldId = change.fieldId,
                value = value,
                source = source,
                confidence = change.confidence,
            )
        }

    private fun identityEnvelope(value: BusinessIdentity): IdentityEnvelope = IdentityEnvelope(
        common = common(value),
        authenticated = true,
        roles = value.roles,
        permissions = value.permissions,
    )

    private fun catalogEnvelope(
        value: BusinessIdentity,
        epoch: Long = catalogEpoch,
    ): CatalogEnvelope {
        val payload = buildJsonObject {
            put("catalogRevision", "framework-demo-v1")
            put("actions", buildJsonObject {
                this@ProductionBusinessDesktopCompositionFactory.storage.catalog.actions.forEach { action ->
                    val descriptor = ApplicationProtocol.JSON
                        .encodeToJsonElement(action.descriptor)
                        .jsonObject
                    put(action.descriptor.id, JsonObject(descriptor + ("enabled" to JsonPrimitive(true))))
                }
            })
        }
        return CatalogEnvelope(
            common = common(value),
            catalogEpoch = epoch,
            contextSequence = 1,
            payloadSize = payload.encodedSize(),
            payload = payload,
        )
    }

    private fun contextEnvelope(
        contextSequence: Long,
        snapshot: com.wzx.huitai.presentation.context.PageContextSnapshot,
    ): ContextEnvelope {
        val activeIdentity = requireNotNull(identity) { "Page context requires authenticated business identity" }
        val connectionId = rpc.connectionId
        val published = synchronized(contextPublisherLock) {
            val publisher = if (contextPublisherConnectionId == connectionId) {
                requireNotNull(pageContextPublisher)
            } else {
                PageContextPublisher(
                    identity = TrustedPageContextIdentity(
                        desktopInstanceId = activeIdentity.desktopInstanceId,
                        authSessionId = activeIdentity.authSessionId,
                        identityEpoch = activeIdentity.identityEpoch,
                    ),
                    sanitizer = PageContextSanitizer(),
                ).also {
                    contextPublisherConnectionId = connectionId
                    pageContextPublisher = it
                }
            }
            publisher.publish(snapshot, catalogEpoch, contextSequence)
        }
        val encodedSnapshot = ApplicationProtocol.JSON.encodeToJsonElement(published.payload).jsonObject
        val payload = JsonObject(
            encodedSnapshot + mapOf(
                "contextRevision" to JsonPrimitive(snapshot.revision),
                "pageType" to JsonPrimitive("framework-demo"),
            ),
        )
        return ContextEnvelope(
            common = common(activeIdentity),
            catalogEpoch = catalogEpoch,
            contextSequence = contextSequence,
            payloadSize = payload.encodedSize(),
            payload = payload,
        )
    }

    private fun common(value: BusinessIdentity): CommonApplicationFields = CommonApplicationFields(
        protocolVersion = ApplicationProtocol.PROTOCOL_VERSION,
        desktopInstanceId = value.desktopInstanceId,
        desktopSessionId = value.desktopSessionId,
        authSessionId = value.authSessionId,
        identityEpoch = value.identityEpoch,
        sequence = nextSequence(),
        generatedAt = Instant.now().toString(),
        userId = value.userId,
        tenantId = value.tenantId,
        platformId = value.platformId,
    )

    private fun nextSequence(): Long = envelopeSequence.incrementAndGet()

    private fun signedOutIdentityEnvelope(): IdentityEnvelope = IdentityEnvelope(
        common = CommonApplicationFields(
            protocolVersion = ApplicationProtocol.PROTOCOL_VERSION,
            desktopInstanceId = child.identity.desktopInstanceId,
            desktopSessionId = child.identity.desktopSessionId,
            authSessionId = null,
            identityEpoch = 1,
            sequence = nextSequence(),
            generatedAt = Instant.now().toString(),
            userId = null,
            tenantId = null,
            platformId = null,
        ),
        authenticated = false,
        roles = emptySet(),
        permissions = emptySet(),
    )

    private suspend fun registerActiveConnection(connectionId: String) = registrationPublicationMutex.withLock {
        check(rpc.connectionId == connectionId) { "Agent connection changed before identity registration" }
        val activeIdentity = identity
        if (activeIdentity == null) {
            identityClient.update(signedOutIdentityEnvelope())
            check(rpc.connectionId == connectionId) { "Agent connection changed during signed-out registration" }
            lastRegisteredConnectionId = connectionId
            return@withLock
        }
        identityClient.bind(identityEnvelope(activeIdentity))
        check(rpc.connectionId == connectionId) { "Agent connection changed during identity registration" }
        catalogClient.register(catalogEnvelope(activeIdentity))
        check(rpc.connectionId == connectionId) { "Agent connection changed during catalog registration" }
        contextClient.publish(
            contextEnvelope(
                contextSequence = 1,
                snapshot = storage.screen.pageContext(),
            ),
        )
        check(rpc.connectionId == connectionId) { "Agent connection changed during context registration" }
        lastRegisteredConnectionId = connectionId
    }

    suspend fun packagedSmokeEvidence(): PackagedSmokeEvidence {
        require(!configuration.frameworkDemoIdentity) { "packaged smoke must use signed-out identity" }
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
            signedOutIdentityBound = identity == null &&
                lastRegisteredConnectionId == rpc.connectionId &&
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
        val session = requireNotNull(child.runtimeSession) { "production child runtime session is missing" }
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

    private fun loadOrCreateBackendPassword(secretStore: JceksSecretStore): CharArray {
        val ref = SecretRef.parse(BACKEND_PASSWORD_ALIAS)
        secretStore.load(ref)?.let { return it }
        val bytes = ByteArray(32).also(SecureRandom()::nextBytes)
        val generated = try {
            Base64.getUrlEncoder().withoutPadding().encodeToString(bytes).toCharArray()
        } finally {
            Arrays.fill(bytes, 0)
        }
        try {
            secretStore.upsert(ref.value, generated)
            return generated.copyOf()
        } finally {
            Arrays.fill(generated, '\u0000')
        }
    }

    private companion object {
        const val BACKEND_PASSWORD_ALIAS = "huitai.backend.keystore.password.v1"
    }
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

private fun JsonObject.encodedSize(): Int = toString().toByteArray(Charsets.UTF_8).size

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
