@file:OptIn(ExperimentalMaterial3Api::class)

package com.julienmiquel.audiotheque.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.julienmiquel.audiotheque.AppContainer
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.library.BookStatEntry
import com.julienmiquel.audiotheque.library.CreatorStatEntry
import com.julienmiquel.audiotheque.library.DayListening
import com.julienmiquel.audiotheque.library.ListeningStatsCalculator
import com.julienmiquel.audiotheque.library.ListeningStatsSummary
import com.julienmiquel.audiotheque.ui.common.BookCover
import com.julienmiquel.audiotheque.ui.common.EmptyState
import com.julienmiquel.audiotheque.ui.common.InfoCard
import com.julienmiquel.audiotheque.ui.common.LocalAppContainer
import com.julienmiquel.audiotheque.ui.common.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn

class StatsViewModel(c: AppContainer) : ViewModel() {
    val summary: StateFlow<ListeningStatsSummary?> = combine(
        c.library.observeListeningSessions(),
        c.library.observeLibrary(),
    ) { sessions, items ->
        ListeningStatsCalculator.compute(sessions = sessions, items = items)
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
}

@Composable
fun StatsScreen(
    onBack: () -> Unit,
    onOpenBook: (String) -> Unit,
    contentPadding: PaddingValues,
) {
    val container = LocalAppContainer.current
    val vm: StatsViewModel = viewModel { StatsViewModel(container) }
    val stats by vm.summary.collectAsStateWithLifecycle()
    val appSettings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    var rangeIndex by rememberSaveable { mutableIntStateOf(0) } // 0 = 7 jours, 1 = 30 jours

    Scaffold(
        modifier = Modifier.padding(contentPadding),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = {
                    val listenerSuffix = appSettings?.takeIf { it.hasMultipleListeners }?.let { " · ${it.activeListenerName}" }.orEmpty()
                    Text("Statistiques d'écoute$listenerSuffix", maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { inner ->
        val s = stats ?: return@Scaffold
        val hasAnyActivity = s.recordedListeningMs > 0L || s.catalogListenedMs > 0L || s.finishedBooksCount > 0

        if (!hasAnyActivity) {
            Box(Modifier.padding(inner).fillMaxSize()) {
                EmptyState(
                    icon = Icons.Filled.BarChart,
                    title = "Aucune écoute enregistrée",
                    message = "Lancez un livre pour suivre votre temps d'écoute quotidien, vos séries et vos auteurs favoris.",
                )
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .padding(inner)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiCard(
                    icon = Icons.Filled.Headphones,
                    label = "Écoute enregistrée",
                    value = formatHoursMinutes(s.recordedListeningMs),
                    subtitle = "Aujourd'hui : ${formatHoursMinutes(s.todayMs)}",
                    modifier = Modifier.weight(1f),
                )
                KpiCard(
                    icon = Icons.Filled.LocalFireDepartment,
                    label = "Série active",
                    value = if (s.currentStreakDays <= 1) "${s.currentStreakDays} jour" else "${s.currentStreakDays} jours",
                    subtitle = "Record : ${s.bestStreakDays} j",
                    modifier = Modifier.weight(1f),
                )
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                KpiCard(
                    icon = Icons.Filled.DoneAll,
                    label = "Livres terminés",
                    value = s.finishedBooksCount.toString(),
                    subtitle = "${s.inProgressBooksCount} en écoute",
                    modifier = Modifier.weight(1f),
                )
                KpiCard(
                    icon = Icons.Filled.Schedule,
                    label = "Catalogue écouté",
                    value = formatHoursMinutes(s.catalogListenedMs),
                    subtitle = "Avancement cumulé",
                    modifier = Modifier.weight(1f),
                )
            }

            InfoCard(title = "Activité quotidienne") {
                val options = listOf("7 derniers jours", "30 derniers jours")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { idx, label ->
                        SegmentedButton(
                            selected = rangeIndex == idx,
                            onClick = { rangeIndex = idx },
                            shape = SegmentedButtonDefaults.itemShape(idx, options.size),
                        ) {
                            Text(label)
                        }
                    }
                }
                val bars = if (rangeIndex == 0) s.dailyBars7 else s.dailyBars30
                val periodTotal = if (rangeIndex == 0) s.last7DaysMs else s.last30DaysMs
                Text(
                    text = "Total sur la période : ${formatHoursMinutes(periodTotal)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DailyBarChart(
                    bars = bars,
                    compact = rangeIndex == 1,
                )
            }

            if (s.topBooks.isNotEmpty()) {
                InfoCard(title = "Livres les plus écoutés") {
                    s.topBooks.forEachIndexed { idx, book ->
                        if (idx > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        TopBookRow(book = book, onClick = { onOpenBook(book.bookId) })
                    }
                }
            }

            if (s.topAuthors.isNotEmpty()) {
                InfoCard(title = "Auteurs favoris") {
                    s.topAuthors.forEachIndexed { idx, author ->
                        if (idx > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        CreatorRow(rank = idx + 1, entry = author)
                    }
                }
            }

            if (s.topNarrators.isNotEmpty()) {
                InfoCard(title = "Voix les plus écoutées") {
                    s.topNarrators.forEachIndexed { idx, narrator ->
                        if (idx > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        CreatorRow(rank = idx + 1, entry = narrator)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun KpiCard(
    icon: ImageVector,
    label: String,
    value: String,
    subtitle: String,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun DailyBarChart(
    bars: List<DayListening>,
    compact: Boolean,
) {
    val maxMs = remember(bars) { bars.maxOfOrNull { it.durationMs }?.coerceAtLeast(60_000L) ?: 60_000L }
    var selected by remember(bars) { mutableStateOf(bars.lastOrNull()) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        selected?.let { day ->
            Text(
                text = "${day.fullLabel} : ${formatHoursMinutes(day.durationMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(132.dp)
                .padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            bars.forEachIndexed { index, bar ->
                val fraction = if (bar.durationMs <= 0L) 0.04f else (bar.durationMs.toFloat() / maxMs).coerceIn(0.08f, 1f)
                val isSelected = selected?.date == bar.date
                val isToday = index == bars.lastIndex
                val color = when {
                    isSelected -> MaterialTheme.colorScheme.primary
                    bar.durationMs > 0L -> MaterialTheme.colorScheme.primary.copy(alpha = 0.65f)
                    isToday -> MaterialTheme.colorScheme.outlineVariant
                    else -> MaterialTheme.colorScheme.surfaceVariant
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable { selected = bar }
                        .semantics {
                            contentDescription = "${bar.fullLabel}, ${formatHoursMinutes(bar.durationMs)}"
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Bottom,
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.BottomCenter,
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(fraction)
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(color),
                        )
                    }
                    if (!compact) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = bar.shortLabel,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }

        if (compact && bars.size >= 2) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(bars.first().shortLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(bars[bars.size / 2].shortLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("Aujourd'hui", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun TopBookRow(book: BookStatEntry, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Ouvrir la fiche du livre", onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BookCover(book.cover, book.title, size = 44.dp, cornerRadius = 8.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                book.author?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.finished) {
                    Tag("terminé", icon = Icons.Filled.DoneAll)
                }
            }
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatHoursMinutes(book.listenedMs),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun CreatorRow(rank: Int, entry: CreatorStatEntry) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$rank.",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(24.dp),
            textAlign = TextAlign.Start,
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val booksLabel = if (entry.booksCount <= 1) "1 livre" else "${entry.booksCount} livres"
            val finishedPart = if (entry.finishedCount > 0) " · ${entry.finishedCount} terminé${if (entry.finishedCount > 1) "s" else ""}" else ""
            Text(
                text = booksLabel + finishedPart,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = formatHoursMinutes(entry.listenedMs),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

private fun formatHoursMinutes(ms: Long): String {
    if (ms <= 0L) return "0 min"
    return Formats.duration(ms, approximate = false)
}
