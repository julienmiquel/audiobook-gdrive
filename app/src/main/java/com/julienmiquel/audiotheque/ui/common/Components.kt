package com.julienmiquel.audiotheque.ui.common

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.julienmiquel.audiotheque.AppContainer
import com.julienmiquel.audiotheque.core.model.BookStatus
import java.io.File

/** Conteneur de l'app, fourni à la racine de l'interface. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer non fourni") }

/**
 * Pochette : fichier local (image du dossier Drive ou des tags) ou URL Google Books ; à défaut,
 * initiales du titre sur fond teinté. Décorative pour TalkBack (le titre est lu à côté).
 */
@Composable
fun BookCover(cover: String?, title: String, size: Dp, modifier: Modifier = Modifier, cornerRadius: Dp = 8.dp) {
    val shape = RoundedCornerShape(cornerRadius)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initials(title),
            style = if (size >= 96.dp) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.SemiBold,
        )
        if (!cover.isNullOrBlank()) {
            AsyncImage(
                model = if (cover.startsWith("http")) cover else File(cover),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun initials(title: String): String {
    val words = title.split(' ', '-', '\'', '’').filter { w -> w.any { it.isLetterOrDigit() } }
    val significant = words.filter { it.length > 2 }.ifEmpty { words }
    return significant.take(2).joinToString("") { w -> w.first { it.isLetterOrDigit() }.uppercase() }.ifEmpty { "♪" }
}

/** Petit badge (« zip », « doublon », « en attente »). */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, container: Color = MaterialTheme.colorScheme.surfaceContainerHighest, content: Color = MaterialTheme.colorScheme.onSurfaceVariant, icon: ImageVector? = null) {
    Surface(color = container, contentColor = content, shape = RoundedCornerShape(6.dp), modifier = modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(Modifier.width(3.dp))
            }
            Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@Composable
fun StatusTag(status: BookStatus, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        BookStatus.IN_PROGRESS -> colors.primaryContainer to colors.onPrimaryContainer
        BookStatus.NEW -> colors.tertiaryContainer to colors.onTertiaryContainer
        BookStatus.DONE -> colors.secondaryContainer to colors.onSecondaryContainer
        else -> colors.surfaceContainerHighest to colors.onSurfaceVariant
    }
    Tag(Labels.status(status), modifier, container, content)
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { heading() },
    )
}

/** Carte de section (écrans Synchro, Fiche livre). */
@Composable
fun InfoCard(title: String?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.height(2.dp))
            }
            content()
        }
    }
}

/** Ligne « libellé : valeur », lue d'un seul tenant par TalkBack. */
@Composable
fun InfoRow(label: String, value: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { },
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.42f))
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(0.58f))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(8.dp))
            action()
        }
    }
}

/** Barre d'avancement d'un livre, avec sa description pour TalkBack. */
@Composable
fun BookProgress(percent: Float, modifier: Modifier = Modifier, label: String? = null) {
    Column(modifier) {
        LinearProgressIndicator(
            progress = { (percent / 100f).coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .semantics { contentDescription = "Avancement ${percent.toInt()} pour cent" },
        )
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
