package com.haise.jiyu.ui.account

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.haise.jiyu.R
import com.haise.jiyu.ui.components.JiyuLoadingIndicator
import com.haise.jiyu.ui.theme.TextPrimary
import com.haise.jiyu.ui.theme.TextSecondary
import com.haise.jiyu.ui.theme.Violet
import com.haise.jiyu.ui.theme.screenGradient
import com.haise.jiyu.ui.theme.titleGradient
import compose.icons.TablerIcons
import compose.icons.tablericons.Eye
import compose.icons.tablericons.EyeOff
import compose.icons.tablericons.Lock
import kotlinx.coroutines.delay

/**
 * Cíl odkazu z resetovacího e-mailu (jiyu://auth#access_token=…&type=recovery).
 * MainActivity importuje session přes handleDeeplinks asynchronně - screen proto krátce
 * čeká na přihlášení; když session nedorazí (prošlý/zneužitý link), ukáže se hláška
 * o expiraci místo formuláře.
 */
@Composable
fun ResetPasswordScreen(
    onDone: () -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val authState   by viewModel.authState.collectAsStateWithLifecycle()
    val snackbarHost = remember { SnackbarHostState() }
    val errorPrefix = stringResource(R.string.account_error_prefix)
    val changedText = stringResource(R.string.account_password_changed)

    // Čekání na import session z deep linku; po 4 s bez session = odkaz neplatný.
    var linkExpired by remember { mutableStateOf(false) }
    LaunchedEffect(currentUser) {
        if (currentUser == null) {
            delay(4_000)
            if (currentUser == null) linkExpired = true
        }
    }

    LaunchedEffect(authState) {
        when (val s = authState) {
            is AuthUiState.Error -> { snackbarHost.showSnackbar(errorPrefix.format(s.message)); viewModel.clearAuthState() }
            is AuthUiState.Success -> { snackbarHost.showSnackbar(changedText); viewModel.clearAuthState(); onDone() }
            else -> Unit
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(screenGradient)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Icon(
                    imageVector = TablerIcons.Lock,
                    contentDescription = null,
                    tint = Violet.copy(alpha = 0.5f),
                    modifier = Modifier.size(72.dp),
                )
                Text(
                    stringResource(R.string.account_reset_title),
                    style = TextStyle(brush = titleGradient, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold),
                )

                when {
                    currentUser == null && !linkExpired -> JiyuLoadingIndicator(size = 48.dp)
                    linkExpired -> {
                        Text(
                            stringResource(R.string.account_reset_expired),
                            color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                        )
                        TextButton(onClick = onDone) {
                            Text(stringResource(R.string.common_back), color = Violet)
                        }
                    }
                    else -> {
                        Text(
                            stringResource(R.string.account_reset_subtitle),
                            color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center,
                        )
                        ResetPasswordForm(
                            isLoading = authState is AuthUiState.Loading,
                            onSubmit = { viewModel.updatePassword(it) },
                        )
                    }
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHost,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

@Composable
private fun ResetPasswordForm(
    isLoading: Boolean,
    onSubmit: (String) -> Unit,
) {
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var passwordVisible by rememberSaveable { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val mismatch = confirm.isNotEmpty() && password != confirm
    val fieldColors = TextFieldDefaults.colors(
        focusedContainerColor = Color.Transparent,
        unfocusedContainerColor = Color.Transparent,
        focusedIndicatorColor = Violet,
        unfocusedIndicatorColor = TextSecondary.copy(alpha = 0.3f),
        focusedTextColor = TextPrimary,
        unfocusedTextColor = TextPrimary,
        focusedLabelColor = Violet,
        unfocusedLabelColor = TextSecondary,
    )

    Column(
        modifier = Modifier.fillMaxWidth(0.88f),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.account_new_password)) },
            leadingIcon = { Icon(TablerIcons.Lock, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Down) }),
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        if (passwordVisible) TablerIcons.EyeOff else TablerIcons.Eye,
                        contentDescription = stringResource(
                            if (passwordVisible) R.string.account_hide_password else R.string.account_show_password
                        ),
                        tint = TextSecondary, modifier = Modifier.size(18.dp),
                    )
                }
            },
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = confirm,
            onValueChange = { confirm = it },
            label = { Text(stringResource(R.string.account_confirm_password)) },
            leadingIcon = { Icon(TablerIcons.Lock, null, tint = TextSecondary, modifier = Modifier.size(18.dp)) },
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            isError = mismatch,
            supportingText = {
                if (mismatch) Text(stringResource(R.string.account_passwords_mismatch))
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                focusManager.clearFocus()
                if (password.length >= 6 && password == confirm) onSubmit(password)
            }),
            colors = fieldColors,
            modifier = Modifier.fillMaxWidth(),
        )
        if (isLoading) {
            JiyuLoadingIndicator(size = 32.dp, modifier = Modifier.align(Alignment.CenterHorizontally))
        } else {
            Button(
                onClick = { onSubmit(password) },
                enabled = password.length >= 6 && password == confirm,
                colors = ButtonDefaults.buttonColors(containerColor = Violet),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                Text(
                    stringResource(R.string.account_set_password),
                    color = Color.White, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}
