package com.netcontrol.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * 带精确数字输入框的滑块组件：拖动可快速粗调，直接输入数字可毫秒/秒级微调。
 */
@Composable
fun SliderWithNumericInput(
    label: String,
    unit: String,
    value: Int,
    valueRange: IntRange,
    step: Int = 1,
    onValueChangeFinished: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var sliderFloat by remember(value) { mutableStateOf(value.toFloat()) }
    var textValue by remember(value) { mutableStateOf(value.toString()) }

    LaunchedEffect(value) {
        sliderFloat = value.toFloat()
        textValue = value.toString()
    }

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = "$label ($unit)",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Slider(
                value = sliderFloat.coerceIn(valueRange.first.toFloat(), valueRange.last.toFloat()),
                onValueChange = { raw ->
                    val snapped = ((raw / step).roundToInt() * step).coerceIn(valueRange.first, valueRange.last)
                    sliderFloat = snapped.toFloat()
                    textValue = snapped.toString()
                },
                onValueChangeFinished = {
                    val committed = sliderFloat.roundToInt().coerceIn(valueRange.first, valueRange.last)
                    onValueChangeFinished(committed)
                },
                valueRange = valueRange.first.toFloat()..valueRange.last.toFloat(),
                modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
                value = textValue,
                onValueChange = { input ->
                    val digitsOnly = input.filter { it.isDigit() }
                    textValue = digitsOnly
                    digitsOnly.toIntOrNull()?.let { parsed ->
                        if (parsed in valueRange) {
                            sliderFloat = parsed.toFloat()
                            onValueChangeFinished(parsed)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                suffix = { Text(unit, style = MaterialTheme.typography.labelSmall) },
                modifier = Modifier.width(108.dp)
            )
        }
    }
}
