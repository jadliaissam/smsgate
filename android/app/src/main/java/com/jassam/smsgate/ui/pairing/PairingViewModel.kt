package com.jassam.smsgate.ui.pairing

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
) {
    val canScan: Boolean get() = playServices

    override fun toString(): String =
        "PairingUiState(mode=$mode, deviceName=$deviceName, serverUrl=$serverUrl, code=${if (code.isEmpty()) "" else "***"}, " +
            "email=${if (email.isEmpty()) "" else "***"}, password=${if (password.isEmpty()) "" else "***"}, " +
            "playServices=$playServices, busy=$busy, error=$error, fieldErrors=$fieldErrors, paired=$paired)"
}

/**
 * Pairing screen state: the three ways in (scan, code, login), field validation
 * and the register call through [Pairer]. Navigation away happens when the
 * credential store reports Paired; [PairingUiState.paired] mirrors it.
 */
class PairingViewModel(
    private val pairer: Pairer,
    defaultServerUrl: String,
    defaultName: String,
    playServices: Boolean,
    private val normalizeServerUrl: (String) -> String?,
) : ViewModel() {
    private val _state = MutableStateFlow(
        PairingUiState(deviceName = defaultName, serverUrl = defaultServerUrl, playServices = playServices),
    )
    val state: StateFlow<PairingUiState> = _state.asStateFlow()

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

    /** A scanned QR: fill the code form from it and pair straight away. */
    fun onQrScanned(raw: String) {
        if (_state.value.busy) return
        val link = PairingQr.parse(raw)
        if (link == null) {
            _state.update { it.copy(error = PairingError.InvalidQr, fieldErrors = emptyMap()) }
            return
        }
        _state.update { it.copy(mode = PairingMode.Code, serverUrl = link.server, code = link.code, error = null, fieldErrors = emptyMap()) }
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
            } catch (e: Throwable) {
                _state.update { it.copy(busy = false) }
                throw e
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
