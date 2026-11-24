package com.netcontrol.core.gkd.selector

import com.netcontrol.core.gkd.snapshot.GkdSnapshotNode
import com.netcontrol.core.gkd.snapshot.SnapshotNodeAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GkdSelectorEngineTest {

    /**
     * 构造一棵典型的开屏广告 + 复杂信息流弹窗节点树：
     * 0: FrameLayout (root)
     * ├── 1: LinearLayout (vid=skip_container, clickable=true)
     * │   └── 2: TextView (vid=tv_skip, text="跳过 3s", clickable=false)
     * └── 3: RelativeLayout (vid=ad_card)
     *     ├── 4: LinearLayout (vid=ad_label_box, index=0)
     *     │   └── 5: TextView (text="广告推广", index=0)
     *     └── 6: FrameLayout (vid=menu_box, index=1)
     *         └── 7: ImageView (vid=close_btn, clickable=true, desc="关闭")
     */
    private val sampleNodes = listOf(
        GkdSnapshotNode(0, -1, 0, 0, "android.widget.FrameLayout", null, null, null, false, true, 0, 0, 1080, 2400),
        GkdSnapshotNode(1, 0, 0, 1, "android.widget.LinearLayout", "com.demo:id/skip_container", null, null, true, true, 850, 80, 1040, 160),
        GkdSnapshotNode(2, 1, 0, 2, "android.widget.TextView", "com.demo:id/tv_skip", "跳过 3s", null, false, true, 860, 90, 1030, 150),
        GkdSnapshotNode(3, 0, 1, 1, "android.widget.RelativeLayout", "com.demo:id/ad_card", null, null, false, true, 0, 500, 1080, 900),
        GkdSnapshotNode(4, 3, 0, 2, "android.widget.LinearLayout", "com.demo:id/ad_label_box", null, null, false, true, 20, 520, 300, 580),
        GkdSnapshotNode(5, 4, 0, 3, "android.widget.TextView", null, "广告推广", null, false, true, 30, 530, 200, 570),
        GkdSnapshotNode(6, 3, 1, 2, "android.widget.FrameLayout", "com.demo:id/menu_box", null, null, false, true, 960, 520, 1060, 580),
        GkdSnapshotNode(7, 6, 0, 3, "android.widget.ImageView", "com.demo:id/close_btn", null, "关闭", true, true, 970, 530, 1050, 570)
    )

    private val adapter = SnapshotNodeAdapter(sampleNodes)
    private val root = adapter.root!!

    @Test
    fun targetRedirectionAtPrefix_clicksClickableParentInsteadOfTextView() {
        val sel = GkdSelectorParser.parse("""@[clickable=true] > TextView[text*="跳过"][text.length<=10]""")
        val hit = GkdSelectorMatcher.matchFirst(sel, root, adapter, "com.demo", fastQuery = true)

        assertNotNull(hit)
        assertEquals(1, hit!!.id) // 点击的是可点击的父容器 skip_container，而非不可点的 TextView
    }

    @Test
    fun defaultTargetWithoutAt_returnsRightmostNode() {
        val sel = GkdSelectorParser.parse("""LinearLayout[vid="skip_container"] > TextView[text~="跳过\\s*\\d+s"]""")
        val hit = GkdSelectorMatcher.matchFirst(sel, root, adapter, "com.demo", fastQuery = false)

        assertNotNull(hit)
        assertEquals(2, hit!!.id)
    }

    @Test
    fun complexMultiCombinatorPopupRule_matchesCloseButtonViaSiblingAndParent() {
        // 先找“广告”开头文本 -> 父节点 ad_label_box -> 后兄弟 menu_box -> 子节点 @close_btn
        val sel = GkdSelectorParser.parse(
            """TextView[text^="广告"] < [vid="ad_label_box"] + [vid="menu_box"] > @[vid="close_btn"][clickable=true]"""
        )
        val hit = GkdSelectorMatcher.matchFirst(sel, root, adapter, "com.demo", fastQuery = true)

        assertNotNull(hit)
        assertEquals(7, hit!!.id)
    }

    @Test
    fun ancestorCombinatorAndLogicalOperators_workCorrectly() {
        val sel = GkdSelectorParser.parse("""[text="跳过 3s"] <<n FrameLayout[parent=null]""")
        val hit = GkdSelectorMatcher.matchFirst(sel, root, adapter, "com.demo", fastQuery = false)
        assertNotNull(hit)
        assertEquals(0, hit!!.id)

        val negated = GkdSelectorParser.parse("""TextView[text*="跳过" && !(text*="禁止")]""")
        assertNotNull(GkdSelectorMatcher.matchFirst(negated, root, adapter, "com.demo", fastQuery = true))

        val excluded = GkdSelectorParser.parse("""TextView[text*="跳过" && !(text*="3s")]""")
        assertNull(GkdSelectorMatcher.matchFirst(excluded, root, adapter, "com.demo", fastQuery = true))
    }
}
