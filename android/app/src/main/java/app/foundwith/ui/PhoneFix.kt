package app.foundwith.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.foundwith.AppGraph
import app.foundwith.core.BrandFamily
import app.foundwith.core.Brands

/**
 * "Let WhoHas work after calls": the one-time phone setting that Xiaomi, Vivo, Oppo, Realme,
 * Tecno and similar phones need before any app can run after a call. The same steps as
 * Truecaller's help pages per brand, with a button that opens the right settings page.
 */
@Composable
fun PhoneFixScreen(graph: AppGraph, onBack: () -> Unit) {
    val context = LocalContext.current
    val family = Brands.family(Build.MANUFACTURER, Build.BRAND)
    val fix = Brands.fix(family)
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        BackBar(onBack)
        Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Let WhoHas check your calls", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Your phone stops apps to save battery. Turn this on so WhoHas can check every call.",
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp)) {
                    Icon(Icons.Filled.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text(fix.setting, fontWeight = FontWeight.SemiBold)
                        Text(fix.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            PrimaryButton("Open settings", { openFix(context, family) })
            OutlinedButton(onClick = { openBattery(context) }, modifier = Modifier.fillMaxWidth()) { Text("Battery settings") }
            TextButton(onClick = { graph.model.dismissFix(); onBack() }) { Text("Done") }
        }
    }
}

/** Tries the brand's own settings screens, then the app's page in Android settings. */
fun openFix(context: Context, family: BrandFamily) {
    for (screen in Brands.fix(family).screens) {
        val intent = Intent().setComponent(ComponentName(screen.packageName, screen.className)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.startActivity(intent) }.isSuccess) return
    }
    context.openAppSettings()
}

/** Android's list of battery-optimised apps, where WhoHas can be set to "Don't optimise". */
fun openBattery(context: Context) {
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (runCatching { context.startActivity(intent) }.isFailure) context.openAppSettings()
}
