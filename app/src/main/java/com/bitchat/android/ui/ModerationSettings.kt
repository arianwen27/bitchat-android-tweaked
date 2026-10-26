package com.bitchat.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bitchat.android.R
import com.bitchat.android.spam.SpamMode
import com.bitchat.android.spam.SpamShieldPreferences
import com.bitchat.android.spam.SpamFilterPreferences
import com.bitchat.android.spam.SpamShieldService
import com.bitchat.android.translate.TranslationDisplayMode
import com.bitchat.android.translate.TranslationEngineMode
import com.bitchat.android.translate.TranslationLanguageCatalog
import com.bitchat.android.translate.TranslationPreferences
import com.bitchat.android.translate.TranslationStore
import com.bitchat.android.ui.theme.BitchatFontFamily

/**
 * Settings for the local spam shield and for auto translation.
 *
 * Both sections write straight to their preference singletons, which is the pattern this app already
 * uses for language and PoW settings: the state lives in the process, not in a view model, so the
 * settings sheet does not need per-field plumbing.
 */

@Composable
fun SpamShieldSettingsSection(onOpenReview: () -> Unit, modifier: Modifier = Modifier) {
    val colorScheme = MaterialTheme.colorScheme
    val enabled by SpamFilterPreferences.enabled.collectAsState()
    val mode by SpamShieldPreferences.mode.collectAsState()
    val autoMute by SpamShieldPreferences.autoMuteMinutes.collectAsState()
    val flagVisible by SpamShieldPreferences.flagVisible.collectAsState()
    val requirePow by SpamShieldPreferences.requireProofOfWork.collectAsState()
    val blocklist by SpamShieldPreferences.blocklist.collectAsState()
    val hiddenTotal by SpamShieldService.hiddenCount.collectAsState()
    var draftPhrase by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionLabel(stringResource(R.string.moderation_section_spam))

        Text(
            text = stringResource(R.string.spam_master_note),
            fontFamily = BitchatFontFamily,
            fontSize = 12.sp,
            color = colorScheme.onSurfaceVariant
        )

        ChipLabel(stringResource(R.string.spam_sensitivity_title))
        ChipRow(
            options = listOf(
                stringResource(R.string.spam_mode_off),
                stringResource(R.string.spam_mode_gentle),
                stringResource(R.string.spam_mode_balanced),
                stringResource(R.string.spam_mode_aggressive)
            ),
            selectedIndex = mode.ordinal,
            enabled = enabled,
            onSelect = { index -> SpamShieldPreferences.setMode(SpamMode.values()[index]) }
        )

        ChipLabel(stringResource(R.string.spam_auto_mute_title))
        ChipRow(
            options = listOf(
                stringResource(R.string.spam_mute_off),
                stringResource(R.string.spam_mute_10),
                stringResource(R.string.spam_mute_30),
                stringResource(R.string.spam_mute_60),
                stringResource(R.string.spam_mute_180)
            ),
            selectedIndex = listOf(0, 10, 30, 60, 180).indexOf(autoMute).coerceAtLeast(1),
            enabled = enabled,
            onSelect = { index -> SpamShieldPreferences.setAutoMuteMinutes(listOf(0, 10, 30, 60, 180)[index]) }
        )

        SwitchRow(
            title = stringResource(R.string.spam_flag_visible_title),
            subtitle = stringResource(R.string.spam_flag_visible_subtitle),
            checked = flagVisible,
            onChange = { SpamShieldPreferences.setFlagVisible(it) }
        )

        SwitchRow(
            title = stringResource(R.string.spam_pow_title),
            subtitle = stringResource(R.string.spam_pow_subtitle),
            checked = requirePow,
            onChange = { SpamShieldPreferences.setRequireProofOfWork(it) }
        )

        ChipLabel(stringResource(R.string.spam_blocklist_title))
        OutlinedTextField(
            value = draftPhrase,
            onValueChange = { draftPhrase = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            placeholder = {
                Text(stringResource(R.string.spam_blocklist_hint), fontSize = 13.sp)
            },
            trailingIcon = {
                Text(
                    text = stringResource(R.string.spam_blocklist_add),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.primary,
                    modifier = Modifier
                        .clickable {
                            if (SpamShieldPreferences.addBlockedPhrase(draftPhrase)) draftPhrase = ""
                        }
                        .padding(end = 12.dp)
                )
            },
            singleLine = true,
            minLines = 1,
            maxLines = 2
        )
        if (blocklist.isEmpty()) {
            Text(
                text = stringResource(R.string.spam_blocklist_empty),
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(blocklist) { phrase ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = colorScheme.surfaceVariant,
                        modifier = Modifier.clickable {
                            SpamShieldPreferences.removeBlockedPhrase(phrase)
                        }
                    ) {
                        Text(
                            text = phrase,
                            fontFamily = BitchatFontFamily,
                            fontSize = 12.sp,
                            color = colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenReview)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.spam_review_action, hiddenTotal),
                fontFamily = BitchatFontFamily,
                fontSize = 13.sp,
                color = colorScheme.primary
            )
        }
    }
}

/**
 * Auto-translation settings.
 *
 * [currentConversationKey] is the conversation key string the chat screen uses, so a per-channel
 * target language can be set for the channel the user is reading.
 */
@Composable
fun TranslationSettingsSection(
    currentConversationKey: String?,
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme
    val enabled by TranslationPreferences.enabled.collectAsState()
    val engineMode by TranslationPreferences.engineMode.collectAsState()
    val displayMode by TranslationPreferences.displayMode.collectAsState()
    val allowPrivate by TranslationPreferences.allowPrivateTranslation.collectAsState()
    val targetLang by TranslationPreferences.targetLang.collectAsState()
    val onlineUrl by TranslationPreferences.onlineBaseUrl.collectAsState()
    val conversationLangs by TranslationPreferences.conversationLangs.collectAsState()
    var urlDraft by remember { mutableStateOf(onlineUrl) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SectionLabel(stringResource(R.string.moderation_section_translation))

        SwitchRow(
            title = stringResource(R.string.translation_toggle_title),
            subtitle = stringResource(R.string.translation_toggle_subtitle),
            checked = enabled,
            onChange = { value ->
                TranslationPreferences.setEnabled(value)
                TranslationStore.syncEngine()
            }
        )

        ChipLabel(stringResource(R.string.translation_engine_title))
        ChipRow(
            options = listOf(
                stringResource(R.string.translation_engine_off),
                stringResource(R.string.translation_engine_device),
                stringResource(R.string.translation_engine_online)
            ),
            selectedIndex = engineMode.ordinal,
            enabled = enabled,
            onSelect = { index ->
                TranslationPreferences.setEngineMode(TranslationEngineMode.values()[index])
                TranslationStore.syncEngine()
            }
        )
        if (engineMode == TranslationEngineMode.OnDevice) {
            Text(
                text = stringResource(R.string.translation_models_note),
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }

        ChipLabel(stringResource(R.string.translation_display_title))
        ChipRow(
            options = listOf(
                stringResource(R.string.translation_display_off),
                stringResource(R.string.translation_display_below),
                stringResource(R.string.translation_display_replace)
            ),
            selectedIndex = displayMode.ordinal,
            enabled = enabled,
            onSelect = { index ->
                TranslationPreferences.setDisplayMode(TranslationDisplayMode.values()[index])
            }
        )

        ChipLabel(stringResource(R.string.translation_target_title))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(TranslationLanguageCatalog.targets) { language ->
                val selected = language.tag == targetLang
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = if (selected) colorScheme.primary else colorScheme.surfaceVariant,
                    modifier = Modifier.clickable {
                        TranslationPreferences.setTargetLang(language.tag)
                    }
                ) {
                    Text(
                        text = language.label,
                        fontFamily = BitchatFontFamily,
                        fontSize = 12.sp,
                        fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                        color = if (selected) colorScheme.onPrimary else colorScheme.onSurface,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }

        if (currentConversationKey != null) {
            val override = conversationLangs[currentConversationKey]
            ChipLabel(stringResource(R.string.translation_channel_title))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(
                        R.string.translation_channel_state,
                        TranslationLanguageCatalog.labelFor(override ?: targetLang)
                    ),
                    fontFamily = BitchatFontFamily,
                    fontSize = 13.sp,
                    color = colorScheme.onSurface
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = stringResource(R.string.translation_channel_use_global),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = colorScheme.primary,
                    modifier = Modifier
                        .clickable { TranslationPreferences.setConversationLanguage(currentConversationKey, null) }
                        .padding(vertical = 4.dp)
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(TranslationLanguageCatalog.targets) { language ->
                    val selected = override == language.tag
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) colorScheme.primary else colorScheme.surfaceVariant,
                        modifier = Modifier.clickable {
                            TranslationPreferences.setConversationLanguage(currentConversationKey, language.tag)
                        }
                    ) {
                        Text(
                            text = language.label,
                            fontFamily = BitchatFontFamily,
                            fontSize = 12.sp,
                            color = if (selected) colorScheme.onPrimary else colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        if (currentConversationKey != null) {
            val sourceLangs by TranslationPreferences.conversationSourceLangs.collectAsState()
            val sourceOverride = sourceLangs[currentConversationKey]
            ChipLabel(stringResource(R.string.translation_source_title))
            Text(
                text = stringResource(R.string.translation_source_note),
                fontFamily = BitchatFontFamily,
                fontSize = 13.sp,
                color = colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (sourceOverride == null) {
                        stringResource(R.string.translation_source_state)
                    } else {
                        stringResource(
                            R.string.translation_source_current,
                            TranslationLanguageCatalog.labelFor(sourceOverride)
                        )
                    },
                    fontFamily = BitchatFontFamily,
                    fontSize = 13.sp,
                    color = colorScheme.onSurface
                )
                if (sourceOverride != null) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.translation_source_use_auto),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                TranslationPreferences.setConversationSourceLanguage(currentConversationKey, null)
                                TranslationStore.clear()
                            }
                            .padding(vertical = 4.dp)
                    )
                }
            }
            // Auto first, then the catalog: the user picks the language the channel speaks, and the
            // cache is dropped so the next render uses the model for that pair instead of the old guess.
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    val autoSelected = sourceOverride == null
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (autoSelected) colorScheme.primary else colorScheme.surfaceVariant,
                        modifier = Modifier.clickable {
                            TranslationPreferences.setConversationSourceLanguage(currentConversationKey, null)
                            TranslationStore.clear()
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.translation_source_auto),
                            fontFamily = BitchatFontFamily,
                            fontSize = 12.sp,
                            color = if (autoSelected) colorScheme.onPrimary else colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
                items(TranslationLanguageCatalog.targets) { language ->
                    val selected = sourceOverride == language.tag
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (selected) colorScheme.primary else colorScheme.surfaceVariant,
                        modifier = Modifier.clickable {
                            TranslationPreferences.setConversationSourceLanguage(currentConversationKey, language.tag)
                            TranslationStore.clear()
                        }
                    ) {
                        Text(
                            text = language.label,
                            fontFamily = BitchatFontFamily,
                            fontSize = 12.sp,
                            color = if (selected) colorScheme.onPrimary else colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        SwitchRow(
            title = stringResource(R.string.translation_private_title),
            subtitle = stringResource(R.string.translation_private_subtitle),
            checked = allowPrivate,
            onChange = { TranslationPreferences.setAllowPrivateTranslation(it) }
        )

        if (engineMode == TranslationEngineMode.Online) {
            ChipLabel(stringResource(R.string.translation_endpoint_title))
            OutlinedTextField(
                value = urlDraft,
                onValueChange = { urlDraft = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
                placeholder = {
                    Text(stringResource(R.string.translation_endpoint_hint), fontSize = 13.sp)
                },
                trailingIcon = {
                    Text(
                        text = stringResource(R.string.spam_blocklist_add),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = colorScheme.primary,
                        modifier = Modifier
                            .clickable {
                                TranslationPreferences.setOnlineBaseUrl(urlDraft)
                                TranslationStore.syncEngine()
                            }
                            .padding(end = 12.dp)
                    )
                },
                singleLine = true,
                minLines = 1,
                maxLines = 2
            )
        }

            Text(
                text = stringResource(R.string.translation_endpoint_note),
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        fontFamily = BitchatFontFamily,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
private fun ChipLabel(text: String) {
    Text(
        text = text,
        fontFamily = BitchatFontFamily,
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 2.dp)
    )
}

@Composable
private fun ChipRow(
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(options.withIndex().toList()) { (index, label) ->
            val selected = index == selectedIndex
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = if (selected) colorScheme.primary else colorScheme.surfaceVariant,
                modifier = Modifier.clickable(enabled = enabled) { onSelect(index) }
            ) {
                Text(
                    text = label,
                    fontFamily = BitchatFontFamily,
                    fontSize = 12.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
                    color = if (selected) colorScheme.onPrimary else colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val colorScheme = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onChange(!checked) }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = title,
                fontFamily = BitchatFontFamily,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = colorScheme.onSurface
            )
            Text(
                text = subtitle,
                fontFamily = BitchatFontFamily,
                fontSize = 12.sp,
                color = colorScheme.onSurfaceVariant,
                lineHeight = 17.sp
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = androidx.compose.ui.graphics.Color.White,
                checkedTrackColor = colorScheme.primary,
                uncheckedThumbColor = androidx.compose.ui.graphics.Color.White,
                uncheckedTrackColor = colorScheme.surfaceVariant
            )
        )
    }
}
