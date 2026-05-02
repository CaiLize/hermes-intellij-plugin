package com.hermes.intellij.toolwindow

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.Color

/**
 * Centralized color and dimension constants for the chat UI.
 * All colors support light/dark theme via JBColor.
 */
object ChatColors {
    val hermesPurple = JBColor(Color(108, 71, 255), Color(150, 120, 255))
    val userBlue = JBColor(Color(30, 100, 200), Color(100, 160, 255))
    val separator = JBColor(Color(230, 230, 230), Color(50, 50, 50))
    val timestampGray = JBColor(Color(153, 153, 153), Color(120, 120, 120))
    val inlineCodeBg = JBColor(Color(240, 240, 240), Color(55, 55, 55))
    val inlineCodeFg = JBColor(Color(199, 37, 78), Color(230, 219, 116))
    val codeBlockBg = JBColor(Color(248, 248, 248), Color(43, 43, 43))
    val codeBlockHeader = JBColor(Color(240, 240, 240), Color(50, 50, 50))
    val inputBorder = JBColor(Color(200, 200, 200), Color(70, 70, 70))
    val actionIcon = JBColor(Color(108, 108, 108), Color(160, 160, 160))
    val chipBg = JBColor(Color(243, 244, 246), Color(55, 55, 58))
    val chipBorder = JBColor(Color(220, 220, 225), Color(70, 70, 73))
    val errorText = JBColor(Color(200, 50, 50), Color(255, 100, 100))

    val avatarSize get() = JBUI.scale(24)
}
