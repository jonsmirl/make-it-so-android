package com.example.makeitso.screens.sign_up

import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.makeitso.R.string as AppText
import com.example.makeitso.common.error.ErrorMessage
import com.example.makeitso.common.error.ErrorMessage.Companion.toErrorMessage
import com.example.makeitso.common.error.ErrorMessage.ResourceError
import com.example.makeitso.common.ext.isValidEmail
import com.example.makeitso.common.ext.isValidPassword
import com.example.makeitso.common.ext.passwordMatches
import com.example.makeitso.model.database.repository.TaskRepository
import com.example.makeitso.model.service.AccountService
import com.example.makeitso.model.service.CrashlyticsService
import com.example.makeitso.model.service.FirestoreService
import com.google.android.gms.tasks.Task
import com.google.firebase.auth.AuthResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SignUpViewModel @Inject constructor(
    private val accountService: AccountService,
    private val firestoreService: FirestoreService,
    private val crashlyticsService: CrashlyticsService,
    private val taskRepository: TaskRepository
) : ViewModel() {
    var uiState = mutableStateOf(SignUpUiState())
        private set

    val snackbarChannel = Channel<ErrorMessage>(Channel.CONFLATED)

    private val email get() = uiState.value.email
    private val password get() = uiState.value.password

    private val exceptionHandler = CoroutineExceptionHandler { _, throwable ->
        snackbarChannel.trySend(ResourceError(AppText.generic_error))
        viewModelScope.launch { crashlyticsService.logNonFatalCrash(throwable) }
    }

    fun onEmailChange(newValue: String) {
        uiState.value = uiState.value.copy(email = newValue)
    }

    fun onPasswordChange(newValue: String) {
        uiState.value = uiState.value.copy(password = newValue)
    }

    fun onRepeatPasswordChange(newValue: String) {
        uiState.value = uiState.value.copy(repeatPassword = newValue)
    }

    fun onSignUpClick(popUpScreen: () -> Unit) {
        if (!email.isValidEmail()) {
            snackbarChannel.trySend(ResourceError(AppText.email_error))
            return
        }

        if (!password.isValidPassword()) {
            snackbarChannel.trySend(ResourceError(AppText.password_error))
            return
        }

        if (!password.passwordMatches(uiState.value.repeatPassword)) {
            snackbarChannel.trySend(ResourceError(AppText.password_match_error))
            return
        }

        viewModelScope.launch(exceptionHandler) {
            accountService.createAccount(email, password) { task ->
                task.onResult { linkWithEmail(popUpScreen) }
            }
        }
    }

    private fun linkWithEmail(popUpScreen: () -> Unit) {
        viewModelScope.launch(exceptionHandler) {
            accountService.linkAccount(email, password) { task ->
                task.onResult { updateUserId(popUpScreen) }
            }
        }
    }

    private fun updateUserId(popUpScreen: () -> Unit) {
        viewModelScope.launch(exceptionHandler) {
            val oldUserId = accountService.getAnonymousUserId()
            val newUserId = accountService.getUserId()

            firestoreService.updateUserId(oldUserId, newUserId) { error ->
                if (error == null) {
                    viewModelScope.launch { taskRepository.updateUserId(oldUserId, newUserId) }
                    popUpScreen()
                } else {
                    viewModelScope.launch { crashlyticsService.logNonFatalCrash(error) }
                }
            }
        }
    }

    private fun Task<AuthResult>.onResult(successCallback: () -> Unit) {
        if (this.isSuccessful) {
            successCallback()
        } else {
            snackbarChannel.trySend(this.exception.toErrorMessage())
            crashlyticsService.logNonFatalCrash(this.exception)
        }
    }
}