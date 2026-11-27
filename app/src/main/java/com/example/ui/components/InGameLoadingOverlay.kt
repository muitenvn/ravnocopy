package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.GameOptimizationProfile
import com.example.data.GameOptimizationRepository
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import kotlinx.coroutines.delay

// Ravno Tonal Flat Palette
private val LaunchBg = Color(0xFF070B09)
private val LaunchSurface = Color(0xFF0D1511)
private val LaunchSurfaceElevated = Color(0xFF142019)
private val LaunchSelected = Color(0xFF16352A)
private val LaunchBorder = Color(0xFF1F3128)
private val LaunchAccent = Color(0xFF27D884)
private val LaunchCyan = Color(0xFF3FD8EE)
private val LaunchWarn = Color(0xFFE3B341)
private val LaunchDanger = Color(0xFFF0706B)

@Composable
fun InGameLoadingOverlay(
    isLoading: Boolean,
    progress: Float,
    targetServerName: String = "Korea",
    hasGamepad: Boolean = false,
    gameProfile: GameOptimizationProfile = GameOptimizationRepository.getProfileForUrl(""),
    gameTitleOverride: String? = null,
    gameSubtitleOverride: String? = null,
    gameImageUrl: String? = null,
    supportsNativeTouch: Boolean? = null,
    targetResolutionTag: String = "1080p",
    targetFps: Int = 60,
    realLatencyMs: Int? = null,
    lowEndDeviceMode: Boolean = false,
    onNavigateHome: () -> Unit = {},
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = isLoading,
        enter = fadeIn(tween(if (lowEndDeviceMode) 0 else 180)),
        exit = fadeOut(tween(if (lowEndDeviceMode) 0 else 200)),
        modifier = modifier
    ) {
        val hubTitle = stringResource(R.string.loading_hub_title)
        val hubSubtitle = stringResource(R.string.loading_hub_subtitle)

        val resolvedTitle = remember(gameTitleOverride, gameProfile.gameId, gameProfile.title, hubTitle) {
            gameTitleOverride?.takeIf { it.isNotBlank() }
                ?: gameProfile.title.takeIf { gameProfile.gameId != "generic" && it.isNotBlank() }
                ?: hubTitle
        }
        val resolvedSubtitle = remember(gameSubtitleOverride, hubSubtitle) {
            gameSubtitleOverride?.takeIf { it.isNotBlank() } ?: hubSubtitle
        }
        val resolvedArtworkUrl = remember(gameImageUrl) {
            gameImageUrl?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
        }
        val nativeTouch = supportsNativeTouch ?: gameProfile.nativeTouchSupport

        val tips = listOf(
            stringResource(R.string.loading_tip_1),
            stringResource(R.string.loading_tip_2),
            stringResource(R.string.loading_tip_3)
        )
        var tipIndex by remember { mutableIntStateOf(0) }
        LaunchedEffect(lowEndDeviceMode) {
            if (!lowEndDeviceMode) {
                while (true) {
                    delay(5000)
                    tipIndex = (tipIndex + 1) % tips.size
                }
            }
        }

        val hasValidPing = realLatencyMs != null && realLatencyMs > 0
        val pingColor = when {
            !hasValidPing -> TextSecondary
            realLatencyMs < 80 -> LaunchAccent
            realLatencyMs < 130 -> LaunchWarn
            else -> LaunchDanger
        }
        val pingRating = when {
            !hasValidPing -> stringResource(R.string.loading_ping_measuring)
            realLatencyMs < 80 -> stringResource(R.string.loading_ping_optimal)
            realLatencyMs < 130 -> stringResource(R.string.loading_ping_stable)
            else -> stringResource(R.string.loading_ping_high)
        }
        val pingText = if (hasValidPing) "${realLatencyMs} ms" else "-- ms"

        val clampedProgress = progress.coerceIn(0.06f, 1f)
        val animatedProgress by animateFloatAsState(
            targetValue = clampedProgress,
            animationSpec = tween(durationMillis = if (lowEndDeviceMode) 0 else 240, easing = FastOutSlowInEasing),
            label = "launch_progress"
        )
        val percent = (animatedProgress * 100f).toInt().coerceIn(6, 100)
        val isNearlyReady = animatedProgress >= 0.85f

        val currentStep = when {
            animatedProgress < 0.28f -> 1
            animatedProgress < 0.58f -> 2
            animatedProgress < 0.85f -> 3
            else -> 4
        }

        val phaseStatusText = when (currentStep) {
            1 -> stringResource(R.string.loading_phase_connecting)
            2 -> stringResource(R.string.loading_phase_routing, targetServerName)
            3 -> stringResource(R.string.loading_phase_syncing, targetResolutionTag, targetFps)
            else -> stringResource(R.string.loading_phase_ready)
        }

        val controllerStatus = when {
            hasGamepad -> stringResource(R.string.loading_ctrl_physical)
            nativeTouch -> stringResource(R.string.loading_ctrl_touch_virtual)
            else -> stringResource(R.string.loading_ctrl_virtual)
        }

        val streamBadgeText = "$targetResolutionTag · ${targetFps} FPS"
        val skipFocusRequester = remember { FocusRequester() }

        GamepadSurfaceController(
            enabled = isLoading,
            defaultFocusRequester = skipFocusRequester,
            autoRequestDefaultFocus = true,
            onBackPress = onNavigateHome
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LaunchBg)
                    .testTag("ingame_loading_overlay")
            ) {
                // Very subtle atmospheric backdrop ONLY when real game art exists (flat tonal veil, no blur/glow)
                if (resolvedArtworkUrl != null && !lowEndDeviceMode) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current)
                            .data(resolvedArtworkUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .alpha(0.11f)
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(LaunchBg.copy(alpha = 0.82f))
                    )
                }

                val isWideLayout = maxWidth > maxHeight || maxWidth >= 600.dp

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(
                            horizontal = if (isWideLayout) 28.dp else 18.dp,
                            vertical = if (isWideLayout) 16.dp else 14.dp
                        )
                ) {
                    LaunchTopBar(
                        pingText = pingText,
                        pingRating = pingRating,
                        pingColor = pingColor,
                        showRatingInline = isWideLayout,
                        onNavigateHome = onNavigateHome
                    )

                    Spacer(modifier = Modifier.height(if (isWideLayout) 16.dp else 18.dp))

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (isWideLayout) {
                            LandscapeLaunchBody(
                                title = resolvedTitle,
                                subtitle = resolvedSubtitle,
                                artworkUrl = resolvedArtworkUrl,
                                streamBadgeText = streamBadgeText,
                                animatedProgress = animatedProgress,
                                percent = percent,
                                currentStep = currentStep,
                                phaseStatusText = phaseStatusText,
                                serverName = targetServerName,
                                controllerStatus = controllerStatus,
                                hasGamepad = hasGamepad,
                                tipText = tips[tipIndex % tips.size],
                                isReady = isNearlyReady,
                                skipFocusRequester = skipFocusRequester,
                                onSkip = onSkip,
                                modifier = Modifier
                                    .widthIn(max = 1040.dp)
                                    .fillMaxSize()
                            )
                        } else {
                            PortraitLaunchBody(
                                title = resolvedTitle,
                                subtitle = resolvedSubtitle,
                                artworkUrl = resolvedArtworkUrl,
                                streamBadgeText = streamBadgeText,
                                animatedProgress = animatedProgress,
                                percent = percent,
                                currentStep = currentStep,
                                phaseStatusText = phaseStatusText,
                                serverName = targetServerName,
                                controllerStatus = controllerStatus,
                                hasGamepad = hasGamepad,
                                tipText = tips[tipIndex % tips.size],
                                isReady = isNearlyReady,
                                skipFocusRequester = skipFocusRequester,
                                onSkip = onSkip,
                                modifier = Modifier
                                    .widthIn(max = 520.dp)
                                    .fillMaxSize()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LaunchTopBar(
    pingText: String,
    pingRating: String,
    pingColor: Color,
    showRatingInline: Boolean,
    onNavigateHome: () -> Unit
) {
    var homeFocused by remember { mutableStateOf(false) }
    val homeLabel = stringResource(R.string.loading_home_btn)

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Surface(
            onClick = onNavigateHome,
            shape = RoundedCornerShape(12.dp),
            color = if (homeFocused) LaunchSelected else LaunchSurface,
            border = BorderStroke(if (homeFocused) 2.dp else 1.dp, if (homeFocused) LaunchAccent else LaunchBorder),
            modifier = Modifier
                .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
                .onFocusChanged { homeFocused = it.isFocused }
                .testTag("loading_home_button")
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = homeLabel,
                    tint = if (homeFocused) LaunchAccent else TextPrimary,
                    modifier = Modifier.size(18.dp)
                )
                Text(
                    text = homeLabel,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        // Single-line telemetry pill — never wraps vertically on narrow portrait phones
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = LaunchSurface,
            border = BorderStroke(1.dp, LaunchBorder)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(pingColor)
                )
                Text(
                    text = pingText,
                    color = TextPrimary,
                    fontFamily = JetBrainsMonoFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false
                )
                if (showRatingInline) {
                    Text(
                        text = pingRating,
                        color = pingColor,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }
        }
    }
}

@Composable
private fun PortraitLaunchBody(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    streamBadgeText: String,
    animatedProgress: Float,
    percent: Int,
    currentStep: Int,
    phaseStatusText: String,
    serverName: String,
    controllerStatus: String,
    hasGamepad: Boolean,
    tipText: String,
    isReady: Boolean,
    skipFocusRequester: FocusRequester,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            GameArtworkStage(
                title = title,
                subtitle = null,
                artworkUrl = artworkUrl,
                streamBadgeText = streamBadgeText,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
            )

            // Clean editorial game title block below cover art
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 23.sp,
                    lineHeight = 28.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            LaunchProgressCard(
                animatedProgress = animatedProgress,
                percent = percent,
                currentStep = currentStep,
                phaseStatusText = phaseStatusText
            )

            LaunchMetaRow(
                serverName = serverName,
                controllerStatus = controllerStatus,
                hasGamepad = hasGamepad
            )

            LaunchTipStrip(tipText = tipText)
        }

        Spacer(modifier = Modifier.height(14.dp))

        LaunchPrimaryButton(
            isReady = isReady,
            focusRequester = skipFocusRequester,
            onClick = onSkip,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun LandscapeLaunchBody(
    title: String,
    subtitle: String,
    artworkUrl: String?,
    streamBadgeText: String,
    animatedProgress: Float,
    percent: Int,
    currentStep: Int,
    phaseStatusText: String,
    serverName: String,
    controllerStatus: String,
    hasGamepad: Boolean,
    tipText: String,
    isReady: Boolean,
    skipFocusRequester: FocusRequester,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left column: Cinematic game cover artwork stage with title & subtitle cleanly anchored at bottom
        GameArtworkStage(
            title = title,
            subtitle = subtitle,
            artworkUrl = artworkUrl,
            streamBadgeText = streamBadgeText,
            modifier = Modifier
                .weight(0.46f)
                .fillMaxHeight()
        )

        // Right column: Crisp status, steps, telemetry, tip, and primary CTA
        Column(
            modifier = Modifier
                .weight(0.54f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LaunchProgressCard(
                    animatedProgress = animatedProgress,
                    percent = percent,
                    currentStep = currentStep,
                    phaseStatusText = phaseStatusText
                )

                LaunchMetaRow(
                    serverName = serverName,
                    controllerStatus = controllerStatus,
                    hasGamepad = hasGamepad
                )

                LaunchTipStrip(tipText = tipText)
            }

            Spacer(modifier = Modifier.height(12.dp))

            LaunchPrimaryButton(
                isReady = isReady,
                focusRequester = skipFocusRequester,
                onClick = onSkip,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun GameArtworkStage(
    title: String,
    subtitle: String?,
    artworkUrl: String?,
    streamBadgeText: String,
    modifier: Modifier = Modifier
) {
    var artLoaded by remember(artworkUrl) { mutableStateOf(false) }
    var artFailed by remember(artworkUrl) { mutableStateOf(false) }
    val artworkCd = stringResource(R.string.loading_artwork_cd, title)
    val monogram = remember(title) {
        val parts = title.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        when {
            parts.size >= 2 -> "${parts[0].first().uppercaseChar()}${parts[1].first().uppercaseChar()}"
            parts.size == 1 && parts[0].length >= 2 -> parts[0].take(2).uppercase()
            parts.size == 1 -> parts[0].uppercase()
            else -> "RV"
        }
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(LaunchSurface)
            .border(1.dp, LaunchBorder, RoundedCornerShape(20.dp))
    ) {
        // Crisp tonal-flat fallback emblem when on Xbox Cloud Hub or while real art loads
        if (artworkUrl == null || !artLoaded || artFailed) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LaunchSurface),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(LaunchSelected)
                            .border(1.dp, LaunchAccent.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (artworkUrl == null) {
                            Icon(
                                imageVector = Icons.Default.SportsEsports,
                                contentDescription = null,
                                tint = LaunchAccent,
                                modifier = Modifier.size(32.dp)
                            )
                        } else {
                            Text(
                                text = monogram,
                                color = LaunchAccent,
                                fontFamily = SpaceGroteskFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp
                            )
                        }
                    }
                }
            }
        }

        // Real official Xbox / Microsoft Store game cover artwork
        if (artworkUrl != null && !artFailed) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = artworkCd,
                contentScale = ContentScale.Crop,
                onSuccess = { artLoaded = true },
                onError = { artFailed = true },
                modifier = Modifier.fillMaxSize()
            )
        }

        // Clean bottom readability scrim (only when showing overlay title in landscape or bottom badge)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colorStops = if (subtitle != null) {
                            arrayOf(
                                0.0f to Color.Transparent,
                                0.48f to Color(0x33070B09),
                                1.0f to Color(0xEC070B09)
                            )
                        } else {
                            arrayOf(
                                0.0f to Color.Transparent,
                                0.65f to Color.Transparent,
                                1.0f to Color(0xCC070B09)
                            )
                        }
                    )
                )
        )

        // Single clean bottom-left badge + optional landscape title/subtitle
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = LaunchBg.copy(alpha = 0.86f),
                border = BorderStroke(1.dp, LaunchBorder)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(LaunchAccent)
                    )
                    Text(
                        text = streamBadgeText,
                        color = TextPrimary,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false
                    )
                }
            }

            if (subtitle != null) {
                Text(
                    text = title,
                    color = TextPrimary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                    lineHeight = 24.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    color = TextSecondary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun LaunchProgressCard(
    animatedProgress: Float,
    percent: Int,
    currentStep: Int,
    phaseStatusText: String
) {
    val steps = listOf(
        stringResource(R.string.loading_step_connect),
        stringResource(R.string.loading_step_region),
        stringResource(R.string.loading_step_stream),
        stringResource(R.string.loading_step_ready)
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LaunchSurface)
            .border(1.dp, LaunchBorder, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = phaseStatusText,
                color = TextPrimary,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "$percent%",
                color = LaunchAccent,
                fontFamily = JetBrainsMonoFontFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1,
                softWrap = false
            )
        }

        // Crisp flat progress rail
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(LaunchSurfaceElevated)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(animatedProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(3.dp))
                    .background(LaunchAccent)
            )
        }

        // 4 balanced non-truncating step pills
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            steps.forEachIndexed { index, label ->
                val stepNum = index + 1
                val isDone = stepNum < currentStep || percent >= 98
                val isActive = stepNum == currentStep && !isDone
                val bgColor = when {
                    isActive -> LaunchSelected
                    isDone -> LaunchSurfaceElevated
                    else -> LaunchBg
                }
                val borderColor = when {
                    isActive -> LaunchAccent
                    isDone -> LaunchAccent.copy(alpha = 0.35f)
                    else -> LaunchBorder
                }
                val textColor = when {
                    isActive -> LaunchAccent
                    isDone -> TextPrimary
                    else -> TextSecondary
                }

                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bgColor)
                        .border(1.dp, borderColor, RoundedCornerShape(10.dp))
                        .padding(horizontal = 6.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = LaunchAccent,
                            modifier = Modifier.size(12.dp)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                    }
                    Text(
                        text = label,
                        color = textColor,
                        fontFamily = SpaceGroteskFontFamily,
                        fontWeight = if (isActive || isDone) FontWeight.SemiBold else FontWeight.Medium,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun LaunchMetaRow(
    serverName: String,
    controllerStatus: String,
    hasGamepad: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        LaunchMetaTile(
            icon = Icons.Default.Public,
            iconTint = LaunchCyan,
            label = stringResource(R.string.loading_meta_server),
            value = serverName,
            modifier = Modifier.weight(1f)
        )
        LaunchMetaTile(
            icon = Icons.Default.Gamepad,
            iconTint = if (hasGamepad) LaunchAccent else LaunchCyan,
            label = stringResource(R.string.loading_meta_controls),
            value = controllerStatus,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun LaunchMetaTile(
    icon: ImageVector,
    iconTint: Color,
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(LaunchSurface)
            .border(1.dp, LaunchBorder, RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(LaunchSurfaceElevated),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(17.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                color = TextSecondary,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = value,
                color = TextPrimary,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LaunchTipStrip(tipText: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LaunchSurface)
            .border(1.dp, LaunchBorder, RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Lightbulb,
            contentDescription = null,
            tint = LaunchWarn,
            modifier = Modifier.size(16.dp)
        )
        AnimatedContent(
            targetState = tipText,
            transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(180)) },
            label = "launch_tip",
            modifier = Modifier.weight(1f)
        ) { text ->
            Text(
                text = text,
                color = TextSecondary,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun LaunchPrimaryButton(
    isReady: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val buttonLabel = if (isReady) {
        stringResource(R.string.loading_enter_now)
    } else {
        stringResource(R.string.loading_skip_wait)
    }

    val containerColor = when {
        isReady -> LaunchAccent
        isFocused -> LaunchSelected
        else -> LaunchSurfaceElevated
    }
    val contentColor = if (isReady) LaunchBg else LaunchAccent
    val borderStroke = when {
        isFocused -> BorderStroke(2.dp, Color.White)
        isReady -> null
        else -> BorderStroke(1.dp, LaunchAccent.copy(alpha = 0.55f))
    }

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = borderStroke,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
        modifier = modifier
            .defaultMinSize(minHeight = 52.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .testTag("loading_skip_button")
    ) {
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = buttonLabel,
            fontFamily = SpaceGroteskFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp,
            maxLines = 1,
            softWrap = false
        )
    }
}
