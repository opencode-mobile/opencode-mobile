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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import dev.opencode.mobile.ProductAnalytics
import dev.opencode.mobile.R
import dev.opencode.mobile.ProductRouteScreenViewEffect
import dev.opencode.mobile.ui.components.OpenCodeButton
import dev.opencode.mobile.ui.components.OpenCodeButtonVariant
import dev.opencode.mobile.ui.components.OpenCodeTag
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
import java.util.Locale

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
                            text = stringResource(R.string.onboarding_skip),
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
                                stringResource(if (lastPage) R.string.onboarding_connect else R.string.onboarding_next),
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
            OpenCodeTag(title = stringResource(badge))
            Spacer(modifier = Modifier.height(10.dp))
        }
        Text(
            text = stringResource(page.title),
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
        )
        Spacer(modifier = Modifier.height(12.dp))
        if (page.features.isNotEmpty()) {
            AppOnboardingFeatureList(features = page.features)
        } else {
            page.body?.let { body ->
                Text(
                    text = stringResource(body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            page.setupLink?.let { link ->
                Spacer(modifier = Modifier.height(8.dp))
                OpenCodeButton(
                    variant = OpenCodeButtonVariant.Link(stringResource(link)),
                    onClick = onNeedServerFirst,
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
            title = stringResource(row.title),
            subtitle = stringResource(row.detail),
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
                        text = stringResource(R.string.onboarding_setup_title),
                        style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.onboarding_setup_intro),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    SetupStep(
                        number = 1,
                        title = stringResource(R.string.onboarding_setup_step1_title),
                        body = stringResource(R.string.onboarding_setup_step1_body),
                        code = "opencode serve",
                    )
                    SetupStep(
                        number = 2,
                        title = stringResource(R.string.onboarding_setup_step2_title),
                        body = stringResource(R.string.onboarding_setup_step2_body),
                    )
                    SetupStep(
                        number = 3,
                        title = stringResource(R.string.onboarding_setup_step3_title),
                        body = stringResource(R.string.onboarding_setup_step3_body),
                    )
                    SetupStep(
                        number = 4,
                        title = stringResource(R.string.onboarding_setup_step4_title),
                        body = stringResource(R.string.onboarding_setup_step4_body),
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
                        variant = OpenCodeButtonVariant.FullWidthText(stringResource(R.string.onboarding_setup_open_docs)),
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
    number: Int,
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
            text = stringResource(R.string.onboarding_setup_step_number, number).uppercase(Locale.getDefault()),
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
