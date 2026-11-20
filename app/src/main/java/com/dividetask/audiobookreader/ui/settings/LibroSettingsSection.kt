package com.dividetask.audiobookreader.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dividetask.audiobookreader.libro.LibroAccount
import com.dividetask.audiobookreader.libro.LibroRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LibroSettingsViewModel @Inject constructor(
    private val account: LibroAccount,
    private val repository: LibroRepository
) : ViewModel() {
    val signedIn = account.signedIn
    val email: String get() = account.email

    var emailInput by mutableStateOf(account.email)
    var password by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf("")
        private set

    fun signIn() {
        busy = true
        message = ""
        viewModelScope.launch {
            message = try {
                repository.signIn(emailInput, password)
                password = ""
                "Signed in"
            } catch (e: Exception) {
                e.message ?: "Sign-in failed"
            }
            busy = false
        }
    }

    fun signOut() {
        viewModelScope.launch { repository.signOut() }
        message = ""
    }
}

/** Libro.fm account: sign in with email and password (only the sign-in token is kept). */
@Composable
fun LibroSettingsSection(viewModel: LibroSettingsViewModel = hiltViewModel()) {
    val signedIn by viewModel.signedIn.collectAsState()
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Text("Libro.fm", style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            if (signedIn) {
                Text("Signed in as ${viewModel.email}", style = MaterialTheme.typography.bodyMedium)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { viewModel.signOut() }, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
            } else {
                Text("Sign in to see and download the books you bought on Libro.fm.",
                    style = MaterialTheme.typography.bodySmall, color = muted)
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(value = viewModel.emailInput, onValueChange = { viewModel.emailInput = it },
                    label = { Text("Email") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email))
                OutlinedTextField(value = viewModel.password, onValueChange = { viewModel.password = it },
                    label = { Text("Password") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Spacer(modifier = Modifier.height(8.dp))
                Button(onClick = { viewModel.signIn() }, enabled = !viewModel.busy &&
                    viewModel.emailInput.isNotBlank() && viewModel.password.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                    Text(if (viewModel.busy) "Signing in…" else "Sign in")
                }
            }
            if (viewModel.message.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(viewModel.message, style = MaterialTheme.typography.bodySmall,
                    color = if (viewModel.message == "Signed in") muted else MaterialTheme.colorScheme.error)
            }
        }
    }
    Spacer(modifier = Modifier.height(16.dp))
}
