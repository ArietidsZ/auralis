package com.dialect.interpreter.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dialect.interpreter.inference.AsrEngine
import com.dialect.interpreter.ui.theme.*

/**
 * Dialect/language selector with animated swap button.
 */
@Composable
fun DialectSelector(
    sourceDialect: String,
    targetLanguage: String,
    onSourceChange: (String) -> Unit,
    onTargetChange: (String) -> Unit,
    onSwap: () -> Unit,
    modifier: Modifier = Modifier
) {
    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }
    var swapRotation by remember { mutableFloatStateOf(0f) }

    val animatedRotation by animateFloatAsState(
        targetValue = swapRotation,
        animationSpec = spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "swapRot"
    )

    val accent = AppColors.accent()
    val surface = AppColors.surface()
    val text = AppColors.text()
    val secondary = AppColors.textSecondary()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(RadiusCard))
            .background(surface)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        // Source dialect
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { showSourcePicker = true }
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "源方言",
                style = MaterialTheme.typography.labelSmall,
                color = secondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                sourceDialect,
                style = MaterialTheme.typography.titleMedium,
                color = accent
            )
        }

        // Animated swap button
        FilledIconButton(
            onClick = {
                swapRotation += 180f
                onSwap()
            },
            modifier = Modifier
                .size(40.dp)
                .rotate(animatedRotation),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = accent.copy(alpha = 0.12f),
                contentColor = accent
            )
        ) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = "交换",
                modifier = Modifier.size(20.dp)
            )
        }

        // Target language
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable { showTargetPicker = true }
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                "目标语言",
                style = MaterialTheme.typography.labelSmall,
                color = secondary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                targetLanguage,
                style = MaterialTheme.typography.titleMedium,
                color = text
            )
        }
    }

    // Source dialect picker
    if (showSourcePicker) {
        DialectPickerDialog(
            title = "选择源方言",
            items = AsrEngine.CHINESE_DIALECTS.map { it.first },
            selectedItem = sourceDialect,
            onSelect = { onSourceChange(it); showSourcePicker = false },
            onDismiss = { showSourcePicker = false }
        )
    }

    // Target language picker
    if (showTargetPicker) {
        DialectPickerDialog(
            title = "选择目标语言",
            items = AsrEngine.SUPPORTED_LANGUAGES.map { it.first },
            selectedItem = targetLanguage,
            onSelect = { onTargetChange(it); showTargetPicker = false },
            onDismiss = { showTargetPicker = false }
        )
    }
}

@Composable
private fun DialectPickerDialog(
    title: String,
    items: List<String>,
    selectedItem: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = AppColors.accent()
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = AppColors.surface(),
        title = {
            Text(title, color = AppColors.text())
        },
        text = {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(4.dp)
            ) {
                items(items) { item ->
                    val isSelected = item == selectedItem
                    FilterChip(
                        selected = isSelected,
                        onClick = { onSelect(item) },
                        label = { Text(item, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = accent.copy(alpha = 0.15f),
                            selectedLabelColor = accent
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("完成", color = accent)
            }
        }
    )
}
