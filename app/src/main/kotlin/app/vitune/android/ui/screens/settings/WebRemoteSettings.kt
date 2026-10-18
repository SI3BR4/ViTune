package app.vitune.android.ui.screens.settings

import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import app.vitune.android.service.WebRemoteBridge
import app.vitune.android.ui.components.themed.DefaultDialog
import app.vitune.android.ui.components.themed.DialogTextButton
import app.vitune.android.ui.components.themed.TextField
import app.vitune.core.ui.LocalAppearance
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun WebRemoteSettings() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var visible by remember { mutableStateOf(false) }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var connected by remember { mutableStateOf(WebRemoteBridge.connection(context) != null) }
    var status by remember { mutableStateOf("No computador, abra Conectar celular e gere o c贸digo. Use o mesmo Wi-Fi.") }
    val connect: () -> Unit = {
        busy = true
        scope.launch {
            try {
                WebRemoteBridge.pair(context, WebRemoteBridge.DEFAULT_URL, code)
                connected = true
                status = "Pareado! Mantenha o ViTune aberto ou tocando. Seus favoritos aparecer茫o no computador."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { status = e.message ?: "Confira o Wi-Fi, o c贸digo e se o servidor est谩 ligado." }
            finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) connect() else status = "Permita o acesso 脿 rede local para conectar ao computador."
    }
    SettingsGroup(title = "ViTune Web") {
        SettingsEntry(title = "Conectar ao computador", text = if (connected) "Pareado 路 controle e favoritos pela rede local" else "Controle o player e compartilhe favoritos com o ViTune Web", onClick = { visible = true })
    }
    if (visible) DefaultDialog(onDismiss = { if (!busy) visible = false }) {
        val (_, typography) = LocalAppearance.current
        BasicText(text = "Conectar ao ViTune Web", style = typography.s)
        Spacer(Modifier.height(12.dp))
        BasicText(text = status, style = typography.xs)
        Spacer(Modifier.height(12.dp))
        BasicText(text = "Endere鏾 do computador: configure apenas na vers鉶 local", style = typography.xs)
        Spacer(Modifier.height(12.dp))
        TextField(value = code, onValueChange = { code = it.filter(Char::isDigit).take(6) },
            hintText = "C贸digo de 6 n煤meros", singleLine = true, enabled = !busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(12.dp))
        if (!busy) {
            DialogTextButton(text = "Conectar", primary = true, onClick = {
                val name = "android.permission.ACCESS_LOCAL_NETWORK"
                if (Build.VERSION.SDK_INT >= 37 && context.checkSelfPermission(name) != PackageManager.PERMISSION_GRANTED) permission.launch(name)
                else connect()
            })
            if (connected) DialogTextButton(text = "Desconectar", onClick = {
                WebRemoteBridge.disconnect(context); connected = false; status = "Conex茫o desativada neste celular."
            })
            DialogTextButton(text = "Fechar", onClick = { visible = false })
        }
    }
}
