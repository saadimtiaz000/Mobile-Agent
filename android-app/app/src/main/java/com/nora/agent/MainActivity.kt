package com.nora.agent

import android.Manifest
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nora.agent.service.NoraForegroundService
import kotlin.math.sin

class MainActivity : ComponentActivity() {
    private var authResultHandler: ((Boolean) -> Unit)? = null

    private val requiredPermissions = buildList {
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            if (result.values.all { it }) {
                startNoraService()
            }
        }

    private val credentialLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            authResultHandler?.invoke(result.resultCode == RESULT_OK)
            authResultHandler = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureSystemBars()
        requestPermissionsIfNeeded()
        requestUnlockIfNeeded()

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF05070F),
                ) {
                    NoraScreen(
                        onStartService = ::startNoraService,
                        onAuthenticate = ::authenticateForProtectedSession,
                    )
                }
            }
        }
    }

    private fun configureSystemBars() {
        window.statusBarColor = AndroidColor.TRANSPARENT
        window.navigationBarColor = AndroidColor.rgb(5, 7, 15)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    private fun requestPermissionsIfNeeded() {
        val missing = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    private fun startNoraService() {
        val intent = Intent(this, NoraForegroundService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun requestUnlockIfNeeded() {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && keyguardManager.isKeyguardLocked) {
            keyguardManager.requestDismissKeyguard(this, null)
        }
    }

    private fun authenticateForProtectedSession(onResult: (Boolean) -> Unit) {
        val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
        if (!keyguardManager.isDeviceSecure) {
            onResult(true)
            return
        }

        val intent = keyguardManager.createConfirmDeviceCredentialIntent(
            "Unlock Nora",
            "Confirm it is you before starting the voice session",
        )
        if (intent == null) {
            onResult(true)
        } else {
            authResultHandler = onResult
            credentialLauncher.launch(intent)
        }
    }
}

@Composable
private fun NoraScreen(
    onStartService: () -> Unit,
    onAuthenticate: ((Boolean) -> Unit) -> Unit,
    viewModel: NoraAgentViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF05070F),
                        Color(0xFF0A1020),
                        Color(0xFF110A1C),
                    ),
                ),
            ),
    ) {
        CyberGrid()
        Box(
            modifier = Modifier
                .padding(top = 74.dp, start = 32.dp)
                .size(132.dp)
                .blur(42.dp)
                .background(Color(0xFF00E5FF).copy(alpha = 0.28f), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = 92.dp)
                .size(176.dp)
                .blur(52.dp)
                .background(Color(0xFFFF2FB3).copy(alpha = 0.2f), CircleShape),
        )
        Box(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 10.dp, top = 120.dp)
                .size(116.dp)
                .blur(44.dp)
                .background(Color(0xFF39FF88).copy(alpha = 0.13f), CircleShape),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 24.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.Top,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            NoraStatusPill(isConnected = state.isConnected, isConnecting = state.isConnecting)
            Spacer(modifier = Modifier.height(28.dp))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top,
            ) {
                SoundWaveOrb(
                    isActive = state.isConnected,
                    isConnecting = state.isConnecting,
                )
                Spacer(modifier = Modifier.height(18.dp))
                Text(
                    text = "Nora",
                    color = Color(0xFFF8FBFF),
                    fontSize = 54.sp,
                    lineHeight = 58.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = 0.sp,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = state.statusText,
                    color = Color(0xFFC9D8FF),
                    fontSize = 20.sp,
                    lineHeight = 26.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth(),
            ) {
                NoraPrimaryButton(
                    isConnecting = state.isConnecting,
                    isConnected = state.isConnected,
                    onClick = {
                        onStartService()
                        onAuthenticate { authenticated ->
                            if (authenticated) {
                                viewModel.startSession()
                            }
                        }
                    },
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NoraTextAction(
                        text = "End",
                        enabled = state.isConnected || state.isConnecting,
                        onClick = viewModel::stopSession,
                    )
                    NoraTextAction(
                        text = "Clear",
                        enabled = true,
                        onClick = viewModel::clearEventLog,
                    )
                }
                Spacer(modifier = Modifier.height(22.dp))
                NoraEventPanel(text = state.lastEvent)
            }
        }
    }
}

@Composable
private fun CyberGrid() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val spacing = 42.dp.toPx()
        var x = 0f
        while (x <= size.width) {
            drawLine(
                color = Color(0xFF7DF9FF).copy(alpha = 0.045f),
                start = Offset(x, 0f),
                end = Offset(x, size.height),
                strokeWidth = 1f,
            )
            x += spacing
        }

        var y = 0f
        while (y <= size.height) {
            drawLine(
                color = Color(0xFFFF2FB3).copy(alpha = 0.035f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += spacing
        }
    }
}

@Composable
private fun NoraStatusPill(isConnected: Boolean, isConnecting: Boolean) {
    val dotColor = when {
        isConnected -> Color(0xFF34C759)
        isConnecting -> Color(0xFFFFB340)
        else -> Color(0xFF8E8E93)
    }
    val text = when {
        isConnected -> "Live"
        isConnecting -> "Linking"
        else -> "Ready"
    }

    Row(
        modifier = Modifier
            .shadow(18.dp, RoundedCornerShape(22.dp), spotColor = Color(0x6600E5FF))
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF101827).copy(alpha = 0.72f))
            .border(1.dp, Color(0xFF7DF9FF).copy(alpha = 0.32f), RoundedCornerShape(22.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(dotColor, CircleShape),
        )
        Text(
            text = text,
            color = Color(0xFFE9F8FF),
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.sp,
        )
    }
}

@Composable
private fun SoundWaveOrb(isActive: Boolean, isConnecting: Boolean) {
    val transition = rememberInfiniteTransition(label = "sound-wave")
    val pulse by transition.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (isActive) 950 else 1450),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse",
    )
    val wave by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1650),
            repeatMode = RepeatMode.Restart,
        ),
        label = "wave",
    )
    val animatedScale = if (isActive || isConnecting) pulse else 1f

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(182.dp)
            .graphicsLayer {
                scaleX = animatedScale
                scaleY = animatedScale
            },
    ) {
        Box(
            modifier = Modifier
                .size(160.dp)
                .shadow(
                    elevation = 34.dp,
                    shape = CircleShape,
                    ambientColor = Color(0x6600E5FF),
                    spotColor = Color(0x66FF2FB3),
                )
                .clip(CircleShape)
                .background(
                    Brush.linearGradient(
                        colors = listOf(
                            Color(0xFFEBFDFF),
                            Color(0xFF54E7FF),
                            Color(0xFF7F5CFF),
                            Color(0xFF11152F),
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(430f, 460f),
                    ),
                ),
        )

        Canvas(modifier = Modifier.size(160.dp)) {
            val radius = size.minDimension / 2f
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color.White.copy(alpha = 0.96f),
                        Color(0xFF7DF9FF).copy(alpha = 0.24f),
                        Color.Transparent,
                    ),
                    center = Offset(size.width * 0.34f, size.height * 0.24f),
                    radius = radius * 0.9f,
                ),
            )
            drawCircle(
                color = Color(0xFFFF2FB3).copy(alpha = 0.18f),
                radius = radius * 0.78f,
                center = Offset(size.width * 0.56f, size.height * 0.58f),
            )

            val center = Offset(size.width / 2f, size.height / 2f)
            val bars = listOf(0.36f, 0.58f, 0.84f, 0.58f, 0.36f)
            val spacing = size.width * 0.105f
            val baseX = center.x - spacing * 2f
            bars.forEachIndexed { index, heightFactor ->
                val activeLift = if (isActive || isConnecting) {
                    0.1f * sin((wave * 6.28f) + index).toFloat()
                } else {
                    0f
                }
                val barHeight = size.height * (heightFactor + activeLift).coerceIn(0.28f, 0.9f)
                val x = baseX + index * spacing
                drawLine(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.White, Color(0xFF7DF9FF), Color(0xFF7F5CFF)),
                        startY = center.y - barHeight / 2f,
                        endY = center.y + barHeight / 2f,
                    ),
                    start = Offset(x, center.y - barHeight / 2f),
                    end = Offset(x, center.y + barHeight / 2f),
                    strokeWidth = size.width * 0.055f,
                    cap = StrokeCap.Round,
                )
            }

            repeat(3) { index ->
                val arcRadius = radius * (0.52f + index * 0.17f)
                val topLeft = Offset(center.x - arcRadius, center.y - arcRadius)
                val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
                val alpha = if (isActive) 0.32f - index * 0.08f else 0.16f - index * 0.04f
                drawArc(
                    color = Color(0xFF7DF9FF).copy(alpha = alpha.coerceAtLeast(0.04f)),
                    startAngle = -38f,
                    sweepAngle = 76f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = size.width * 0.022f, cap = StrokeCap.Round),
                )
                drawArc(
                    color = Color(0xFFFF2FB3).copy(alpha = alpha.coerceAtLeast(0.04f)),
                    startAngle = 142f,
                    sweepAngle = 76f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = size.width * 0.022f, cap = StrokeCap.Round),
                )
            }
        }
    }
}

@Composable
private fun NoraPrimaryButton(
    isConnecting: Boolean,
    isConnected: Boolean,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = !isConnected && !isConnecting,
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            contentColor = Color.White,
            disabledContentColor = Color(0xFF738091),
        ),
        contentPadding = PaddingValues(),
        modifier = Modifier
            .fillMaxWidth()
            .height(58.dp)
            .shadow(
                elevation = if (isConnected) 0.dp else 18.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color(0x5500E5FF),
                spotColor = Color(0x66FF2FB3),
            )
            .clip(RoundedCornerShape(28.dp))
            .background(
                if (isConnected || isConnecting) {
                    Brush.horizontalGradient(listOf(Color(0xFF1C2534), Color(0xFF252033)))
                } else {
                    Brush.horizontalGradient(
                        listOf(Color(0xFF00E5FF), Color(0xFF7F5CFF), Color(0xFFFF2FB3)),
                    )
                },
            ),
    ) {
        if (isConnecting) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = Color(0xFF7DF9FF),
                strokeWidth = 2.5.dp,
            )
        } else {
            Text(
                text = "Talk to Nora",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 0.sp,
            )
        }
    }
}

@Composable
private fun NoraTextAction(text: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(22.dp),
        colors = ButtonDefaults.textButtonColors(
            contentColor = Color(0xFF7DF9FF),
            disabledContentColor = Color(0xFF596372),
        ),
        modifier = Modifier
            .width(94.dp)
            .height(44.dp)
            .clip(RoundedCornerShape(22.dp))
            .background(Color(0xFF111827).copy(alpha = 0.68f))
            .border(1.dp, Color(0xFF7DF9FF).copy(alpha = 0.26f), RoundedCornerShape(22.dp)),
    ) {
        Text(
            text = text,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
        )
    }
}

@Composable
private fun NoraEventPanel(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(102.dp)
            .shadow(18.dp, RoundedCornerShape(28.dp), spotColor = Color(0x3300E5FF))
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xFF0D1422).copy(alpha = 0.78f))
            .border(1.dp, Color(0xFF7DF9FF).copy(alpha = 0.24f), RoundedCornerShape(28.dp))
            .padding(horizontal = 18.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color(0xFFE5F7FF),
            fontSize = 17.sp,
            lineHeight = 23.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 0.sp,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
