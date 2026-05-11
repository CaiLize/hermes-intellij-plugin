package com.hermes.intellij.toolwindow

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.*
import javax.swing.*
import javax.swing.border.EmptyBorder

/**
 * Avatar panel - displays a colored circle with icon for user/hermes identification.
 * Properly handles transparency and anti-aliasing for clean rendering.
 */
class AvatarPanel(private val avatarRole: String) : JPanel() {
    private val size = ChatColors.avatarSize
    private val iconLabel: JBLabel
    
    init { 
        isOpaque = false
        background = Color(0, 0, 0, 0)  // Transparent background
        preferredSize = Dimension(size, size)
        maximumSize = Dimension(size, size)
        minimumSize = Dimension(size, size)
        layout = BorderLayout()
        
        // Create icon label based on role
        val icon = if (avatarRole == "user") {
            // User icon - simple person silhouette
            IconLoader.getIcon("/icons/user_13.svg", AvatarPanel::class.java)
        } else {
            // Hermes icon - white H letter only (background drawn by paintComponent)
            IconLoader.getIcon("/icons/hermes_13.svg", AvatarPanel::class.java)
        }
        
        iconLabel = JBLabel(icon).apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            isOpaque = false
            // Center the 13px icon in the 24px avatar circle
            border = EmptyBorder(3, 3, 3, 3)
        }
        add(iconLabel, BorderLayout.CENTER)
    }
    
    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
        
        // Set color based on role - draw background circle FIRST
        g2.color = if (avatarRole == "user") ChatColors.userBlue else ChatColors.hermesPurple
        
        // Draw filled circle with small margin for clean edges
        val margin = 1
        g2.fillOval(margin, margin, size - margin * 2, size - margin * 2)
        
        // NOW paint children (icon label) on top of the background
        super.paintComponent(g)
    }
}
