package com.hermes.intellij.toolwindow

import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.LayoutManager2

/**
 * Vertical layout that stacks children top-to-bottom WITHOUT stretching them.
 *
 * Unlike BoxLayout.Y_AXIS, which distributes extra space proportionally based
 * on each child's maximumSize, this layout gives every child exactly its
 * preferredSize.height. This eliminates the need to override getMaximumSize()
 * on every container in the hierarchy to prevent blank space from appearing.
 *
 * Width behavior: each child is stretched to the full container width.
 *
 * maximumLayoutSize() returns preferredLayoutSize().height as the max height —
 * this tells parent containers not to allocate more vertical space than needed.
 */
class NoStretchVerticalLayout : LayoutManager2 {

    override fun preferredLayoutSize(parent: Container): Dimension {
        synchronized(parent.treeLock) {
            return computeSize(parent) { it.preferredSize }
        }
    }

    override fun minimumLayoutSize(parent: Container): Dimension {
        synchronized(parent.treeLock) {
            return computeSize(parent) { it.minimumSize }
        }
    }

    override fun maximumLayoutSize(parent: Container): Dimension {
        val pref = preferredLayoutSize(parent)
        return Dimension(Int.MAX_VALUE, pref.height)
    }

    override fun layoutContainer(parent: Container) {
        synchronized(parent.treeLock) {
            val insets = parent.insets
            var y = insets.top
            val width = parent.width - insets.left - insets.right

            for (comp in parent.components) {
                if (!comp.isVisible) continue
                // 【关键】不限制子组件高度，让子组件自己决定显示区域
                // 子组件的 getPreferredSize() 可能在布局后变化（如 JEditorPane 的 HTML 懒加载）
                // 如果在这里限制高度，子组件内容会被截断
                // 使用 height = 0 让子组件自己管理高度（通过自身的 preferredSize）
                comp.setBounds(insets.left, y, width, 0)
                // 但 setBounds height=0 会导致组件不可见...
                // 改用 preferredSize.height，但允许子组件在后续更新
                val prefH = comp.preferredSize.height
                comp.setBounds(insets.left, y, width, prefH)
                y += prefH
            }
        }
    }

    private fun computeSize(parent: Container, sizeFn: (Component) -> Dimension): Dimension {
        val insets = parent.insets
        var maxWidth = 0
        var totalHeight = 0

        for (comp in parent.components) {
            if (!comp.isVisible) continue
            val size = sizeFn(comp)
            maxWidth = maxOf(maxWidth, size.width)
            totalHeight += size.height
        }

        return Dimension(
            maxWidth + insets.left + insets.right,
            totalHeight + insets.top + insets.bottom
        )
    }

    // LayoutManager2 required methods
    override fun addLayoutComponent(comp: Component, constraints: Any?) {}
    override fun removeLayoutComponent(comp: Component) {}
    override fun getLayoutAlignmentX(target: Container): Float = 0f
    override fun getLayoutAlignmentY(target: Container): Float = 0f
    override fun invalidateLayout(target: Container) {}

    // LayoutManager required (parent interface)
    override fun addLayoutComponent(name: String?, comp: Component) {}
}
