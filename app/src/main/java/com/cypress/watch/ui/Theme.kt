package com.cypress.watch.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object C {
    val Bg = Color(0xFF0E1116); val Bg2 = Color(0xFF0A0C10); val Panel = Color(0xFF161A21); val Line = Color(0xFF252B35)
    val Ink = Color(0xFFEEF1F6); val Muted = Color(0xFF8B94A5); val Accent = Color(0xFF5B8CFF); val AccentDark = Color(0xFF22356B)
    val Ok = Color(0xFF4ADE80); val Warn = Color(0xFFF2B84B); val Bad = Color(0xFFF87171); val Hover = Color(0xFF1D222B)
}

@Composable
fun CypressTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = C.Accent, background = C.Bg, surface = C.Panel, onSurface = C.Ink, onBackground = C.Ink), content = content)
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 5.dp)
        .background(C.Panel, RoundedCornerShape(18.dp)).border(1.dp, C.Line, RoundedCornerShape(18.dp)).padding(14.dp), content = content)
}

@Composable
fun Section(text: String) {
    Text(text.uppercase(), color = C.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
        modifier = Modifier.padding(start = 18.dp, top = 14.dp, bottom = 6.dp))
}

@Composable
fun Btn(text: String, modifier: Modifier = Modifier, primary: Boolean = true, enabled: Boolean = true, onClick: () -> Unit) {
    Box(modifier.background(if (!enabled) C.Hover else if (primary) C.AccentDark else C.Hover, RoundedCornerShape(12.dp))
        .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
        Text(text, color = if (enabled) C.Ink else C.Muted, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
    }
}

@Composable
fun ToggleRow(title: String, sub: String? = null, icon: String? = null, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) { Box(Modifier.size(36.dp).background(C.Hover, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text(icon, fontSize = 17.sp) }; Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Ink, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            if (sub != null) Text(sub, color = C.Muted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, colors = SwitchDefaults.colors(checkedTrackColor = C.Accent, uncheckedTrackColor = Color(0xFF2B313C), uncheckedBorderColor = Color.Transparent, uncheckedThumbColor = C.Muted))
    }
}

@Composable
fun Tile(icon: String, label: String, value: String, unit: String = "", sub: String? = null, modifier: Modifier = Modifier, extra: @Composable () -> Unit = {}) {
    Column(modifier.background(C.Panel, RoundedCornerShape(18.dp)).border(1.dp, C.Line, RoundedCornerShape(18.dp)).padding(14.dp)) {
        Text("$icon $label", color = C.Muted, fontSize = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = C.Ink, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            if (unit.isNotEmpty()) Text(" $unit", color = C.Muted, fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
        if (sub != null) Text(sub, color = C.Muted, fontSize = 12.sp)
        extra()
    }
}
