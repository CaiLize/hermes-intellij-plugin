package com.hermes.intellij.toolwindow

import java.awt.*
import java.awt.event.*
import java.awt.geom.AffineTransform
import java.awt.image.BufferedImage
import java.util.Base64
import javax.swing.*

/**
 * 可缩放的图片预览对话框。
 *
 * 功能：
 * - 默认缩放适配窗口，看全整图
 * - 鼠标滚轮放大/缩小（以鼠标位置为中心）
 * - 左键拖拽平移
 * - ESC 关闭
 * - 窗口缩放时自动适配
 */
class ImagePreviewDialog(
    parent: Frame?,
    image: Image
) : JDialog(parent, "Image Preview", false) {

    private val bufferedImage: BufferedImage
    private var scale = 1.0
    private var offsetX = 0.0
    private var offsetY = 0.0
    private var isDragging = false
    private var dragStartX = 0
    private var dragStartY = 0
    private var dragOffsetX = 0.0
    private var dragOffsetY = 0.0

    private val minScale = 0.05
    private val maxScale = 20.0
    private val zoomFactor = 1.15

    // canvas 引用，用于 fitToWindow 中获取实际绘制区域大小
    private val canvas: JPanel

    init {
        // 将 Image 转为 BufferedImage
        bufferedImage = if (image is BufferedImage) {
            image
        } else {
            val bi = BufferedImage(image.getWidth(null), image.getHeight(null), BufferedImage.TYPE_INT_ARGB)
            val g2d = bi.createGraphics()
            g2d.drawImage(image, 0, 0, null)
            g2d.dispose()
            bi
        }

        defaultCloseOperation = DISPOSE_ON_CLOSE

        canvas = object : JPanel() {
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2d = g as Graphics2D
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)

                // 画深灰背景
                g2d.color = Color(45, 45, 48)
                g2d.fillRect(0, 0, width, height)

                // 绘制图片
                val transform = AffineTransform()
                transform.translate(offsetX, offsetY)
                transform.scale(scale, scale)
                g2d.drawImage(bufferedImage, transform, null)
            }
        }

        canvas.background = Color(45, 45, 48)
        canvas.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        // 鼠标滚轮缩放
        canvas.addMouseWheelListener { e ->
            val mouseX = e.x.toDouble()
            val mouseY = e.y.toDouble()

            val oldScale = scale
            if (e.wheelRotation < 0) {
                // 放大
                scale = (scale * zoomFactor).coerceAtMost(maxScale)
            } else {
                // 缩小
                scale = (scale / zoomFactor).coerceAtLeast(minScale)
            }

            // 以鼠标位置为中心缩放
            val scaleRatio = scale / oldScale
            offsetX = mouseX - (mouseX - offsetX) * scaleRatio
            offsetY = mouseY - (mouseY - offsetY) * scaleRatio

            canvas.repaint()
        }

        // 鼠标拖拽平移
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    isDragging = true
                    dragStartX = e.x
                    dragStartY = e.y
                    dragOffsetX = offsetX
                    dragOffsetY = offsetY
                    canvas.cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.button == MouseEvent.BUTTON1) {
                    isDragging = false
                    canvas.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                }
            }
        })

        canvas.addMouseMotionListener(object : MouseMotionAdapter() {
            override fun mouseDragged(e: MouseEvent) {
                if (isDragging) {
                    offsetX = dragOffsetX + (e.x - dragStartX)
                    offsetY = dragOffsetY + (e.y - dragStartY)
                    canvas.repaint()
                }
            }
        })

        // 双击重置缩放（适配窗口）
        canvas.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2 && e.button == MouseEvent.BUTTON1) {
                    fitToWindow()
                    canvas.repaint()
                }
            }
        })

        // ESC 关闭
        val escapeStroke = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)
        canvas.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(escapeStroke, "close")
        canvas.actionMap.put("close", object : AbstractAction() {
            override fun actionPerformed(e: ActionEvent?) {
                dispose()
            }
        })

        // 窗口缩放时重新适配
        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent?) {
                fitToWindow()
                canvas.repaint()
            }
        })

        contentPane = canvas

        // 设置窗口大小为屏幕 60%
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val dialogWidth = (screenSize.width * 0.6).toInt()
        val dialogHeight = (screenSize.height * 0.7).toInt()
        size = Dimension(dialogWidth, dialogHeight)
        setLocationRelativeTo(parent)

        // 初始缩放适配
        fitToWindow()
    }

    /**
     * 计算缩放和偏移，使图片完整显示在 canvas 内容区域中。
     * 使用 canvas 的实际大小而非 dialog 的大小，避免标题栏导致上下间距不对称。
     */
    private fun fitToWindow() {
        val canvasWidth = canvas.width
        val canvasHeight = canvas.height
        if (canvasWidth <= 0 || canvasHeight <= 0) return

        val imgWidth = bufferedImage.width.toDouble()
        val imgHeight = bufferedImage.height.toDouble()
        if (imgWidth <= 0 || imgHeight <= 0) return

        // 留统一边距
        val padding = 20.0
        val availableWidth = canvasWidth - padding * 2
        val availableHeight = canvasHeight - padding * 2

        val scaleX = availableWidth / imgWidth
        val scaleY = availableHeight / imgHeight
        scale = minOf(scaleX, scaleY).coerceIn(minScale, maxScale)

        // 居中（基于 canvas 大小）
        val scaledWidth = imgWidth * scale
        val scaledHeight = imgHeight * scale
        offsetX = (canvasWidth - scaledWidth) / 2.0
        offsetY = (canvasHeight - scaledHeight) / 2.0
    }

    companion object {
        // P0: 防止资源耗尽攻击 - Base64 数据大小限制（5MB）
        private const val MAX_BASE64_SIZE = 5 * 1024 * 1024

        /**
         * 从 base64 数据创建图片预览对话框。
         */
        fun showFromBase64(parent: Component?, base64Data: String) {
            // P0: 添加大小检查，防止 DoS 攻击
            if (base64Data.length > MAX_BASE64_SIZE) {
                JOptionPane.showMessageDialog(
                    parent,
                    "图片数据过大（最大 5MB），无法加载",
                    "错误",
                    JOptionPane.ERROR_MESSAGE
                )
                return
            }

            try {
                val cleanBase64 = base64Data
                    .removePrefix("data:image/jpeg;base64,")
                    .removePrefix("data:image/png;base64,")
                    .removePrefix("data:image/jpg;base64,")
                    .removePrefix("data:image/gif;base64,")
                    .removePrefix("data:image/webp;base64,")
                    .removePrefix("data:image/bmp;base64,")
                
                // 额外的长度检查（解码前）
                if (cleanBase64.length > MAX_BASE64_SIZE) {
                    JOptionPane.showMessageDialog(
                        parent,
                        "图片数据过大，无法加载",
                        "错误",
                        JOptionPane.ERROR_MESSAGE
                    )
                    return
                }

                val imageBytes = Base64.getDecoder().decode(cleanBase64)
                
                // 解码后再次检查
                if (imageBytes.size > MAX_BASE64_SIZE) {
                    JOptionPane.showMessageDialog(
                        parent,
                        "图片文件过大，无法加载",
                        "错误",
                        JOptionPane.ERROR_MESSAGE
                    )
                    return
                }

                val image = Toolkit.getDefaultToolkit().createImage(imageBytes)
                // 确保图片加载完成
                val tracker = MediaTracker(java.awt.Panel())
                tracker.addImage(image, 0)
                tracker.waitForID(0, 5000)

                val parentFrame = if (parent != null) {
                    SwingUtilities.getWindowAncestor(parent) as? Frame
                } else null

                val dialog = ImagePreviewDialog(parentFrame, image)
                dialog.isVisible = true
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    parent,
                    "Failed to load image: ${e.message}",
                    "Image Preview Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }

        /**
         *从 Image 对象创建图片预览对话框。
         */
        fun showFromImage(parent: Component?, image: Image) {
            try {
                val tracker = MediaTracker(java.awt.Panel())
                tracker.addImage(image, 0)
                tracker.waitForID(0, 5000)

                val parentFrame = if (parent != null) {
                    SwingUtilities.getWindowAncestor(parent) as? Frame
                } else null

                val dialog = ImagePreviewDialog(parentFrame, image)
                dialog.isVisible = true
            } catch (e: Exception) {
                JOptionPane.showMessageDialog(
                    parent,
                    "Failed to load image: ${e.message}",
                    "Image Preview Error",
                    JOptionPane.ERROR_MESSAGE
                )
            }
        }
    }
}
