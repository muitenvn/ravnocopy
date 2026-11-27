package com.example.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.R
import com.example.data.GameOptimizationProfile
import com.example.data.GameOptimizationRepository
import com.example.ui.theme.DirectCyan
import com.example.ui.theme.SpaceGroteskFontFamily
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningAmber
import com.example.ui.theme.XboxCardBorder
import com.example.ui.theme.XboxDark
import com.example.ui.theme.XboxNeonGreen
import com.example.ui.theme.XboxSurface
import com.example.ui.theme.XboxSurfaceElevated
import com.example.ui.theme.XboxSurfaceVariant
import com.example.ui.theme.rememberXboxMotionEnabled

private val ProgressSmoothEasing = CubicBezierEasing(0.3f, 0.6f, 0.3f, 1f)

@Composable
fun InGameLoadingOverlay(
    isLoading: Boolean,
    progress: Float,
    targetServerName: String = "Korea",
    @Suppress("UNUSED_PARAMETER") hasGamepad: Boolean = false,
    gameProfile: GameOptimizationProfile = GameOptimizationRepository.getProfileForUrl(""),
    gameTitleOverride: String? = null,
    @Suppress("UNUSED_PARAMETER") gameSubtitleOverride: String? = null,
    gameImageUrl: String? = null,
    @Suppress("UNUSED_PARAMETER") supportsNativeTouch: Boolean? = null,
    targetResolutionTag: String = "1080p",
    targetFps: Int = 60,
    realLatencyMs: Int? = null,
    lowEndDeviceMode: Boolean = false,
    onNavigateHome: () -> Unit = {},
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val motionEnabled = rememberXboxMotionEnabled(lowEndDeviceMode = lowEndDeviceMode)

    AnimatedVisibility(
        visible = isLoading,
        enter = fadeIn(tween(if (motionEnabled) 180 else 0)),
        exit = fadeOut(tween(if (motionEnabled) 200 else 0)),
        modifier = modifier
    ) {
        val hubTitle = stringResource(R.string.loading_hub_title)

        val resolvedTitle = remember(gameTitleOverride, gameProfile.gameId, gameProfile.title, hubTitle) {
            gameTitleOverride?.takeIf { it.isNotBlank() }
                ?: gameProfile.title.takeIf { gameProfile.gameId != "generic" && it.isNotBlank() }
                ?: hubTitle
        }
        val resolvedArtworkUrl = remember(gameImageUrl) {
            gameImageUrl?.trim()?.takeIf { it.startsWith("http", ignoreCase = true) }
        }

        val clampedProgress = progress.coerceIn(0.06f, 1f)
        val isNearlyReady = clampedProgress >= 0.85f
        val currentStep = when {
            clampedProgress < 0.28f -> 1
            clampedProgress < 0.58f -> 2
            clampedProgress < 0.85f -> 3
            else -> 4
        }

        val phaseStatusText = when (currentStep) {
            1 -> stringResource(R.string.loading_phase_connecting)
            2 -> stringResource(R.string.loading_phase_routing, targetServerName)
            3 -> stringResource(R.string.loading_phase_syncing, targetResolutionTag, targetFps)
            else -> stringResource(R.string.loading_phase_ready)
        }

        val highPingWarning = if (realLatencyMs != null && realLatencyMs >= 130) {
            "Ping ${realLatencyMs} ms · ${stringResource(R.string.loading_ping_high)}"
        } else {
            null
        }

        val skipFocusRequester = remember { FocusRequester() }
        val backFocusRequester = remember { FocusRequester() }

        GamepadSurfaceController(
            enabled = isLoading,
            defaultFocusRequester = skipFocusRequester,
            autoRequestDefaultFocus = true,
            onBackPress = onNavigateHome
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(XboxDark)
                    .testTag("ingame_loading_overlay")
            ) {
                val density = LocalDensity.current
                val isTvUiMode = GamepadUiNavigation.keepFocusVisibleInTvMode
                val isLandscapeOrWide = isTvUiMode || (maxWidth > maxHeight)
                val scope = remember(maxWidth, maxHeight, isTvUiMode, isLandscapeOrWide, density) {
                    AdaptivePopupScope(
                        winW = maxWidth,
                        winH = maxHeight,
                        isTvUiMode = isTvUiMode,
                        isGamepadDetected = GamepadUiNavigation.isGamepadModeActive,
                        useTwoColumnLayout = isLandscapeOrWide,
                        density = density
                    )
                }

                // Full-bleed cover artwork with slow GPU-only drift (or tonal gradient fallback)
                DriftingBackdropArt(
                    artworkUrl = resolvedArtworkUrl,
                    title = resolvedTitle,
                    motionEnabled = motionEnabled,
                    modifier = Modifier.fillMaxSize()
                )

                // Strong vertical readability scrim so text stays legible on bright/busy artwork
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0.00f to XboxDark.copy(alpha = 0.44f),
                                0.28f to XboxDark.copy(alpha = 0.08f),
                                0.54f to XboxDark.copy(alpha = 0.58f),
                                0.78f to XboxDark.copy(alpha = 0.88f),
                                1.00f to XboxDark.copy(alpha = 0.96f)
                            )
                        )
                )

                // Safe-area interactive overlay content
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                ) {
                    // Top-left icon-only back button
                    BackIconButton(
                        scope = scope,
                        focusRequester = backFocusRequester,
                        nextFocusRequester = skipFocusRequester,
                        onClick = onNavigateHome,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(
                                start = scope.rDp(if (isLandscapeOrWide) 20f else 16f),
                                top = scope.rDp(if (isLandscapeOrWide) 16f else 14f)
                            )
                    )

                    // Bottom hero block (landscape: left title+status / right button; portrait: stacked full-width)
                    val bottomPadding = scope.rDp(if (isLandscapeOrWide) 26f else 30f)
                    val sidePadding = scope.rDp(if (isLandscapeOrWide) 28f else 22f)

                    if (isLandscapeOrWide) {
                        Row(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(
                                    start = sidePadding,
                                    end = sidePadding,
                                    bottom = bottomPadding
                                ),
                            horizontalArrangement = Arrangement.spacedBy(scope.rDp(20f)),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            TitleAndStatusBlock(
                                scope = scope,
                                title = resolvedTitle,
                                statusText = phaseStatusText,
                                isReady = isNearlyReady,
                                highPingWarning = highPingWarning,
                                motionEnabled = motionEnabled,
                                isLandscape = true,
                                modifier = Modifier.weight(1f)
                            )
                            LaunchPrimaryButton(
                                scope = scope,
                                isReady = isNearlyReady,
                                motionEnabled = motionEnabled,
                                focusRequester = skipFocusRequester,
                                backFocusRequester = backFocusRequester,
                                fullWidth = false,
                                onClick = onSkip
                            )
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .fillMaxWidth()
                                .padding(
                                    start = sidePadding,
                                    end = sidePadding,
                                    bottom = bottomPadding
                                ),
                            verticalArrangement = Arrangement.spacedBy(scope.rDp(20f))
                        ) {
                            TitleAndStatusBlock(
                                scope = scope,
                                title = resolvedTitle,
                                statusText = phaseStatusText,
                                isReady = isNearlyReady,
                                highPingWarning = highPingWarning,
                                motionEnabled = motionEnabled,
                                isLandscape = false,
                                modifier = Modifier.fillMaxWidth()
                            )
                            LaunchPrimaryButton(
                                scope = scope,
                                isReady = isNearlyReady,
                                motionEnabled = motionEnabled,
                                focusRequester = skipFocusRequester,
                                backFocusRequester = backFocusRequester,
                                fullWidth = true,
                                onClick = onSkip
                            )
                        }
                    }
                }

                // Thin bottom-edge progress line (flush to screen bottom, GPU scaleX)
                BottomProgressLine(
                    progress = clampedProgress,
                    motionEnabled = motionEnabled,
                    trackHeight = scope.rDp(3.5f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun DriftingBackdropArt(
    artworkUrl: String?,
    title: String,
    motionEnabled: Boolean,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        XboxSurfaceElevated,
                        XboxSurfaceVariant,
                        XboxDark
                    )
                )
            )
    ) {
        // Subtle flat tonal cyan/green corner wash for no-image fallback (no blur, no glow)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.0f to DirectCyan.copy(alpha = 0.14f),
                        0.45f to XboxNeonGreen.copy(alpha = 0.08f),
                        1.0f to Color.Transparent
                    )
                )
        )

        if (artworkUrl != null) {
            val driftModifier = if (motionEnabled) {
                val infiniteTransition = rememberInfiniteTransition(label = "backdrop_drift")
                val driftState = infiniteTransition.animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 12_000, easing = EaseInOutSine),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "backdrop_drift_progress"
                )
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val t = driftState.value
                        val scale = 1f + 0.06f * t
                        scaleX = scale
                        scaleY = scale
                        translationX = -size.width * 0.012f * t
                        translationY = -size.height * 0.01f * t
                    }
            } else {
                Modifier.fillMaxSize()
            }

            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(artworkUrl)
                    .crossfade(if (motionEnabled) 220 else 0)
                    .build(),
                contentDescription = stringResource(R.string.loading_artwork_cd, title),
                contentScale = ContentScale.Crop,
                modifier = driftModifier
            )
        }
    }
}

@Composable
private fun BackIconButton(
    scope: AdaptivePopupScope,
    focusRequester: FocusRequester,
    nextFocusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isFocused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(scope.rDp(14f))

    Surface(
        onClick = onClick,
        shape = shape,
        color = if (isFocused) XboxSurfaceElevated else XboxSurface.copy(alpha = 0.72f),
        border = if (isFocused) {
            BorderStroke(scope.borderFocus, XboxNeonGreen)
        } else {
            BorderStroke(scope.borderThin, XboxCardBorder.copy(alpha = 0.6f))
        },
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(scope.rDp(48f))
            .focusRequester(focusRequester)
            .focusProperties {
                down = nextFocusRequester
                right = nextFocusRequester
            }
            .onFocusChanged { isFocused = it.isFocused }
            .testTag("loading_home_button")
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.loading_home_btn),
                tint = TextPrimary,
                modifier = Modifier.size(scope.rDp(22f))
            )
        }
    }
}

@Composable
private fun TitleAndStatusBlock(
    scope: AdaptivePopupScope,
    title: String,
    statusText: String,
    isReady: Boolean,
    highPingWarning: String?,
    motionEnabled: Boolean,
    isLandscape: Boolean,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val slideOffsetPx = remember(density, scope) {
        with(density) { scope.rDp(6f).roundToPx() }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(scope.rDp(8f))
    ) {
        Text(
            text = title,
            color = TextPrimary,
            fontFamily = SpaceGroteskFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = scope.rSp(if (isLandscape) 28f else 31f),
            lineHeight = scope.rSp(if (isLandscape) 32f else 35f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(scope.rDp(8f)),
            modifier = Modifier
                .fillMaxWidth()
                .semantics { liveRegion = LiveRegionMode.Polite }
        ) {
            StatusIndicatorDot(
                isReady = isReady,
                motionEnabled = motionEnabled,
                dotSize = scope.rDp(7.5f)
            )
            AnimatedContent(
                targetState = statusText,
                transitionSpec = {
                    if (motionEnabled) {
                        (slideInVertically(
                            animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
                            initialOffsetY = { slideOffsetPx }
                        ) + fadeIn(animationSpec = tween(durationMillis = 220))) togetherWith
                            (slideOutVertically(
                                animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                                targetOffsetY = { -slideOffsetPx }
                            ) + fadeOut(animationSpec = tween(durationMillis = 160)))
                    } else {
                        EnterTransition.None togetherWith ExitTransition.None
                    }.using(SizeTransform(clip = false))
                },
                label = "loading_phase_status",
                modifier = Modifier.weight(1f)
            ) { currentText ->
                Text(
                    text = currentText,
                    color = if (isReady) TextPrimary else TextSecondary,
                    fontFamily = SpaceGroteskFontFamily,
                    fontWeight = if (isReady) FontWeight.Medium else FontWeight.Normal,
                    fontSize = scope.rSp(14.5f),
                    lineHeight = scope.rSp(19f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (highPingWarning != null) {
            Text(
                text = highPingWarning,
                color = WarningAmber,
                fontFamily = SpaceGroteskFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = scope.rSp(12.5f),
                lineHeight = scope.rSp(16f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun StatusIndicatorDot(
    isReady: Boolean,
    motionEnabled: Boolean,
    dotSize: Dp
) {
    val dotColor by animateColorAsState(
        targetValue = if (isReady) XboxNeonGreen else DirectCyan,
        animationSpec = if (motionEnabled) tween(260) else snap(),
        label = "status_dot_color"
    )

    val pulseModifier = if (!isReady && motionEnabled) {
        val infiniteTransition = rememberInfiniteTransition(label = "status_dot_pulse")
        val alphaState = infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 1_200, easing = EaseInOutSine),
                repeatMode = RepeatMode.Reverse
            ),
            label = "status_dot_alpha"
        )
        Modifier.graphicsLayer { alpha = alphaState.value }
    } else {
        Modifier
    }

    Box(
        modifier = Modifier
            .size(dotSize)
            .then(pulseModifier)
            .clip(CircleShape)
            .background(dotColor)
    )
}

@Composable
private fun LaunchPrimaryButton(
    scope: AdaptivePopupScope,
    isReady: Boolean,
    motionEnabled: Boolean,
    focusRequester: FocusRequester,
    backFocusRequester: FocusRequester,
    fullWidth: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    val containerColor by animateColorAsState(
        targetValue = if (isReady) XboxNeonGreen else XboxSurfaceVariant.copy(alpha = 0.88f),
        animationSpec = if (motionEnabled) {
            tween(durationMillis = 320, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "cta_container_color"
    )
    val contentColor by animateColorAsState(
        targetValue = if (isReady) XboxDark else TextSecondary,
        animationSpec = if (motionEnabled) {
            tween(durationMillis = 320, easing = FastOutSlowInEasing)
        } else {
            snap()
        },
        label = "cta_content_color"
    )

    val readyScale = remember { Animatable(1f) }
    LaunchedEffect(isReady, motionEnabled) {
        if (isReady && motionEnabled) {
            readyScale.snapTo(1f)
            readyScale.animateTo(
                targetValue = 1.03f,
                animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing)
            )
            readyScale.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = 180, easing = FastOutSlowInEasing)
            )
        } else {
            readyScale.snapTo(1f)
        }
    }

    val borderStroke = when {
        isFocused && isReady -> BorderStroke(scope.borderFocus, TextPrimary)
        isFocused -> BorderStroke(scope.borderFocus, XboxNeonGreen)
        !isReady -> BorderStroke(scope.borderThin, XboxCardBorder)
        else -> null
    }

    val buttonModifier = Modifier
        .then(
            if (fullWidth) {
                Modifier.fillMaxWidth()
            } else {
                Modifier.widthIn(min = scope.rDp(156f))
            }
        )
        .minimumInteractiveComponentSize()
        .defaultMinSize(minHeight = scope.rDp(52f))
        .graphicsLayer {
            val s = readyScale.value
            scaleX = s
            scaleY = s
        }
        .focusRequester(focusRequester)
        .focusProperties {
            up = backFocusRequester
            left = backFocusRequester
        }
        .onFocusChanged { isFocused = it.isFocused }
        .testTag("loading_skip_button")

    Button(
        onClick = onClick,
        shape = RoundedCornerShape(scope.rDp(16f)),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor,
            contentColor = contentColor
        ),
        border = borderStroke,
        contentPadding = PaddingValues(
            horizontal = scope.rDp(28f),
            vertical = scope.rDp(14f)
        ),
        modifier = buttonModifier
    ) {
        Text(
            text = stringResource(if (isReady) R.string.loading_enter_now else R.string.loading_skip_wait),
            fontFamily = SpaceGroteskFontFamily,
            fontWeight = FontWeight.Bold,
            fontSize = scope.rSp(15.5f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun BottomProgressLine(
    progress: Float,
    motionEnabled: Boolean,
    trackHeight: Dp,
    modifier: Modifier = Modifier
) {
    val animatedProgressState = animateFloatAsState(
        targetValue = progress.coerceIn(0.04f, 1f),
        animationSpec = if (motionEnabled) {
            tween(durationMillis = 420, easing = ProgressSmoothEasing)
        } else {
            snap()
        },
        label = "bottom_progress_bar"
    )

    Box(
        modifier = modifier
            .height(trackHeight)
            .background(TextPrimary.copy(alpha = 0.12f))
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = animatedProgressState.value
                    transformOrigin = TransformOrigin(0f, 0.5f)
                }
                .background(XboxNeonGreen)
        )
    }
}
