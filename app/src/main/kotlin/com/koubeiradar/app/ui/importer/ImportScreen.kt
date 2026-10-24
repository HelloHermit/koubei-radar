package com.koubeiradar.app.ui.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.app.R
import com.koubeiradar.app.platform.ImageLoader
import com.koubeiradar.app.platform.ImportLimits
import com.koubeiradar.app.ui.components.KoubeiIcons
import com.koubeiradar.app.ui.components.NoticeCard
import com.koubeiradar.app.ui.components.NoticeTone
import com.koubeiradar.app.ui.components.SectionTitle
import com.koubeiradar.app.ui.components.ShopFacts
import com.koubeiradar.app.ui.components.TagPill
import com.koubeiradar.app.ui.components.asString
import com.koubeiradar.app.ui.importMessageRes
import com.koubeiradar.core.model.Shop

private const val THUMBNAIL_PX = 320

/** 导入: screenshots or pasted text in, shops in the candidate pool out. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    viewModel: ImportViewModel,
    imageLoader: ImageLoader,
    onOpenSettings: () -> Unit,
    onMerged: (count: Int) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportEvent.Merged -> onMerged(event.count)
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(ImportLimits.MAX_IMAGES)) { uris: List<Uri> ->
        viewModel.addImages(uris.map(Uri::toString))
    }
    Scaffold(topBar = { TopAppBar(title = { Text(stringResource(R.string.import_title)) }) }) { padding ->
        Column(
            modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Instructions()
            NoticeCard(
                text = stringResource(R.string.import_privacy, state.provider.label),
                icon = Icons.Default.Lock,
            )
            if (state.configured == false) {
                NoticeCard(text = stringResource(R.string.import_not_configured), tone = NoticeTone.ERROR, icon = Icons.Default.Warning) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_go_settings)) }
                }
            } else if (state.visionModelMissing) {
                NoticeCard(text = stringResource(R.string.import_vision_missing), icon = Icons.Default.Warning)
            }
            val editable = state.phase !is ImportPhase.Extracting
            ImagePicker(
                images = state.images,
                capped = state.imagesCapped,
                enabled = editable && state.images.size < ImportLimits.MAX_IMAGES,
                imageLoader = imageLoader,
                onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                onRemove = viewModel::removeImage,
            )
            OutlinedTextField(
                value = state.text,
                onValueChange = viewModel::onTextChanged,
                enabled = editable,
                label = { Text(stringResource(R.string.import_text_label)) },
                supportingText = { Text(stringResource(R.string.import_text_counter, state.text.length, ImportLimits.MAX_TEXT_CHARS)) },
                minLines = 4,
                maxLines = 10,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = viewModel::extract,
                enabled = state.canExtract && state.configured == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.import_action_recognize))
            }
            when (val phase = state.phase) {
                ImportPhase.Editing -> Unit
                ImportPhase.Extracting -> Extracting(onCancel = viewModel::cancel)
                is ImportPhase.Preview -> Preview(phase, onMerge = viewModel::commit, onBack = viewModel::backToEditing)
                is ImportPhase.Failed -> Failure(phase.error, onRetry = viewModel::extract, onOpenSettings = onOpenSettings)
            }
        }
    }
}

@Composable
private fun Instructions() {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(stringResource(R.string.import_howto_title))
            Text(stringResource(R.string.import_howto_body), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun ImagePicker(
    images: List<String>,
    capped: Boolean,
    enabled: Boolean,
    imageLoader: ImageLoader,
    onPick: () -> Unit,
    onRemove: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onPick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
            Icon(KoubeiIcons.Download, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.import_pick_images, ImportLimits.MAX_IMAGES), modifier = Modifier.padding(start = 8.dp))
        }
        if (images.isNotEmpty()) {
            Text(
                stringResource(R.string.import_images_selected, images.size, ImportLimits.MAX_IMAGES),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(images, key = { it }) { ref ->
                    Thumbnail(ref, index = images.indexOf(ref) + 1, imageLoader = imageLoader, onRemove = { onRemove(ref) })
                }
            }
        }
        if (capped) Text(stringResource(R.string.import_images_capped, ImportLimits.MAX_IMAGES), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Thumbnail(ref: String, index: Int, imageLoader: ImageLoader, onRemove: () -> Unit) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, ref) { value = imageLoader.thumbnail(ref, THUMBNAIL_PX)?.asImageBitmap() }
    Box(Modifier.size(88.dp)) {
        Box(Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant)) {
            bitmap?.let {
                Image(it, contentDescription = stringResource(R.string.cd_screenshot, index), contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        IconButton(onClick = onRemove, modifier = Modifier.align(Alignment.TopEnd)) {
            Box(Modifier.size(24.dp).background(Color.Black.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.cd_remove_screenshot, index),
                    modifier = Modifier.size(16.dp),
                    tint = Color.White,
                )
            }
        }
    }
}

@Composable
private fun Extracting(onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 3.dp)
        Text(stringResource(R.string.import_extracting), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = onCancel) { Text(stringResource(R.string.action_cancel)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Preview(preview: ImportPhase.Preview, onMerge: () -> Unit, onBack: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (preview.shops.isEmpty()) {
            NoticeCard(text = stringResource(R.string.import_preview_empty), icon = Icons.Default.Warning)
        } else {
            SectionTitle(stringResource(R.string.import_preview_title, preview.shops.size))
            preview.shops.forEach { ImportedShopCard(it) }
        }
        preview.notices.forEach { NoticeCard(text = it.asString()) }
        if (preview.notes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SectionTitle(stringResource(R.string.import_notes_title))
                preview.notes.forEach { Text("• $it", style = MaterialTheme.typography.bodyMedium) }
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (preview.shops.isNotEmpty()) Button(onClick = onMerge) { Text(stringResource(R.string.import_action_merge)) }
            OutlinedButton(onClick = onBack) { Text(stringResource(R.string.import_action_back)) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportedShopCard(shop: Shop) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(shop.name, style = MaterialTheme.typography.titleSmall)
            ShopFacts(shop)
            if (shop.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    shop.tags.forEach { TagPill(it) }
                }
            }
        }
    }
}

@Composable
private fun Failure(error: ImportError, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    val message = when (error) {
        ImportError.NotConfigured -> R.string.error_llm_not_configured
        is ImportError.Llm -> error.kind.importMessageRes()
        ImportError.NoReadableImages -> R.string.import_error_no_images
        ImportError.Unexpected -> R.string.import_error_unexpected
    }
    val needsSettings = error == ImportError.NotConfigured ||
        (error is ImportError.Llm && (error.kind == LlmException.Kind.AUTH || error.kind == LlmException.Kind.BAD_REQUEST))
    NoticeCard(text = stringResource(message), tone = NoticeTone.ERROR, icon = Icons.Default.Warning) {
        if (error != ImportError.NotConfigured && error != ImportError.NoReadableImages) {
            TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        }
        if (needsSettings) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_go_settings)) }
    }
}
