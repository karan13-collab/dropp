package com.dropp.dropp.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dropp.dropp.services.FileBrowserManager
import com.dropp.dropp.services.RemoteBrowserItem
import com.dropp.dropp.services.RemoteSearchResult
import com.dropp.dropp.services.SearchManager
import kotlinx.coroutines.delay

@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf("ALL") }
    var isGlobalSearchActive by remember { mutableStateOf(false) }

    // Directory Browser state
    val currentPath by FileBrowserManager.currentPath.collectAsState()
    val parentPath by FileBrowserManager.parentPath.collectAsState()
    val currentItems by FileBrowserManager.currentItems.collectAsState()
    val isBrowsing by FileBrowserManager.isLoading.collectAsState()
    val browserError by FileBrowserManager.browserError.collectAsState()

    // Global Search state
    val searchResults by SearchManager.remoteResults.collectAsState()
    val isSearching by SearchManager.isSearching.collectAsState()
    val searchError by SearchManager.searchError.collectAsState()

    // Initial browse on first display
    LaunchedEffect(Unit) {
        if (currentItems.isEmpty()) {
            FileBrowserManager.browseRemote(context, "ROOT")
        }
    }

    // 500ms Debounce for active global search
    LaunchedEffect(query, selectedFilter, isGlobalSearchActive) {
        if (isGlobalSearchActive && query.isNotBlank()) {
            delay(500)
            SearchManager.searchRemote(context, query.trim(), selectedFilter)
        }
    }

    val navigateTo: (String) -> Unit = { path ->
        isGlobalSearchActive = false
        query = ""
        FileBrowserManager.browseRemote(context, path)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        // --- 1. Path Navigation & Breadcrumb Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Home / Root Button
            OutlinedButton(
                onClick = { navigateTo("ROOT") },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("🏠 PC", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            // Up Button
            OutlinedButton(
                onClick = {
                    if (parentPath != null && parentPath != "ROOT") {
                        navigateTo(parentPath!!)
                    } else {
                        navigateTo("ROOT")
                    }
                },
                enabled = parentPath != null || currentPath != "ROOT",
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("⬆ Up", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }

            // Current Path Display Pill
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            ) {
                Text(
                    text = if (isGlobalSearchActive) "Global Search: '$query'"
                           else if (currentPath == "ROOT") "PC Libraries"
                           else currentPath,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // Refresh Button
            IconButton(
                onClick = {
                    if (isGlobalSearchActive && query.isNotBlank()) {
                        SearchManager.searchRemote(context, query.trim(), selectedFilter)
                    } else {
                        navigateTo(currentPath)
                    }
                },
                modifier = Modifier.size(36.dp)
            ) {
                Text("🔄", fontSize = 14.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 2. Quick PC Library Jump Chips ---
        val quickLibraries = listOf(
            "Downloads" to "DOWNLOADS",
            "Documents" to "DOCUMENTS",
            "Desktop" to "DESKTOP",
            "Pictures" to "PICTURES",
            "Local Disk C:" to "C:\\",
            "Local Disk D:" to "D:\\"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickLibraries.forEach { (label, path) ->
                FilledTonalButton(
                    onClick = { navigateTo(path) },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = if (!isGlobalSearchActive && (currentPath.equals(path, ignoreCase = true) || currentPath.contains(label, ignoreCase = true)))
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(label, fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 3. Search & Filter Input Bar ---
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    if (it.isBlank() && isGlobalSearchActive) {
                        isGlobalSearchActive = false
                    }
                },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (isGlobalSearchActive) "Search all PC files..."
                        else "Filter current folder or search PC...",
                        fontSize = 12.sp
                    )
                },
                singleLine = true,
                shape = RoundedCornerShape(10.dp),
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = {
                            query = ""
                            if (isGlobalSearchActive) isGlobalSearchActive = false
                        }) {
                            Text("✕", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            )

            // Dedicated Search Entire PC Button
            ElevatedButton(
                onClick = {
                    if (query.isNotBlank()) {
                        isGlobalSearchActive = true
                        SearchManager.searchRemote(context, query.trim(), selectedFilter)
                    } else {
                        Toast.makeText(context, "Type a filename or keyword first", Toast.LENGTH_SHORT).show()
                    }
                },
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp)
            ) {
                Text("Search PC", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(6.dp))

        // --- 4. Category Filter Chips ---
        val filters = listOf("ALL", "FOLDERS", "PDF", "DOC", "IMAGE", "VIDEO", "AUDIO")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            filters.forEach { filter ->
                FilterChip(
                    selected = selectedFilter == filter,
                    onClick = {
                        selectedFilter = filter
                        if (isGlobalSearchActive && query.isNotBlank()) {
                            SearchManager.searchRemote(context, query.trim(), filter)
                        }
                    },
                    label = { Text(filter, fontSize = 11.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // --- 5. Error Banners ---
        val activeError = if (isGlobalSearchActive) searchError else browserError
        if (activeError != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = activeError,
                    modifier = Modifier.padding(10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    textAlign = TextAlign.Center
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
        }

        // --- 6. Loading Indicator ---
        val activeLoading = if (isGlobalSearchActive) isSearching else isBrowsing
        if (activeLoading) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (isGlobalSearchActive) "Searching PC storage..." else "Loading PC folder...",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // --- 7. Items / Results Display ---
        if (isGlobalSearchActive) {
            // Global Search Results View
            if (searchResults.isEmpty() && !isSearching) {
                EmptyStateBox(title = "No files found on PC", subtitle = "Try refining your search keyword or switching category filter.")
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(searchResults, key = { it.id }) { item ->
                        SearchResultItemCard(
                            item = item,
                            onPreviewClick = {
                                Toast.makeText(context, "Streaming remote preview for ${item.name}...", Toast.LENGTH_SHORT).show()
                                SearchManager.requestRemoteStream(context, item.id, isPreview = true)
                            },
                            onDownloadClick = {
                                Toast.makeText(context, "Downloading ${item.name} to Downloads...", Toast.LENGTH_SHORT).show()
                                SearchManager.requestRemoteStream(context, item.id, isPreview = false)
                            }
                        )
                    }
                }
            }
        } else {
            // Live Directory Explorer View
            val displayedItems = remember(currentItems, selectedFilter, query) {
                currentItems.filter { item ->
                    // Category filter
                    if (selectedFilter == "FOLDERS" && !item.isDirectory) return@filter false
                    if (selectedFilter != "ALL" && selectedFilter != "FOLDERS") {
                        if (item.isDirectory) return@filter false
                        if (!item.type.equals(selectedFilter, ignoreCase = true)) return@filter false
                    }

                    // Query text filter
                    if (query.isNotBlank()) {
                        if (!item.name.contains(query, ignoreCase = true)) return@filter false
                    }

                    true
                }
            }

            if (displayedItems.isEmpty() && !isBrowsing) {
                EmptyStateBox(
                    title = if (query.isNotBlank()) "No matching items in folder" else "Folder is empty",
                    subtitle = if (query.isNotBlank()) "Tap 'Search PC' to search your entire computer." else "No files or subfolders found here."
                )
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(displayedItems, key = { it.id }) { item ->
                        RemoteBrowserItemCard(
                            item = item,
                            onFolderClick = {
                                navigateTo(item.id)
                            },
                            onPreviewClick = {
                                Toast.makeText(context, "Streaming remote preview for ${item.name}...", Toast.LENGTH_SHORT).show()
                                SearchManager.requestRemoteStream(context, item.id, isPreview = true)
                            },
                            onDownloadClick = {
                                Toast.makeText(context, "Downloading ${item.name} to Downloads...", Toast.LENGTH_SHORT).show()
                                SearchManager.requestRemoteStream(context, item.id, isPreview = false)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun RemoteBrowserItemCard(
    item: RemoteBrowserItem,
    onFolderClick: () -> Unit,
    onPreviewClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    if (item.isDirectory) {
        // Folder item card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onFolderClick() },
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Folder Icon Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("📁", fontSize = 18.sp)
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // Folder Name & Subtitle
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = if (item.itemCount > 0) "${item.itemCount} items • Tap to open" else "Folder • Tap to open",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Open Button
                OutlinedButton(
                    onClick = onFolderClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("Open", fontSize = 11.sp)
                }
            }
        }
    } else {
        // File item card
        val (badgeBg, badgeFg) = when (item.type) {
            "PDF" -> Color(0xFFFBE9E7) to Color(0xFFD32F2F)
            "IMAGE" -> Color(0xFFF3E5F5) to Color(0xFF7B1FA2)
            "DOC" -> Color(0xFFE3F2FD) to Color(0xFF1976D2)
            "VIDEO" -> Color(0xFFFFF3E0) to Color(0xFFE65100)
            "AUDIO" -> Color(0xFFE8F5E9) to Color(0xFF388E3C)
            else -> Color(0xFFECEFF1) to Color(0xFF455A64)
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onPreviewClick() },
            shape = RoundedCornerShape(10.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // File Type Badge
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg,
                    modifier = Modifier.size(40.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = item.type.take(3),
                            color = badgeFg,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                // File Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        fontWeight = FontWeight.SemiBold,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.formattedSize,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = " • Tap to preview",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(6.dp))

                // Action Buttons
                FilledTonalButton(
                    onClick = onDownloadClick,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)
                ) {
                    Text("Save", fontSize = 11.sp)
                }
            }
        }
    }
}

@Composable
fun SearchResultItemCard(
    item: RemoteSearchResult,
    onPreviewClick: () -> Unit,
    onDownloadClick: () -> Unit
) {
    val (badgeBg, badgeFg) = when (item.type) {
        "PDF" -> Color(0xFFFBE9E7) to Color(0xFFD32F2F)
        "IMAGE" -> Color(0xFFF3E5F5) to Color(0xFF7B1FA2)
        "DOC" -> Color(0xFFE3F2FD) to Color(0xFF1976D2)
        "VIDEO" -> Color(0xFFFFF3E0) to Color(0xFFE65100)
        "AUDIO" -> Color(0xFFE8F5E9) to Color(0xFF388E3C)
        else -> Color(0xFFECEFF1) to Color(0xFF455A64)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPreviewClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = badgeBg,
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = item.type.take(3),
                        color = badgeFg,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.formattedSize,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = " • Tap to preview",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Spacer(modifier = Modifier.width(6.dp))

            FilledTonalButton(
                onClick = onDownloadClick,
                shape = RoundedCornerShape(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp)
            ) {
                Text("Save", fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun EmptyStateBox(title: String, subtitle: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 40.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}
