package com.dividetask.audiobookreader.ui.common

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class SortOrder(val label: String) {
    TITLE("Title"),
    RECENTLY_PLAYED("Recently played"),
    RECENTLY_ADDED("Recently added")
}

/** The tabs with a sortable list, the orders each offers, and the order it starts with. */
enum class SortTab(val options: List<SortOrder>, val default: SortOrder) {
    LIBRARY(listOf(SortOrder.TITLE, SortOrder.RECENTLY_PLAYED, SortOrder.RECENTLY_ADDED), SortOrder.RECENTLY_PLAYED),
    AUDIBLE(listOf(SortOrder.TITLE, SortOrder.RECENTLY_ADDED), SortOrder.RECENTLY_ADDED),
    SERVER(listOf(SortOrder.TITLE, SortOrder.RECENTLY_ADDED), SortOrder.TITLE),
    ALL(listOf(SortOrder.TITLE, SortOrder.RECENTLY_PLAYED), SortOrder.TITLE)
}

/** The sort order picked on each tab, kept across restarts. */
@Singleton
class ListSortPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs = context.getSharedPreferences("list_prefs", Context.MODE_PRIVATE)

    private val _orders = MutableStateFlow(SortTab.entries.associateWith { tab ->
        SortOrder.entries.firstOrNull { it.name == prefs.getString(key(tab), null) }
            ?.takeIf { it in tab.options } ?: tab.default
    })
    val orders: StateFlow<Map<SortTab, SortOrder>> = _orders.asStateFlow()

    fun setOrder(tab: SortTab, order: SortOrder) {
        prefs.edit().putString(key(tab), order.name).apply()
        _orders.value = _orders.value + (tab to order)
    }

    private fun key(tab: SortTab) = "sort_${tab.name.lowercase()}"
}

/** A small "Sort: Title ▾" link at the right edge of a list; tapping it opens the choices. */
@Composable
fun SortMenu(tab: SortTab, current: SortOrder, onChange: (SortOrder) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.End) {
        Box {
            Row(
                modifier = Modifier.clickable { open = true }.padding(start = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Sort: ${current.label}", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Change sort order",
                    modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                tab.options.forEach { order ->
                    DropdownMenuItem(
                        text = { Text(order.label) },
                        onClick = { open = false; onChange(order) }
                    )
                }
            }
        }
    }
}
