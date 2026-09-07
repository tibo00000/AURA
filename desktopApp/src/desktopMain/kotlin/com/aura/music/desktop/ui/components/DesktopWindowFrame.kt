package com.aura.music.desktop.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowScope
import androidx.compose.ui.window.WindowState
import com.aura.music.desktop.ui.handClickable
import com.aura.music.ui.theme.*
import java.awt.Cursor
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.SwingUtilities

/**
 * Barre de titre moderne sans bordure apparente (Option 2), parfaitement intégrée
 * au thème sombre DeepBlack d'AURA.
 */
@Composable
fun WindowScope.DesktopTitleBar(
    windowState: WindowState,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isMaximized = windowState.placement == WindowPlacement.Maximized

    WindowDraggableArea(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(DeepBlack)
    ) {
        Row(
            modifier = Modifier.fillMaxSize(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Zone Draggable (permet de déplacer et double-clic pour agrandir/restaurer)
            Spacer(modifier = Modifier.weight(1f))

            // Contrôles de Fenêtre (Droite : Minimiser, Agrandir/Restaurer, Fermer)
            Row(
                modifier = Modifier.fillMaxHeight(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Minimiser
                TitleBarButton(
                    onClick = { windowState.isMinimized = true },
                    hoverBackground = PureWhite.copy(alpha = 0.08f)
                ) { _ ->
                    Icon(
                        imageVector = Icons.Rounded.Remove,
                        contentDescription = "Minimiser",
                        tint = PureWhite.copy(alpha = 0.75f),
                        modifier = Modifier.size(16.dp)
                    )
                }

                // Agrandir / Restaurer
                TitleBarButton(
                    onClick = {
                        windowState.placement = if (isMaximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                    },
                    hoverBackground = PureWhite.copy(alpha = 0.08f)
                ) { _ ->
                    Icon(
                        imageVector = if (isMaximized) Icons.Rounded.WebAsset else Icons.Rounded.CropSquare,
                        contentDescription = if (isMaximized) "Restaurer" else "Agrandir",
                        tint = PureWhite.copy(alpha = 0.75f),
                        modifier = Modifier.size(14.dp)
                    )
                }

                // Fermer (Fond rouge au survol)
                TitleBarButton(
                    onClick = onClose,
                    hoverBackground = Color(0xFFE81123)
                ) { isHovered ->
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Fermer",
                        tint = if (isHovered) PureWhite else PureWhite.copy(alpha = 0.75f),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun TitleBarButton(
    onClick: () -> Unit,
    hoverBackground: Color,
    activeTint: Color? = null,
    modifier: Modifier = Modifier,
    content: @Composable (isHovered: Boolean) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()

    Box(
        modifier = modifier
            .width(46.dp)
            .fillMaxHeight()
            .background(if (isHovered) hoverBackground else Color.Transparent)
            .hoverable(interactionSource)
            .handClickable(interactionSource = interactionSource, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content(isHovered)
    }
}

/**
 * Installe les écouteurs de redimensionnement souris natifs pour les fenêtres sans bordure (undecorated).
 * Permet de redimensionner la fenêtre à la souris par les bords et les coins lorsqu'elle est flottante.
 */
@Composable
fun WindowScope.InstallUndecoratedResizer(
    windowState: WindowState,
    minWidth: Int = 1000,
    minHeight: Int = 640,
    borderWidth: Int = 6
) {
    val isFloating = windowState.placement == WindowPlacement.Floating

    DisposableEffect(window, isFloating) {
        if (!isFloating) return@DisposableEffect onDispose {}

        val awtWindow = window
        var cursorType = Cursor.DEFAULT_CURSOR
        var startMousePos: Point? = null
        var startBounds: java.awt.Rectangle? = null

        val mouseAdapter = object : MouseAdapter() {
            override fun mouseMoved(e: MouseEvent) {
                if (windowState.placement == WindowPlacement.Maximized) {
                    if (awtWindow.cursor.type != Cursor.DEFAULT_CURSOR) {
                        awtWindow.cursor = Cursor.getDefaultCursor()
                    }
                    return
                }

                val w = awtWindow.width
                val h = awtWindow.height
                val x = e.x
                val y = e.y

                val left = x in 0..borderWidth
                val right = x in (w - borderWidth)..w
                val top = y in 0..borderWidth
                val bottom = y in (h - borderWidth)..h

                cursorType = when {
                    top && left -> Cursor.NW_RESIZE_CURSOR
                    top && right -> Cursor.NE_RESIZE_CURSOR
                    bottom && left -> Cursor.SW_RESIZE_CURSOR
                    bottom && right -> Cursor.SE_RESIZE_CURSOR
                    left -> Cursor.W_RESIZE_CURSOR
                    right -> Cursor.E_RESIZE_CURSOR
                    top -> Cursor.N_RESIZE_CURSOR
                    bottom -> Cursor.S_RESIZE_CURSOR
                    else -> Cursor.DEFAULT_CURSOR
                }

                if (awtWindow.cursor.type != cursorType) {
                    awtWindow.cursor = Cursor.getPredefinedCursor(cursorType)
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (cursorType != Cursor.DEFAULT_CURSOR && SwingUtilities.isLeftMouseButton(e)) {
                    startMousePos = e.locationOnScreen
                    startBounds = awtWindow.bounds
                }
            }

            override fun mouseDragged(e: MouseEvent) {
                val start = startMousePos ?: return
                val bounds = startBounds ?: return
                if (cursorType == Cursor.DEFAULT_CURSOR) return

                val current = e.locationOnScreen
                val dx = current.x - start.x
                val dy = current.y - start.y

                var newX = bounds.x
                var newY = bounds.y
                var newW = bounds.width
                var newH = bounds.height

                when (cursorType) {
                    Cursor.E_RESIZE_CURSOR -> {
                        newW = (bounds.width + dx).coerceAtLeast(minWidth)
                    }
                    Cursor.S_RESIZE_CURSOR -> {
                        newH = (bounds.height + dy).coerceAtLeast(minHeight)
                    }
                    Cursor.SE_RESIZE_CURSOR -> {
                        newW = (bounds.width + dx).coerceAtLeast(minWidth)
                        newH = (bounds.height + dy).coerceAtLeast(minHeight)
                    }
                    Cursor.W_RESIZE_CURSOR -> {
                        val potentialW = bounds.width - dx
                        if (potentialW >= minWidth) {
                            newX = bounds.x + dx
                            newW = potentialW
                        }
                    }
                    Cursor.N_RESIZE_CURSOR -> {
                        val potentialH = bounds.height - dy
                        if (potentialH >= minHeight) {
                            newY = bounds.y + dy
                            newH = potentialH
                        }
                    }
                    Cursor.NW_RESIZE_CURSOR -> {
                        val potentialW = bounds.width - dx
                        val potentialH = bounds.height - dy
                        if (potentialW >= minWidth) {
                            newX = bounds.x + dx
                            newW = potentialW
                        }
                        if (potentialH >= minHeight) {
                            newY = bounds.y + dy
                            newH = potentialH
                        }
                    }
                    Cursor.NE_RESIZE_CURSOR -> {
                        val potentialH = bounds.height - dy
                        newW = (bounds.width + dx).coerceAtLeast(minWidth)
                        if (potentialH >= minHeight) {
                            newY = bounds.y + dy
                            newH = potentialH
                        }
                    }
                    Cursor.SW_RESIZE_CURSOR -> {
                        val potentialW = bounds.width - dx
                        if (potentialW >= minWidth) {
                            newX = bounds.x + dx
                            newW = potentialW
                        }
                        newH = (bounds.height + dy).coerceAtLeast(minHeight)
                    }
                }

                awtWindow.setBounds(newX, newY, newW, newH)
            }

            override fun mouseReleased(e: MouseEvent) {
                startMousePos = null
                startBounds = null
            }
        }

        awtWindow.addMouseListener(mouseAdapter)
        awtWindow.addMouseMotionListener(mouseAdapter)

        onDispose {
            awtWindow.removeMouseListener(mouseAdapter)
            awtWindow.removeMouseMotionListener(mouseAdapter)
            if (awtWindow.cursor.type != Cursor.DEFAULT_CURSOR) {
                awtWindow.cursor = Cursor.getDefaultCursor()
            }
        }
    }
}
