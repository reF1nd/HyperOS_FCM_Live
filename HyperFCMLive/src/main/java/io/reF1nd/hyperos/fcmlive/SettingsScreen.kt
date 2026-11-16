package io.reF1nd.hyperos.fcmlive

import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.github.libxposed.service.XposedService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Checkbox
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InfiniteProgressIndicator
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.RadioButtonLocation
import top.yukonga.miuix.kmp.preference.RadioButtonPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.utils.overScrollVertical
import top.yukonga.miuix.kmp.utils.scrollEndHaptic

@Composable
private fun rememberXposedService(): XposedService? {
    var service by remember { mutableStateOf(App.xposedService) }
    val listener = remember { App.ServiceListener { service = it } }
    DisposableEffect(listener) {
        App.addServiceListener(listener)
        onDispose { App.removeServiceListener(listener) }
    }
    return service
}

@Composable
fun SettingsRoot() {
    val service = rememberXposedService()
    val preferences: SharedPreferences? = remember(service) {
        runCatching {
            service?.takeIf { (it.frameworkProperties and XposedService.PROP_CAP_REMOTE) != 0L }
                ?.getRemotePreferences(ExemptConfig.PREFERENCES_NAME)
        }.getOrNull()
    }
    val serviceAvailable = preferences != null
    var settings by remember(preferences) {
        mutableStateOf(preferences?.let(::readExemptSettings) ?: ExemptSettings())
    }
    var selectedListName by rememberSaveable { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    var showLauncherIcon by remember { mutableStateOf(isLauncherIconShown(context)) }
    val apps by produceState<List<FcmApp>?>(null, context) {
        value = withContext(Dispatchers.IO) {
            runCatching { loadFcmApps(context) }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(serviceAvailable) {
        if (!serviceAvailable) selectedListName = null
    }

    val selectedList = selectedListName?.let { name -> ExemptListType.entries.firstOrNull { it.name == name } }
    if (selectedList == null || !serviceAvailable) {
        SettingsScreen(
            serviceAvailable = serviceAvailable,
            settings = settings,
            apps = apps,
            onModeChange = { type, mode ->
                settings = settings.with(type, settings[type].copy(mode = mode))
                preferences?.edit()?.putString(type.modeKey, ExemptConfig.normalizeMode(mode))?.apply()
            },
            onOpenList = { selectedListName = it.name },
            showLauncherIcon = showLauncherIcon,
            onShowLauncherIconChange = {
                showLauncherIcon = it
                setLauncherIconShown(context, it)
            }
        )
    } else {
        FcmAppsScreen(
            title = stringResource(
                when (selectedList) {
                    ExemptListType.Push -> R.string.push_list_title
                    ExemptListType.ForceStop -> R.string.force_stop_list_title
                }
            ),
            apps = apps,
            selectedPackages = settings[selectedList].packages,
            onBack = { selectedListName = null },
            onTogglePackage = { packageName ->
                val list = settings[selectedList]
                val packages = if (packageName in list.packages) list.packages - packageName
                else list.packages + packageName
                settings = settings.with(selectedList, list.copy(packages = packages))
                preferences.edit().putStringSet(selectedList.packagesKey, HashSet(packages)).apply()
            }
        )
    }
}

private fun cardModifier() = Modifier
    .fillMaxWidth()
    .padding(horizontal = 12.dp)
    .padding(bottom = 12.dp)

@Composable
fun SettingsScreen(
    serviceAvailable: Boolean,
    settings: ExemptSettings,
    apps: List<FcmApp>?,
    onModeChange: (ExemptListType, String) -> Unit,
    onOpenList: (ExemptListType) -> Unit,
    showLauncherIcon: Boolean,
    onShowLauncherIconChange: (Boolean) -> Unit
) {
    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.settings_title),
                scrollBehavior = scrollBehavior
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            contentPadding = PaddingValues(top = padding.calculateTopPadding())
        ) {
            if (!serviceAvailable) {
                item { Spacer(Modifier.height(12.dp)) }
                item { ServiceUnavailableCard() }
            }
            ExemptListType.entries.forEach { type ->
                val list = settings[type]
                val selectedCount = apps?.count { it.packageName in list.packages } ?: 0
                item(key = "${type.name}_section") {
                    SmallTitle(
                        stringResource(
                            when (type) {
                                ExemptListType.Push -> R.string.push_section_title
                                ExemptListType.ForceStop -> R.string.force_stop_section_title
                            }
                        )
                    )
                }
                item(key = "${type.name}_mode") {
                    ModeCard(list.mode, serviceAvailable) { onModeChange(type, it) }
                }
                item(key = "${type.name}_apps") {
                    Card(cardModifier()) {
                        ArrowPreference(
                            title = stringResource(R.string.apps_list_title),
                            summary = stringResource(R.string.fcm_apps_count, selectedCount, apps?.size ?: 0),
                            onClick = if (serviceAvailable) ({ onOpenList(type) }) else null,
                            enabled = serviceAvailable
                        )
                    }
                }
            }
            item { SmallTitle(stringResource(R.string.misc_section_title)) }
            item {
                Card(cardModifier()) {
                    // The launcher alias is managed by PackageManager and works without the service.
                    SwitchPreference(
                        checked = showLauncherIcon,
                        onCheckedChange = onShowLauncherIconChange,
                        title = stringResource(R.string.show_launcher_icon_title),
                        summary = stringResource(R.string.show_launcher_icon_summary)
                    )
                }
            }
            item { SmallTitle(stringResource(R.string.notice_title)) }
            item { NoticeCard() }
            item {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
private fun ModeCard(mode: String, enabled: Boolean, onModeChange: (String) -> Unit) {
    Card(cardModifier()) {
        RadioButtonPreference(
            title = stringResource(R.string.exempt_mode_default),
            selected = mode == ExemptConfig.DEFAULT,
            onClick = { onModeChange(ExemptConfig.DEFAULT) },
            summary = stringResource(R.string.exempt_mode_default_summary),
            radioButtonLocation = RadioButtonLocation.End,
            enabled = enabled
        )
        RadioButtonPreference(
            title = stringResource(R.string.exempt_mode_blacklist),
            selected = mode == ExemptConfig.BLACKLIST,
            onClick = { onModeChange(ExemptConfig.BLACKLIST) },
            summary = stringResource(R.string.exempt_mode_blacklist_summary),
            radioButtonLocation = RadioButtonLocation.End,
            enabled = enabled
        )
        RadioButtonPreference(
            title = stringResource(R.string.exempt_mode_whitelist),
            selected = mode == ExemptConfig.WHITELIST,
            onClick = { onModeChange(ExemptConfig.WHITELIST) },
            summary = stringResource(R.string.exempt_mode_whitelist_summary),
            radioButtonLocation = RadioButtonLocation.End,
            enabled = enabled
        )
    }
}

@Composable
private fun ServiceUnavailableCard() {
    Card(cardModifier()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.service_unavailable_title),
                color = MiuixTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.service_unavailable_message),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun NoticeCard() {
    Card(cardModifier()) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = stringResource(R.string.notice_message),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
            )
        }
    }
}

@Composable
fun FcmAppsScreen(
    title: String,
    apps: List<FcmApp>?,
    selectedPackages: Set<String>,
    onBack: () -> Unit,
    onTogglePackage: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var searching by rememberSaveable { mutableStateOf(false) }
    val closeSearch = {
        searching = false
        query = ""
    }
    val back = { if (searching) closeSearch() else onBack() }
    BackHandler(onBack = back)

    val trimmedQuery = query.trim()
    val filteredApps = remember(apps, trimmedQuery) {
        when {
            apps == null -> emptyList()
            trimmedQuery.isEmpty() -> apps
            else -> apps.filter {
                it.label.contains(trimmedQuery, ignoreCase = true) ||
                        it.packageName.contains(trimmedQuery, ignoreCase = true)
            }
        }
    }

    val scrollBehavior = MiuixScrollBehavior()
    Scaffold(
        topBar = {
            SmallTopAppBar(
                title = title,
                navigationIcon = {
                    IconButton(onClick = back) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = stringResource(R.string.common_back),
                            tint = MiuixTheme.colorScheme.onSurface
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
                bottomContent = {
                    if (searching) {
                        InputField(
                            query = query,
                            onQueryChange = { query = it },
                            onSearch = {},
                            expanded = true,
                            onExpandedChange = {},
                            modifier = cardModifier(),
                            label = stringResource(R.string.fcm_apps_search_hint)
                        )
                    } else {
                        // Placeholder field: a tap anywhere switches to the real search field.
                        Box(
                            modifier = cardModifier().pointerInput(Unit) {
                                detectTapGestures { searching = true }
                            }
                        ) {
                            InputField(
                                query = "",
                                onQueryChange = {},
                                onSearch = {},
                                expanded = false,
                                onExpandedChange = { if (it) searching = true },
                                modifier = Modifier.fillMaxWidth(),
                                label = stringResource(R.string.fcm_apps_search_hint)
                            )
                        }
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .scrollEndHaptic()
                .overScrollVertical()
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .imePadding(),
            contentPadding = PaddingValues(top = padding.calculateTopPadding())
        ) {
            if (apps == null) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        InfiniteProgressIndicator(color = MiuixTheme.colorScheme.primary)
                    }
                }
            } else {
                val visibleApps = if (searching) filteredApps else apps
                if (visibleApps.isEmpty()) {
                    item {
                        Text(
                            text = stringResource(
                                if (apps.isEmpty()) R.string.fcm_apps_empty else R.string.fcm_apps_no_match
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 32.dp),
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            textAlign = TextAlign.Center
                        )
                    }
                } else {
                    item {
                        SmallTitle(
                            stringResource(
                                R.string.fcm_apps_count,
                                visibleApps.count { it.packageName in selectedPackages },
                                visibleApps.size
                            )
                        )
                    }
                    items(visibleApps, key = { it.packageName }) { app ->
                        FcmAppItem(app, app.packageName in selectedPackages) {
                            onTogglePackage(app.packageName)
                        }
                    }
                }
            }
            item {
                Spacer(
                    Modifier
                        .height(24.dp)
                        .navigationBarsPadding()
                )
            }
        }
    }
}

@Composable
private fun FcmAppItem(app: FcmApp, checked: Boolean, onClick: () -> Unit) {
    Card(cardModifier()) {
        BasicComponent(
            title = app.label,
            summary = app.packageName,
            startAction = {
                AsyncImage(
                    model = app.packageInfo,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .size(40.dp)
                )
            },
            endActions = {
                Checkbox(
                    state = if (checked) ToggleableState.On else ToggleableState.Off,
                    onClick = onClick
                )
            },
            onClick = onClick
        )
    }
}
