package com.salahoard.mustnotify.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salahoard.mustnotify.api.ApiResult
import com.salahoard.mustnotify.api.sha1Hex
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val account: String = "",
    val password: String = "",
    val loading: Boolean = false,
    val error: String? = null,
)

class LoginViewModel(private val deps: AppDependencies) : ViewModel() {
    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onAccountChange(value: String) = _state.update { it.copy(account = value, error = null) }

    /** The vendor accepts at most 32 characters. */
    fun onPasswordChange(value: String) = _state.update { it.copy(password = value.take(MAX_PASSWORD_LENGTH), error = null) }

    fun submit(onLoggedIn: () -> Unit) {
        val current = _state.value
        if (current.loading) return
        if (current.account.isBlank() || current.password.isEmpty()) {
            _state.update { it.copy(error = "Enter your account and password.") }
            return
        }
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            // The password is hashed here and only the token the server returns is kept; the password is never stored.
            val result = deps.api.login(current.account, sha1Hex(current.password))
            when (result) {
                is ApiResult.Ok -> {
                    deps.secrets.saveToken(result.value.token)
                    _state.value = LoginUiState()
                    onLoggedIn()
                }
                is ApiResult.BadCredentials -> fail("The account or password is incorrect.")
                is ApiResult.NetworkError -> fail("Couldn't reach the server. Check your connection and try again.")
                is ApiResult.ServerError -> fail("The server returned an error (code ${result.code}).")
                is ApiResult.AuthRequired, is ApiResult.BadResponse -> fail("Unexpected response from the server.")
            }
        }
    }

    private fun fail(message: String) = _state.update { it.copy(loading = false, error = message) }

    companion object {
        const val MAX_PASSWORD_LENGTH = 32
    }
}
