package dev.opencode.mobile.ui.onboarding

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.ProductRouteScreenViewEffect
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeCodeSnippet
import dev.opencode.mobile.ui.components.OpenCodePageIndicator
import dev.opencode.mobile.ui.components.OpenCodeListGroup
import dev.opencode.mobile.ui.components.OpenCodeListItem
import dev.opencode.mobile.ui.components.OpenCodeReadableColumn
import dev.opencode.mobile.ui.components.OpenCodeSkeletonIllustration
import dev.opencode.mobile.ui.components.OpenCodeTopBar
import dev.opencode.mobile.ui.components.OpenCodeTopBarLeading
import dev.opencode.mobile.ui.components.StartDecorator
import dev.opencode.mobile.ui.icons.OpenCodeIcons
import kotlinx.coroutines.launch

@Composable
fun AppOnboardingScreen(
    onSkip: () -> Unit,
    onConnect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showSetupGuide by rememberSaveable { mutableStateOf(false) }
    val pages = AppOnboardingCopy.pages
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()

    if (showSetupGuide) {
        BackHandler { showSetupGuide = false }
        ProductRouteScreenViewEffect(
            screenId = ProductAnalytics.Screen.APP_ONBOARDING_SETUP_GUIDE,
        )
        AppOnboardingSetupGuideScreen(
            onBack = {
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.APP_ONBOARDING_SETUP_GUIDE,
                    actionId = ProductAnalytics.Action.SETUP_GUIDE_BACK,
                )
                showSetupGuide = false
            },
            modifier = modifier,
        )
    } else {
        ProductRouteScreenViewEffect(screenId = ProductAnalytics.Screen.APP_ONBOARDING)
        AppOnboardingPagerScreen(
            pagerState = pagerState,
            pages = pages,
            onSkip = onSkip,
            onConnect = onConnect,
            onNeedServerFirst = {
                ProductAnalytics.logUserAction(
                    screenId = ProductAnalytics.Screen.APP_ONBOARDING,
                    actionId = ProductAnalytics.Action.ONBOARDING_OPEN_SETUP_GUIDE,
                )
                scope.launch {
                    pagerState.scrollToPage(1)
                    showSetupGuide = true
                }
            },
            modifier = modifier,
        )
    }
}

@Composable
private fun AppOnboardingPagerScreen(
    pagerState: PagerState,
    pages: List<AppOnboardingPage>,
    onSkip: () -> Unit,
    onConnect: () -> Unit,
    onNeedServerFirst: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val lastPage = pagerState.currentPage == pages.lastIndex
    var lastReportedPage by remember { mutableIntStateOf(pagerState.currentPage) }
    var advancedByNextButton by remember { mutableStateOf(false) }

    LaunchedEffect(pagerState.currentPage) {
        val page = pagerState.currentPage
        if (page == lastReportedPage) return@LaunchedEffect
        lastReportedPage = page
        if (advancedByNextButton) {
            advancedByNextButton = false
            return@LaunchedEffect
        }
        ProductAnalytics.logUserAction(
            screenId = ProductAnalytics.Screen.APP_ONBOARDING,
            actionId = ProductAnalytics.Action.ONBOARDING_SWIPE_PAGE,
        )
    }

    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        OpenCodeReadableColumn(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(
                        onClick = {
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.APP_ONBOARDING,
                                actionId = ProductAnalytics.Action.ONBOARDING_SKIP,
                            )
                            onSkip()
                        },
                    ) {
                        Text(
                            text = "Skip",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.weight(1f),
                ) { pageIndex ->
                    AppOnboardingPageContent(
                        page = pages[pageIndex],
                        onNeedServerFirst = onNeedServerFirst,
                        modifier =
                            Modifier
                                .fillMaxSize()
                                .padding(horizontal = 28.dp),
                    )
                }

                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OpenCodePageIndicator(pageCount = pages.size, currentPage = pagerState.currentPage)
                    OpenCodeButton(
                        variant =
                            OpenCodeButtonVariant.FullWidthText(
                                if (lastPage) "Connect to server" else "Next",
                            ),
                        onClick = {
                            if (lastPage) {
                                ProductAnalytics.logUserAction(
                                    screenId = ProductAnalytics.Screen.APP_ONBOARDING,
                                    actionId = ProductAnalytics.Action.ONBOARDING_CONNECT,
                                )
                                onConnect()
                            } else {
                                ProductAnalytics.logUserAction(
                                    screenId = ProductAnalytics.Screen.APP_ONBOARDING,
                                    actionId = ProductAnalytics.Action.ONBOARDING_NEXT,
                                )
                                advancedByNextButton = true
                                scope.launch {
                                    pagerState.animateScrollToPage(pagerState.currentPage + 1)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun AppOnboardingPageContent(
    page: AppOnboardingPage,
    onNeedServerFirst: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.Start,
    ) {
        OpenCodeSkeletonIllustration(
            topic = page.illustration,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(20.dp)),
        )
        Spacer(modifier = Modifier.height(16.dp))
        page.badge?.let { badge ->
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = badge,
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = page.title,
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (page.features.isNotEmpty()) {
            AppOnboardingFeatureList(features = page.features)
        } else {
            page.body?.let { body ->
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            page.setupLink?.let { link ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = link,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    textDecoration = TextDecoration.Underline,
                    modifier = Modifier.clickable(onClick = onNeedServerFirst),
                )
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun AppOnboardingFeatureList(
    features: List<AppOnboardingFeatureRow>,
    modifier: Modifier = Modifier,
) {
    OpenCodeListGroup(itemCount = features.size, modifier = modifier) { index ->
        val row = features[index]
        OpenCodeListItem(
            title = row.title,
            subtitle = row.detail,
            startDecorator =
                StartDecorator.Icon(
                    imageVector = featureIcon(row.id),
                    contentDescription = null,
                ),
        )
    }
}

private fun featureIcon(id: String) =
    when (id) {
        "sessions" -> OpenCodeIcons.List
        "chat" -> OpenCodeIcons.StopCircle
        "permissions" -> OpenCodeIcons.Check
        "changes" -> OpenCodeIcons.Description
        "live" -> OpenCodeIcons.Cloud
        else -> OpenCodeIcons.Widgets
    }

@Composable
private fun AppOnboardingSetupGuideScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    ) {
        OpenCodeTopBar(
            leading = OpenCodeTopBarLeading.Back(onClick = onBack),
        )
        OpenCodeReadableColumn(modifier = Modifier.weight(1f)) {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp),
                ) {
                    Text(
                        text = "Set up opencode serve",
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text =
                            "The app talks to an opencode server you run. Short path below — full detail in the official docs.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    SetupStep(
                        number = "Step 1",
                        title = "Start the server on your machine",
                        body =
                            "In your project, run the opencode server (headless). Keep it running while you use the phone.",
                        code = "opencode serve",
                    )
                    SetupStep(
                        number = "Step 2",
                        title = "Require a password",
                        body =
                            "Set OPENCODE_SERVER_PASSWORD. Username defaults to opencode unless you set OPENCODE_SERVER_USERNAME.",
                    )
                    SetupStep(
                        number = "Step 3",
                        title = "Reach it from your phone",
                        body =
                            "Recommended: Tailscale — install Tailscale on the machine and phone, then use the machine’s " +
                                "Tailscale IP or MagicDNS name as the server URL.\n\nAlso fine: same LAN, VPN, HTTPS, or " +
                                "another tunnel you manage. The app does not create a relay for you.",
                    )
                    SetupStep(
                        number = "Step 4",
                        title = "Connect in the app",
                        body =
                            "Enter base URL + password. The app checks GET /global/health, then saves a Connection Profile " +
                                "on this device.",
                        showDivider = false,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 28.dp),
                ) {
                    OpenCodeButton(
                        variant = OpenCodeButtonVariant.FullWidthText("Open server docs"),
                        onClick = {
                            ProductAnalytics.logUserAction(
                                screenId = ProductAnalytics.Screen.APP_ONBOARDING_SETUP_GUIDE,
                                actionId = ProductAnalytics.Action.SETUP_GUIDE_OPEN_DOCS,
                            )
                            val intent =
                                Intent(Intent.ACTION_VIEW, Uri.parse(AppOnboardingCopy.SERVER_DOCS_URL))
                            context.startActivity(intent)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SetupStep(
    number: String,
    title: String,
    body: String,
    code: String? = null,
    showDivider: Boolean = true,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
    ) {
        Text(
            text = number.uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        )
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (code != null) {
            Spacer(modifier = Modifier.height(8.dp))
            OpenCodeCodeSnippet(code = code)
        }
        if (showDivider) {
            Spacer(modifier = Modifier.height(14.dp))
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}
