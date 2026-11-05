@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package io.github.thegingerbehindu.rehabtracker.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.thegingerbehindu.rehabtracker.data.Symptom
import io.github.thegingerbehindu.rehabtracker.domain.APP_ZONE
import io.github.thegingerbehindu.rehabtracker.domain.pretty
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(APP_ZONE)
val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm").withZone(APP_ZONE)

private val LightColors = lightColorScheme(
    primary = Color(0xFF2457D6),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE4EBFD),
    onPrimaryContainer = Color(0xFF12306F),
    secondaryContainer = Color(0xFFE4EBFD),
    onSecondaryContainer = Color(0xFF12306F),
    background = Color(0xFFF4F7FB),
    surface = Color(0xFFF4F7FB),
    surfaceContainerLow = Color.White,
    surfaceContainer = Color.White,
    surfaceContainerHighest = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFA9C0FF),
    onPrimary = Color(0xFF0B2A6B),
    primaryContainer = Color(0xFF1D3B85),
    onPrimaryContainer = Color(0xFFDCE5FF),
    secondaryContainer = Color(0xFF1D3B85),
    onSecondaryContainer = Color(0xFFDCE5FF),
)

@Composable
fun RehabTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors, content = content)
}

/** Tap a score to choose it; tap it again to clear it. A blank score is never treated as zero. */
@Composable
fun ScoreRow(label: String, value: Double?, min: Int = 0, max: Int = 10, onChange: (Double?) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            (min..max).forEach { score ->
                val selected = value == score.toDouble()
                Box(
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .background(
                            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerLow,
                            RoundedCornerShape(8.dp),
                        )
                        .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(8.dp))
                        .clickable { onChange(if (selected) null else score.toDouble()) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        score.toString(),
                        color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                }
            }
        }
        // An imported decimal score has no button, so it's shown instead of being lost.
        if (value != null && value % 1.0 != 0.0) {
            Text("Recorded: ${value.pretty()}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun ChoiceChips(options: List<String>, selected: String?, onSelect: (String?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = option == selected,
                onClick = { onSelect(if (option == selected) null else option) },
                label = { Text(option) },
            )
        }
    }
}

@Composable
fun NumberField(label: String, value: String, modifier: Modifier = Modifier, suffix: String? = null, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { next -> onChange(next.filter { it.isDigit() || it == '.' || it == ',' }.replace(',', '.')) },
        label = { Text(label) },
        suffix = suffix?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier,
    )
}

@Composable
fun DateTimeRow(at: Instant, onChange: (Instant) -> Unit) {
    val context = LocalContext.current
    val zoned = at.atZone(APP_ZONE)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            DatePickerDialog(
                context,
                { _, year, month, day ->
                    onChange(ZonedDateTime.of(year, month + 1, day, zoned.hour, zoned.minute, 0, 0, APP_ZONE).toInstant())
                },
                zoned.year,
                zoned.monthValue - 1,
                zoned.dayOfMonth,
            ).show()
        }) { Text(DATE_FORMAT.format(at)) }
        OutlinedButton(onClick = {
            TimePickerDialog(
                context,
                { _, hour, minute -> onChange(zoned.withHour(hour).withMinute(minute).withSecond(0).withNano(0).toInstant()) },
                zoned.hour,
                zoned.minute,
                true,
            ).show()
        }) { Text(TIME_FORMAT.format(at)) }
    }
}

/** Header with Back and optional Delete, a scrolling body, and a Save button that's always reachable. */
@Composable
fun EditorLayout(
    title: String,
    saveLabel: String = "Save",
    saveEnabled: Boolean = true,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onDelete: (() -> Unit)? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    BackHandler(onBack = onBack)
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Back") }
            Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
        }
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            content = body,
        )
        Button(onClick = onSave, enabled = saveEnabled, modifier = Modifier.fillMaxWidth().padding(16.dp)) { Text(saveLabel) }
    }
}

/** Runs when the app leaves the screen, so switching apps can't lose what was just entered. */
@Composable
fun OnAppHidden(action: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(action)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) latest() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

/** The "Back again" and "New symptom" actions shown under every form's symptom scores. */
@Composable
fun SymptomActions(resolved: List<Symptom>, onBackAgain: (Symptom) -> Unit, onNew: (String) -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    var dialogOpen by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { dialogOpen = true }) { Text("New symptom") }
        if (resolved.isNotEmpty()) {
            Box {
                OutlinedButton(onClick = { menuOpen = true }) { Text("Back again") }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    resolved.forEach { symptom ->
                        DropdownMenuItem(text = { Text(symptom.name) }, onClick = {
                            menuOpen = false
                            onBackAgain(symptom)
                        })
                    }
                }
            }
        }
    }

    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            title = { Text("New symptom") },
            text = {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Symptom name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    onNew(name.trim())
                    name = ""
                    dialogOpen = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { dialogOpen = false }) { Text("Cancel") } },
        )
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
}

@Composable
fun ExpandableDetails(label: String = "Add details", content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = { open = !open }) { Text(if (open) "Hide details" else label) }
        if (open) content()
    }
}
