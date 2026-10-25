package com.jassam.smsgate.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jassam.smsgate.auth.PairingState
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class PairingMode { Choose, Code, Login }

enum class Field { Name, Server, Code, Email, Password }

enum class FieldError { Required, InvalidServer, IncompleteCode, InvalidEmail, TooLong }

/** Pairing form state. [password] is held only here (never saved); [toString] redacts it. */
data class PairingUiState(
    val mode: PairingMode = PairingMode.Choose,
    val deviceName: String,
    val serverUrl: String,
    /** Normalised code without the dash (shown as XXXX-XXXX by a visual transformation). */
    val code: String = "",
    val email: String = "",
    val password: String = "",
    /** Google Play services present: QR scanning and FCM (D11). */
    val playServices: Boolean,
    val busy: Boolean = false,
    val error: PairingError? = null,
    val fieldErrors: Map<Field, FieldError> = emptyMap(),
    val paired: Boolean = false,
    /** A scanned QR for a server other than the built-in default, waiting for the user's OK. */
    val pendingQr: PendingQr? = null,
) {
    val canScan: Boolean get() = playServices

    override fun toString(): String =
        "PairingUiState(mode=$mode, deviceName=$deviceName, serverUrl=$serverUrl, code=${if (code.isEmpty()) "" else "***"}, " +
            "email=${if (email.isEmpty()) "" else "***"}, password=${if (password.isEmpty()) "" else "***"}, " +
            "playServices=$playServices, busy=$busy, error=$error, fieldErrors=$fieldErrors, paired=$paired, pendingQr=$pendingQr)"
}

/**
 * A scanned pairing QR awaiting confirmation. [serverUrl] is normalised;
 * [host] is what the user is asked to trust (see [ServerHost.display]).
 */
data class PendingQr(val serverUrl: String, val host: String, val code: String) {
    override fun toString(): String = "PendingQr(serverUrl=$serverUrl, host=$host, code=***)"
}

/**
 * Pairing screen state: the three ways in (scan, code, login), field validation
 * and the register call through [Pairer]. Navigation away happens when the
 * credential store reports Paired; [PairingUiState.paired] mirrors it.
 */
class PairingViewModel(
    private val pairer: Pairer,
    /** BuildConfig.DEFAULT_SERVER_URL: prefills the form, and the one server a QR may pair with unconfirmed. */
    defaultServerUrl: String,
    defaultName: String,
    playServices: Boolean,
    private val normalizeServerUrl: (String) -> String?,
    /** The credential store's pairing state; used to start over after an unpair. */
    pairingStates: Flow<PairingState> = emptyFlow(),
) : ViewModel() {
    private val officialServer: String? = normalizeServerUrl(defaultServerUrl)
    private val initial = PairingUiState(deviceName = defaultName, serverUrl = defaultServerUrl, playServices = playServices)
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

    init {
        // This ViewModel is activity-scoped and outlives the paired period. When the
        // phone is unpaired later (A8's Unpair, or a 401 wipe), start from a clean form
        // instead of showing the used code and paired = true. Keeps the device name
        // and server the user chose.
        viewModelScope.launch {
            pairingStates.collect { p ->
                if (p is PairingState.Unpaired) {
                    _state.update {
                        if (!it.paired) it else initial.copy(deviceName = it.deviceName, serverUrl = it.serverUrl)
                    }
                }
            }
        }
    }

    fun setMode(mode: PairingMode) = _state.update {
        if (it.busy) it else it.copy(mode = mode, error = null, fieldErrors = emptyMap())
    }

    fun onNameChange(v: String) = edit(Field.Name) { it.copy(deviceName = v) }
    fun onServerChange(v: String) = edit(Field.Server) { it.copy(serverUrl = v) }
    fun onCodeChange(v: String) = edit(Field.Code) { it.copy(code = PairingCode.normalize(v)) }
    fun onEmailChange(v: String) = edit(Field.Email) { it.copy(email = v) }
    fun onPasswordChange(v: String) = edit(Field.Password) { it.copy(password = v) }

    fun dismissError() = _state.update { it.copy(error = null) }

    /** The scanner could not start (no module, Play services error): suggest typing the code. */
    fun onScanUnavailable() = _state.update { it.copy(mode = PairingMode.Code, error = PairingError.ScannerUnavailable) }

    /**
     * A scanned QR. Nothing is sent yet: a QR naming any server other than the
     * build-time default ([defaultServerUrl], compared after normalisation) is held
     * in [PairingUiState.pendingQr] until [confirmQr], because registering sends the
     * phone's name, SIMs and push token to that server. The default server pairs
     * straight away. Typed addresses need no confirmation (the user chose them).
     */
    fun onQrScanned(raw: String) {
        if (_state.value.busy) return
        val link = PairingQr.parse(raw)
        val server = link?.let { normalizeServerUrl(it.server) }
        if (link == null || server == null) {
            _state.update { it.copy(error = PairingError.InvalidQr, fieldErrors = emptyMap(), pendingQr = null) }
            return
        }
        if (server == officialServer) {
            pairWithQr(server, link.code)
        } else {
            _state.update { it.copy(pendingQr = PendingQr(server, ServerHost.display(server), link.code), error = null, fieldErrors = emptyMap()) }
        }
    }

    /**
     * The user trusts the scanned server: register with it. [shown] is the QR the
     * dialog displayed; if another scan replaced it meanwhile, nothing happens.
     */
    fun confirmQr(shown: PendingQr) {
        val pending = _state.value.pendingQr ?: return
        if (pending != shown) return
        pairWithQr(pending.serverUrl, pending.code)
    }

    /** The user declined the scanned server: forget the QR, send nothing. */
    fun cancelQr() = _state.update { it.copy(pendingQr = null) }

    private fun pairWithQr(server: String, code: String) {
        if (_state.value.busy) return
        _state.update {
            it.copy(mode = PairingMode.Code, serverUrl = server, code = code, pendingQr = null, error = null, fieldErrors = emptyMap())
        }
        submit()
    }

    /** Submits the current form (code or login). Ignored while a request is in flight. */
    fun submit() {
        val s = _state.value
        if (s.busy) return
        val errors = validate(s)
        if (errors.isNotEmpty()) {
            _state.update { it.copy(fieldErrors = errors, error = null) }
            return
        }
        val method = when (s.mode) {
            PairingMode.Login -> PairingMethod.Login(s.serverUrl, s.email, s.password)
            else -> PairingMethod.Code(s.serverUrl, s.code)
        }
        _state.update { it.copy(busy = true, error = null, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            val result = try {
                pairer.pair(method, s.deviceName, withPush = s.playServices)
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw e
            } catch (e: Exception) {
                // Never crash the app from viewModelScope: anything unexpected is a generic error.
                PairingResult.Failure(PairingError.Unknown(e.javaClass.simpleName))
            }
            _state.update {
                when (result) {
                    PairingResult.Success -> it.copy(busy = false, paired = true, password = "")
                    is PairingResult.Failure -> failed(it, result.error)
                }
            }
        }
    }

    private fun failed(s: PairingUiState, error: PairingError): PairingUiState = when (error) {
        PairingError.InvalidServer -> s.copy(busy = false, fieldErrors = mapOf(Field.Server to FieldError.InvalidServer))
        PairingError.InvalidName -> s.copy(busy = false, fieldErrors = mapOf(Field.Name to FieldError.TooLong))
        else -> s.copy(busy = false, error = error)
    }

    private fun validate(s: PairingUiState): Map<Field, FieldError> = buildMap {
        val name = s.deviceName.trim()
        when {
            name.isEmpty() -> put(Field.Name, FieldError.Required)
            !DeviceName.isValid(name) -> put(Field.Name, FieldError.TooLong)
        }
        when {
            s.serverUrl.isBlank() -> put(Field.Server, FieldError.Required)
            normalizeServerUrl(s.serverUrl) == null -> put(Field.Server, FieldError.InvalidServer)
        }
        if (s.mode == PairingMode.Login) {
            val email = s.email.trim()
            when {
                email.isEmpty() -> put(Field.Email, FieldError.Required)
                !looksLikeEmail(email) -> put(Field.Email, FieldError.InvalidEmail)
            }
            if (s.password.isEmpty()) put(Field.Password, FieldError.Required)
        } else if (!PairingCode.isComplete(s.code)) {
            put(Field.Code, FieldError.IncompleteCode)
        }
    }

    private fun edit(field: Field, change: (PairingUiState) -> PairingUiState) = _state.update {
        if (it.busy) it else change(it).copy(fieldErrors = it.fieldErrors - field)
    }

    private companion object {
        // A light check only; the server is the authority on accounts.
        fun looksLikeEmail(e: String): Boolean {
            val at = e.indexOf('@')
            return at > 0 && at < e.length - 1 && e.none { it.isWhitespace() }
        }
    }
}
