package com.koubeiradar.app.ui.agent

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.koubeiradar.agent.llm.LlmException
import com.koubeiradar.app.R
import com.koubeiradar.app.ui.components.KoubeiIcons
import com.koubeiradar.app.ui.components.MascotMood
import com.koubeiradar.app.ui.components.MessagePanel
import com.koubeiradar.app.ui.components.NoticeCard
import com.koubeiradar.app.ui.components.NoticeTone
import com.koubeiradar.app.ui.components.OrderButton
import com.koubeiradar.app.ui.components.ScoreRing
import com.koubeiradar.app.ui.components.ShopCard
import com.koubeiradar.app.ui.components.ShopFacts
import com.koubeiradar.app.ui.components.TagPill
import com.koubeiradar.app.ui.components.VerdictBadge
import com.koubeiradar.app.ui.components.WarningLine
import com.koubeiradar.app.ui.criteria.summaryText
import com.koubeiradar.app.ui.messageRes
import com.koubeiradar.core.model.Shop

/** 智能体: a chat with the shop advisor. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentScreen(
    viewModel: AgentViewModel,
    onOpenSettings: () -> Unit,
    onOpenDetail: (shopId: String) -> Unit,
    onOrder: (Shop) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    LaunchedEffect(state.items.size, state.running?.size) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last > 0) listState.animateScrollToItem(last)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.agent_title)) },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = viewModel::newConversation) {
                            Icon(Icons.Default.Add, contentDescription = stringResource(R.string.agent_action_new))
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).consumeWindowInsets(padding).fillMaxSize().imePadding()) {
            Conversation(
                state = state,
                listState = listState,
                onExample = viewModel::ask,
                onOpenSettings = onOpenSettings,
                onRuleBased = { viewModel.showRuleBased() },
                onCancel = viewModel::cancel,
                onRetry = viewModel::retry,
                onFallback = { viewModel.showRuleBased(it) },
                onOpenDetail = onOpenDetail,
                onOrder = onOrder,
                modifier = Modifier.weight(1f),
            )
            InputBar(
                value = state.input,
                enabled = state.configured == true && state.running == null,
                configured = state.configured == true,
                onValueChange = viewModel::onInputChanged,
                onSend = viewModel::send,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Conversation(
    state: AgentUiState,
    listState: LazyListState,
    onExample: (String) -> Unit,
    onOpenSettings: () -> Unit,
    onRuleBased: () -> Unit,
    onCancel: () -> Unit,
    onRetry: (Long) -> Unit,
    onFallback: (Long) -> Unit,
    onOpenDetail: (String) -> Unit,
    onOrder: (Shop) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.items.isEmpty() && state.running == null) {
            item(key = "welcome") {
                MessagePanel(
                    mood = MascotMood.HAPPY,
                    title = stringResource(R.string.agent_welcome_title),
                    body = stringResource(R.string.agent_welcome_body),
                )
            }
            if (state.configured == true) {
                item(key = "examples") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf(R.string.agent_example_1, R.string.agent_example_2, R.string.agent_example_3).forEach { res ->
                            val text = stringResource(res)
                            SuggestionChip(onClick = { onExample(text) }, label = { Text(text) })
                        }
                    }
                }
            }
        }
        if (state.configured == false) {
            item(key = "setup") {
                NoticeCard(text = stringResource(R.string.agent_setup_body), icon = KoubeiIcons.Sparkles) {
                    TextButton(onClick = onRuleBased) { Text(stringResource(R.string.agent_action_rule_based)) }
                    Button(onClick = onOpenSettings) { Text(stringResource(R.string.action_go_settings)) }
                }
            }
        }
        items(state.items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.User -> UserBubble(item.text)
                is ChatItem.Answer -> AnswerView(item, onOpenDetail, onOrder)
                is ChatItem.Failure -> FailureView(item, onRetry = { onRetry(item.id) }, onFallback = { onFallback(item.id) }, onOpenSettings = onOpenSettings)
                is ChatItem.RuleBased -> RuleBasedView(item, onOpenDetail, onOrder)
                is ChatItem.Cancelled -> Text(stringResource(R.string.agent_cancelled), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        state.running?.let { steps -> item(key = "running") { RunningCard(steps, onCancel) } }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 4.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
        }
    }
}

@Composable
private fun AnswerView(answer: ChatItem.Answer, onOpenDetail: (String) -> Unit, onOrder: (Shop) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(answer.summary, style = MaterialTheme.typography.bodyLarge)
                Text(
                    stringResource(R.string.agent_criteria_line, answer.criteria.summaryText()),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (answer.degraded) NoticeCard(text = stringResource(R.string.agent_degraded))
        answer.picks.forEach { pick -> PickCard(pick, onDetail = { onOpenDetail(pick.scored.shop.id) }, onOrder = { onOrder(pick.scored.shop) }) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PickCard(pick: PickUi, onDetail: () -> Unit, onOrder: () -> Unit) {
    val shop = pick.scored.shop
    val rankDescription = stringResource(R.string.agent_pick_rank, pick.rank)
    Card(
        onClick = onDetail,
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(28.dp).semantics(mergeDescendants = true) { contentDescription = rankDescription },
                ) {
                    Box(contentAlignment = Alignment.Center) { Text(pick.rank.toString(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold) }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(shop.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    VerdictBadge(pick.scored.verdict)
                }
                ScoreRing(pick.scored.score, pick.scored.verdict, size = 48.dp)
            }
            ShopFacts(shop)
            Text(pick.reason, style = MaterialTheme.typography.bodyMedium)
            if (pick.dishes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.agent_pick_dishes), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        pick.dishes.forEach { TagPill(it) }
                    }
                }
            }
            pick.cautions.forEach { WarningLine(it) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onDetail) { Text(stringResource(R.string.action_details)) }
                OrderButton(onOrder)
            }
        }
    }
}

@Composable
private fun FailureView(failure: ChatItem.Failure, onRetry: () -> Unit, onFallback: () -> Unit, onOpenSettings: () -> Unit) {
    val message = when (val error = failure.error) {
        AgentError.NotConfigured -> R.string.error_llm_not_configured
        is AgentError.Llm -> error.kind.messageRes()
        AgentError.Unexpected -> R.string.error_agent_unexpected
    }
    val needsSettings = when (val error = failure.error) {
        AgentError.NotConfigured -> true
        is AgentError.Llm -> error.kind == LlmException.Kind.AUTH || error.kind == LlmException.Kind.BAD_REQUEST
        AgentError.Unexpected -> false
    }
    NoticeCard(text = stringResource(message), tone = NoticeTone.ERROR, icon = Icons.Default.Warning) {
        if (failure.error != AgentError.NotConfigured) TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
        if (needsSettings) TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.action_go_settings)) }
        Button(onClick = onFallback) { Text(stringResource(R.string.agent_action_fallback)) }
    }
}

@Composable
private fun RuleBasedView(item: ChatItem.RuleBased, onOpenDetail: (String) -> Unit, onOrder: (Shop) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NoticeCard(
            text = stringResource(R.string.agent_rule_based_title) + "\n" +
                if (item.shops.isEmpty()) stringResource(R.string.agent_rule_based_empty) else stringResource(R.string.agent_rule_based_body, item.shops.size),
        )
        Text(
            stringResource(R.string.agent_criteria_line, item.criteria.summaryText()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        item.shops.forEach { scored -> ShopCard(scored, onDetail = { onOpenDetail(scored.shop.id) }, onOrder = { onOrder(scored.shop) }) }
    }
}

/** The live step log of a running request, with the cancel button. */
@Composable
private fun RunningCard(steps: List<AgentStep>, onCancel: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.agent_running_title), style = MaterialTheme.typography.titleSmall)
            LinearProgressIndicator(Modifier.fillMaxWidth())
            steps.forEachIndexed { index, step ->
                val last = index == steps.lastIndex
                when (step) {
                    is AgentStep.Thinking -> StepLine(stringResource(R.string.agent_step_thinking, step.iteration), if (last) StepStatus.RUNNING else StepStatus.DONE)
                    is AgentStep.Tool -> StepLine(step.text, step.status)
                }
            }
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text(stringResource(R.string.action_cancel)) }
        }
    }
}

@Composable
private fun StepLine(text: String, status: StepStatus) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            when (status) {
                StepStatus.RUNNING -> CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                StepStatus.DONE -> Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                StepStatus.FAILED -> Icon(Icons.Default.Warning, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.error)
            }
        }
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun InputBar(value: String, enabled: Boolean, configured: Boolean, onValueChange: (String) -> Unit, onSend: () -> Unit) {
    Surface(tonalElevation = 3.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                enabled = enabled,
                placeholder = { Text(stringResource(if (configured) R.string.agent_input_hint else R.string.agent_input_hint_disabled)) },
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (value.isNotBlank()) onSend() }),
            )
            FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.agent_action_send))
            }
        }
    }
}
