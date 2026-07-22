package com.wzx.huitai.desktop.auth

import com.wzx.huitai.desktop.security.BusinessLoginCredentialStore
import com.wzx.huitai.desktop.security.LocalCredentialStoreUnavailableException
import com.wzx.huitai.desktop.security.RememberedLoginInvalidException
import com.wzx.huitai.integration.oa.auth.OaTenantCandidate
import java.util.Arrays
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex

interface BusinessAuthenticationOperations {
    val gate: StateFlow<BusinessAccessGateState>
    suspend fun findTenantCandidates(account: String): List<OaTenantCandidate>
    fun enterTenantSelection()
    fun cancelTenantSelection()
    suspend fun authenticate(account: String, password: CharArray, candidate: OaTenantCandidate)
}

class RememberedLoginValue(
    val account: String,
    password: CharArray,
) : AutoCloseable {
    private val password = password.copyOf()
    private var closed = false

    @Synchronized
    fun copyPassword(): CharArray {
        check(!closed) { "Remembered login is closed" }
        return password.copyOf()
    }

    @Synchronized
    override fun close() {
        if (closed) return
        Arrays.fill(password, '\u0000')
        closed = true
    }

    override fun toString(): String = "RememberedLoginValue(account=[REDACTED], password=[REDACTED])"
}

interface BusinessRememberedLoginPort {
    fun load(): RememberedLoginValue?
    fun saveOrReplace(account: String, password: CharArray)
    fun clear()
}

class StoredBusinessRememberedLoginPort(
    private val store: BusinessLoginCredentialStore,
) : BusinessRememberedLoginPort {
    override fun load(): RememberedLoginValue? = try {
        store.load()?.use { remembered ->
            val copy = remembered.copyPassword()
            try {
                RememberedLoginValue(remembered.account, copy)
            } finally {
                Arrays.fill(copy, '\u0000')
            }
        }
    } catch (failure: RememberedLoginInvalidException) {
        throw BusinessLoginException(BusinessLoginErrorCode.REMEMBERED_LOGIN_INVALID)
    } catch (failure: LocalCredentialStoreUnavailableException) {
        throw BusinessLoginException(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE)
    }

    override fun saveOrReplace(account: String, password: CharArray) = store.saveOrReplace(account, password)
    override fun clear() = store.clear()
}

class BusinessLoginController(
    private val authentication: BusinessAuthenticationOperations,
    private val rememberedLogin: BusinessRememberedLoginPort,
) : AutoCloseable {
    constructor(authentication: BusinessAuthenticationOperations, store: BusinessLoginCredentialStore) :
        this(authentication, StoredBusinessRememberedLoginPort(store))

    private val closed = AtomicBoolean(false)
    private val requestMutex = Mutex()
    private val mutableState = MutableStateFlow(BusinessLoginState())
    val state: StateFlow<BusinessLoginState> = mutableState.asStateFlow()

    suspend fun initialize() {
        if (closed.get()) return
        try {
            rememberedLogin.load()?.use { remembered ->
                val password = remembered.copyPassword()
                try {
                    mutableState.value = mutableState.value.copy(
                        account = remembered.account,
                        password = password.concatToString(),
                        remember = true,
                        notice = null,
                    )
                } finally {
                    Arrays.fill(password, '\u0000')
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BusinessLoginException) {
            mutableState.value = mutableState.value.copy(notice = BusinessLoginMessage(failure.code), error = null)
        } catch (_: Throwable) {
            mutableState.value = mutableState.value.copy(
                error = BusinessLoginMessage(BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED),
            )
        }
    }

    fun updateAccount(value: String) = update { copy(account = value, error = null, notice = null) }
    fun updatePassword(value: String) = update { copy(password = value, error = null, notice = null) }
    fun updateAgreement(value: Boolean) = update { copy(agreementAccepted = value, error = null, notice = null) }

    fun updateRemember(value: Boolean) {
        update { copy(remember = value, error = null, notice = null) }
        if (!value) {
            try {
                rememberedLogin.clear()
            } catch (_: LocalCredentialStoreUnavailableException) {
                fail(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE)
            } catch (_: Throwable) {
                fail(BusinessLoginErrorCode.LOCAL_CREDENTIAL_STORE_FAILED)
            }
        }
    }

    suspend fun submit() {
        if (closed.get() || mutableState.value.submitting) return
        val validation = validate(mutableState.value)
        if (validation != null) {
            fail(validation)
            return
        }
        update { copy(slider = BusinessSliderState.REQUESTED, error = null, notice = null) }
    }

    fun dismissSlider() = update { copy(slider = BusinessSliderState.IDLE) }

    suspend fun completeSlider(success: Boolean) {
        if (closed.get() || mutableState.value.slider != BusinessSliderState.REQUESTED) return
        update { copy(slider = BusinessSliderState.IDLE) }
        if (!success || !requestMutex.tryLock()) return
        try {
            update { copy(submitting = true, error = null, notice = null) }
            val snapshot = mutableState.value
            val candidates = authentication.findTenantCandidates(snapshot.account)
            val available = candidates.filterNot { it.tenantEnterStatus == 1 || it.tenantEnterStatus == 2 }
            when {
                candidates.isEmpty() -> {
                    authentication.cancelTenantSelection()
                    fail(BusinessLoginErrorCode.ACCOUNT_NOT_FOUND)
                }
                available.isEmpty() -> {
                    authentication.cancelTenantSelection()
                    update { copy(tenantCandidates = candidates.toStates()) }
                    fail(BusinessLoginErrorCode.TENANT_UNAVAILABLE)
                }
                available.size == 1 -> authenticate(snapshot, available.single())
                else -> {
                    authentication.enterTenantSelection()
                    update { copy(tenantCandidates = candidates.toStates()) }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BusinessAuthenticationException) {
            if (failure.code == BusinessLoginErrorCode.INVALID_CREDENTIALS) clearPassword()
            fail(failure.code)
        } catch (_: Throwable) {
            fail(BusinessLoginErrorCode.REMOTE_UNAVAILABLE)
        } finally {
            update { copy(submitting = false) }
            requestMutex.unlock()
        }
    }

    suspend fun selectTenant(candidate: OaTenantCandidate) {
        if (closed.get() || mutableState.value.submitting) return
        val option = mutableState.value.tenantCandidates.firstOrNull { it.candidate == candidate } ?: return
        if (!option.enabled || !requestMutex.tryLock()) return
        try {
            update { copy(submitting = true, error = null, notice = null) }
            authenticate(mutableState.value, candidate)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: BusinessAuthenticationException) {
            if (failure.code == BusinessLoginErrorCode.INVALID_CREDENTIALS) clearPassword()
            fail(failure.code)
        } catch (_: Throwable) {
            fail(BusinessLoginErrorCode.REMOTE_UNAVAILABLE)
        } finally {
            update { copy(submitting = false) }
            requestMutex.unlock()
        }
    }

    fun cancelTenantSelection() {
        authentication.cancelTenantSelection()
        update {
            copy(
                tenantCandidates = emptyList(),
                error = null,
                notice = BusinessLoginMessage(BusinessLoginErrorCode.TENANT_SELECTION_CANCELLED),
            )
        }
    }

    fun clearSensitiveInput() = clearPassword()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        clearPassword()
    }

    override fun toString(): String = "BusinessLoginController(state=[REDACTED])"

    private suspend fun authenticate(snapshot: BusinessLoginState, candidate: OaTenantCandidate) {
        val password = snapshot.password.toCharArray()
        val authenticationPassword = password.copyOf()
        try {
            authentication.authenticate(snapshot.account, authenticationPassword, candidate)
            if (authentication.gate.value != BusinessAccessGateState.READY) {
                throw BusinessAuthenticationException(BusinessLoginErrorCode.AGENT_REGISTRATION_FAILED)
            }
            clearPassword()
            if (snapshot.remember) {
                rememberedLogin.saveOrReplace(snapshot.account, password)
            } else {
                rememberedLogin.clear()
            }
            update { copy(tenantCandidates = emptyList(), error = null, notice = null) }
        } catch (failure: LocalCredentialStoreUnavailableException) {
            throw BusinessAuthenticationException(BusinessLoginErrorCode.LOCAL_KEYSTORE_UNAVAILABLE)
        } finally {
            Arrays.fill(authenticationPassword, '\u0000')
            Arrays.fill(password, '\u0000')
        }
    }

    private fun clearPassword() = update { copy(password = "") }
    private fun fail(code: BusinessLoginErrorCode) = update { copy(error = BusinessLoginMessage(code), notice = null) }
    private fun update(transform: BusinessLoginState.() -> BusinessLoginState) {
        mutableState.value = mutableState.value.transform()
    }

    private fun validate(state: BusinessLoginState): BusinessLoginErrorCode? = when {
        !isAccountValid(state.account) -> BusinessLoginErrorCode.INVALID_ACCOUNT
        !PASSWORD.matches(state.password) -> BusinessLoginErrorCode.INVALID_PASSWORD_FORMAT
        !state.agreementAccepted -> BusinessLoginErrorCode.AGREEMENT_REQUIRED
        else -> null
    }

    private fun isAccountValid(account: String): Boolean = MOBILE.matches(account) || EMAIL.matches(account)
    private fun List<OaTenantCandidate>.toStates() = map {
        BusinessTenantCandidateState(it, it.tenantEnterStatus != 1 && it.tenantEnterStatus != 2)
    }

    private companion object {
        val MOBILE = Regex("^1[3-9][0-9]{9}$")
        val EMAIL = Regex("^[A-Za-z0-9.!#$%&'*+/=?^_`{|}~-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)+$")
        val PASSWORD = Regex("^(?=.*[A-Za-z])(?=.*[0-9])[A-Za-z0-9]{8,16}$")
    }
}
