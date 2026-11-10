package com.frederic22.hyperextreme.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.frederic22.hyperextreme.R
import com.frederic22.hyperextreme.aicr.AicrVision
import com.frederic22.hyperextreme.data.AliveItem
import com.frederic22.hyperextreme.data.AlivePreview
import com.frederic22.hyperextreme.data.AliveStore
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.RasterStore
import com.frederic22.hyperextreme.ui.nav.NavBarBottomGap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.DropdownArrowEndAction
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Switch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.squircle.squircleClip
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowBottomSheet

/** 槽位尺寸 = 卡片流中间态卡片的 80%（与光栅浮页 `SLOT_SCALE` 同一个值）。 */
private const val ALIVE_SLOT_SCALE = 0.8f

/** 槽位间距（与光栅浮页 `SLOT_GAP` 逐值一致）。 */
private val ALIVE_SLOT_GAP = 12.dp

/** 息屏样式的下拉选项（下标即 [AliveStore.STYLE_QIHANG] / [AliveStore.STYLE_LIUGUANG]）。 */
private val ALIVE_STYLE_LABELS = listOf("启航", "留光")

/** 卡片圆角（与光栅浮页槽位一致）。 */
private val ALIVE_CARD_RADIUS = 16.dp

/**
 * Alive 壁纸的**编辑浮页**（2026-11-08）。
 *
 * 与光栅浮页（`RasterGroupSheet`）同一副骨架（顶栏 ✕ / ✓、底部按钮组、配色与
 * 启用条件都照抄），但内容只有**三个固定槽**，没有拖拽排序、没有多选：
 *
 * ```
 * [AOD]  [锁屏]  [桌面]        ← 三张等大
 *  AOD    锁屏    桌面         ← 卡片下 8dp，字号/颜色同选项 summary
 * 桌面与锁屏一致        [开关]
 * [计算锁屏景深] [删除锁屏景深]
 * [导入/更换锁屏的遮罩或主体]
 * ```
 *
 * * **AOD 卡**不可点 —— 它的内容是锁屏图算出来的预览（球心固定屏幕 0.5）；
 * * **锁屏卡**点击导图，导入后立刻重算 AOD 预览；
 * * **桌面卡**点击导图；开了「桌面与锁屏一致」就置灰、直接显示锁屏那张。
 *
 * 保存条件：锁屏与桌面都有图（或开了「一致」）。
 */
@Composable
internal fun AliveGroupSheet(
    show: Boolean,
    group: AliveItem?,
    /**
     * 这一次是**新建**（页面刚建的空组）还是编辑已有卡。
     *
     * ⚠️ 不能靠 `group == null` 判断：新建流程也会先建一个空组再进浮页，
     * 那样标题永远是「编辑」（2026-11-08 用户指出）。
     */
    isNew: Boolean = false,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberClickHaptic()

    var working by remember { mutableStateOf<AliveItem?>(null) }

    /**
     * 编辑草稿（2026-11-09）。**浮页里所有改动都只进它，不写 meta.json** ——
     * 点 ✓ 才 [AliveStore.Draft.commit]，点 ✕ / 左上角关闭就 [AliveStore.Draft.rollback]。
     * 与光栅 `RasterGroupSheet` 的 `names` 同一语义。
     */
    var draft by remember { mutableStateOf<AliveStore.Draft?>(null) }
    var importing by remember { mutableStateOf(false) }
    /** AOD 预览正在算。 */
    var generating by remember { mutableStateOf(false) }
    /** 正在跑的景深任务（null = 空闲）；按钮文案靠它显示「处理中…」。 */
    var runningTask by remember { mutableStateOf<String?>(null) }
    /** 锁屏封面（Compose Bitmap），导入 / 换图后重解码。 */
    var lockBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var deskBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var aodBmp by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var maskBusy by remember { mutableStateOf(false) }
    /** 等用户确认「尺寸与壁纸不一致」的那次导入（照光栅同一套流程）。 */
    var pendingMask by remember { mutableStateOf<Triple<AliveItem, Uri, RasterStore.SubjectProbe>?>(null) }

    LaunchedEffect(show, group?.id) {
        if (!show) return@LaunchedEffect
        val g = group ?: return@LaunchedEffect
        // 每次打开都新建一份草稿：上次残留的改动不该跟着这一次
        draft = AliveStore.Draft(g)
        working = g
        runningTask = null
    }

    // 三个槽的封面：**按文件路径分槽判定**，只有真的换了图才重解码。
    //
    // ⚠️ 之前是「四个 key 任一变化就三张一起重解码」（2026-11-09 用户报「切『桌面与锁屏一致』时
    //    AOD 与锁屏卡会闪一下」）：`followLock` 一变，锁屏 / AOD 的文件**根本没换**，
    //    却拿到了新的 `Bitmap` 对象 ⇒ `Crossfade(targetState = bitmap)` 按对象比较，
    //    会重播一次 150ms 交叉 —— 观感就是闪。
    //
    // ⚠️ 桌面卡**不能**用 `effectiveDesktop`（那个在没导图时会回落到锁屏图，
    // 是给渲染器兜底的语义）。关着「桌面与锁屏一致」时它必须显示自己的图或
    // 「新建」卡，否则用户会看到「关了开关桌面还跟着锁屏变」（2026-11-08 反馈）。
    var lockFile by remember { mutableStateOf<String?>(null) }
    var deskFile by remember { mutableStateOf<String?>(null) }
    var aodFile by remember { mutableStateOf<String?>(null) }
    // AOD 预览的版本号（见 `AliveItem.aodVer`）：草稿期预览写的是**固定名** `aod.png.draft`，
    // 路径不变 ⇒ 只看路径永远发现不了「内容换了」。切样式时靠它触发重解码。
    var aodVer by remember { mutableStateOf(-1L) }
    LaunchedEffect(
        working?.lock, working?.desktop, working?.aod, working?.aodVer, working?.followLock,
    ) {
        val g = working ?: return@LaunchedEffect
        // 开了「一致」时桌面那份就是锁屏图（同一个文件 ⇒ 同一个 Bitmap 实例）
        val lockKey = g.lock?.absolutePath
        val deskKey = if (g.followLock) lockKey else g.desktop?.absolutePath
        val aodKey = g.aod?.absolutePath
        withContext(Dispatchers.IO) {
            if (lockKey != lockFile) {
                lockBmp = g.lock?.let { AliveStore.decode(it, 720) }
                lockFile = lockKey
            }
            if (deskKey != deskFile) {
                deskBmp = when {
                    deskKey == null -> null
                    deskKey == lockKey -> lockBmp
                    else -> g.desktop?.let { AliveStore.decode(it, 720) }
                }
                deskFile = deskKey
            }
            if (aodKey != aodFile || g.aodVer != aodVer) {
                aodBmp = g.aod?.let { AliveStore.decode(it, 720) }
                aodFile = aodKey
                aodVer = g.aodVer
            }
        }
        // ⚠️ **不要 recycle 旧的**：Compose 的 `asImageBitmap()` 仍持有引用，
        // 旧的被回收后下一次绘制直接抛「trying to use a recycled bitmap」。
        // 720 短边的几张小图交给 GC 就行，一次浮页最多重解三四回。
    }

    val screen = rememberScreenSizePx()

    /**
     * 卡片流的滚动位置 —— **必须提到 `WindowBottomSheet` 外面**（2026-11-09 用户报
     * 「打开浮页 → 关闭 → 卡片自动跳到第一个」）。
     *
     * 写在里面的 `rememberScrollState()` 挂在浮页内容那棵子树上，miuix 收起浮页时会把
     * 内容移出组合 ⇒ 状态被丢掉，下次打开从 0 开始。放在这里就跟着 `AliveGroupSheet`
     * 自己的生命周期走（页面**常驻组合**它），开关多少次都停在原处。
     */
    val slotsScroll = rememberScrollState()

    /**
     * 重算 AOD 预览（只进草稿）。
     *
     * 源图看 [AliveItem.effectiveAodSrc]（「AOD 与锁屏一致」= 锁屏图，否则 = AOD 自己的图）；
     * 算法看 [AliveItem.style]：启航画圆球、留光画相片。
     * 源为空（留光 + 关一致 + 还没选图）时把预览清掉，卡片回到「新建」态。
     */
    suspend fun regenerateAod(item: AliveItem) {
        val d = draft ?: return
        val srcFile = item.effectiveAodSrc
        if (srcFile == null) {
            working = withContext(Dispatchers.IO) { d.setAod(null) }
            return
        }
        generating = true
        val preview = withContext(Dispatchers.IO) {
            val src = AliveStore.decode(srcFile, 1080)
            val out = src?.let {
                if (item.style == AliveStore.STYLE_LIUGUANG) {
                    AlivePreview.renderLiuguang(it, screen.width, screen.height)
                } else {
                    AlivePreview.render(it, screen.width, screen.height)
                }
            }
            src?.recycle()
            out
        }
        val next = withContext(Dispatchers.IO) { d.setAod(preview) }
        preview?.recycle()
        working = next
        generating = false
    }

    /**
     * 清掉一个槽（含它的景深附属）。锁屏槽连带把 AOD 预览一起删 —— 预览是从它算出来的。
     * **文件只登记、不删**（点了 ✓ 才真删）。
     */
    fun removeSlot(slot: AliveStore.Slot) {
        val d = draft ?: return
        haptic()
        scope.launch {
            val next = withContext(Dispatchers.IO) { d.clearSlot(slot) }
            working = next
        }
    }

    fun handlePick(slot: AliveStore.Slot, uri: Uri?) {
        if (uri == null) return
        val d = draft ?: return
        scope.launch {
            importing = true
            val next = withContext(Dispatchers.IO) { d.importImage(ctx, slot, uri) }
            importing = false
            if (next == null) {
                AppToast.show(ctx, "这张图读不出来，换一张试试")
                return@launch
            }
            working = next
            // 锁屏图变了要重算（AOD 跟着锁屏时）；AOD 自己换图也要重算
            if (slot == AliveStore.Slot.LOCK || slot == AliveStore.Slot.AOD) regenerateAod(next)
        }
    }

    val lockPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { handlePick(AliveStore.Slot.LOCK, it) }
    val deskPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { handlePick(AliveStore.Slot.DESKTOP, it) }
    val aodPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { handlePick(AliveStore.Slot.AOD, it) }

    /**
     * 遮罩 / 主体导入（只对锁屏那张）。
     *
     * 与光栅的差别：**不做尺寸一致性弹窗** —— Alive 只有一张锁屏图，直接按原图尺寸
     * 缩放即可（错位风险由用户自己感知，少一次打断）。
     */
    /** 真正落盘：主体直接缩放存、遮罩合成成主体（都走草稿的 `importDepth`）。 */
    fun applyDepth(uri: Uri, kind: RasterStore.SubjectKind) {
        val d = draft ?: return
        scope.launch {
            maskBusy = true
            val next = withContext(Dispatchers.IO) { d.importDepth(ctx, uri, kind) }
            maskBusy = false
            if (next != null) {
                working = next
                AppToast.show(
                    ctx,
                    if (kind == RasterStore.SubjectKind.MASK) "已导入遮罩" else "已导入主体",
                )
            } else {
                AppToast.show(ctx, "导入失败")
            }
        }
    }

    /**
     * 导入遮罩 / 主体 —— **完全照光栅的流程**（2026-11-08 用户要求）：
     *
     * 1. `probeSubject` 判类型：有透明区域 ⇒ 主体；灰度 ⇒ 遮罩；都不是 ⇒ 拒绝；
     * 2. 尺寸与原图不一致 ⇒ 弹「尺寸与壁纸不一致」，确认后才继续；
     * 3. 落盘：主体直接缩放到锁屏图尺寸；**遮罩走 `MaskComposer.compose` 合成成主体**
     *    （黑→透明、白→保留），与光栅 `applySubject` 是同一套算法。
     */
    val maskPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val g = working ?: return@rememberLauncherForActivityResult
        scope.launch {
            maskBusy = true
            val probe = withContext(Dispatchers.IO) { AliveStore.probeSubject(ctx, g, uri) }
            maskBusy = false
            when {
                probe == null -> AppToast.show(ctx, "这张图读不出来")
                probe.kind == RasterStore.SubjectKind.INVALID ->
                    AppToast.show(ctx, "无法检测出该图片为遮罩或主体")
                !probe.sizeMatch -> pendingMask = Triple(g, uri, probe)
                else -> applyDepth(uri, probe.kind)
            }
        }
    }

    /** 计算 / 删除锁屏景深（AICR 抠图），与光栅同一套 `AicrVision`。结果只进草稿。 */
    fun computeDepth() {
        val d = draft ?: return
        val lock = working?.lock ?: return
        if (runningTask != null) return
        haptic()
        runningTask = "compute"
        scope.launch {
            val bmp = withContext(Dispatchers.IO) {
                val r = AicrVision.segmentFile(ctx, lock)
                r.bitmap ?: r.mask
            }
            val next = withContext(Dispatchers.IO) { d.setLockSubject(bmp) }
            bmp?.recycle()
            runningTask = null
            if (next.lockSubject == null) {
                AppToast.show(ctx, "景深计算失败")
            } else {
                working = next
            }
        }
    }

    fun deleteDepth() {
        val d = draft ?: return
        if (runningTask != null) return
        haptic()
        runningTask = "delete"
        scope.launch {
            val next = withContext(Dispatchers.IO) { d.setLockSubject(null) }
            runningTask = null
            working = next
        }
    }

    /**
     * 收尾。**空组清理必须在这里做**（2026-11-08 真机 bug）：
     * 页面持有的 `group` 是**打开时的快照**，浮页里导入图片只改内部的 [working]，
     * 页面那个值永远是空组 ⇒ 如果让页面按 `isEmpty` 判空删组，点 ✓ 会把刚导入的整组删掉
     * （现象就是「点 ✓ 不保存」）。
     */
    /**
     * **取消**（左上角 ✕ / 下拉关闭 / 返回键）：什么都不存。
     *
     * - 编辑已有组：回滚草稿（只删本次新写的文件），`meta.json` 一个字节没动、
     *   旧的 `aod.png` / `*.subject.png` 也没被覆盖（草稿期写的是 `.draft`）；
     * - 新建组：整组目录删掉（含已经导入的图）。
     *
     * 用户 2026-11-09：「alive 壁纸控件更改过，即便点左上角关闭也会被存储，
     * 应该像光栅一样更改要点击右上角确认才生效」。
     */
    fun close() {
        val d = draft
        val g = working
        if (isNew) {
            // 整组目录都没了，rollback 只是兜底
            d?.rollback()
            if (g != null) AliveStore.delete(g)
        } else {
            d?.rollback()
        }
        onDismiss()
    }

    /**
     * **确认**（右上角 ✓）：校验通过后才真正落盘。
     *
     * 校验口径（2026-11-09 用户要求「按实际状态提示」）：
     * * 锁屏：**永远必填**（AOD 层与转场都以它为源）；
     * * 桌面：开「桌面与锁屏一致」时跟随锁屏、不算缺；否则必填；
     * * AOD：只有「留光 + 关『AOD 与锁屏一致』」才算一槽（`aodPickable`），此时必填；
     *   其它情况 AOD 是用锁屏图算出来的，不参与校验。
     *
     * 文案统一成「AOD/锁屏/桌面还没有选择图片」，缺几项报几项（顺序 AOD → 锁屏 → 桌面）。
     * 原来那两条「锁屏和桌面都要选图」「至少给锁屏选一张图」已删 —— 它们说不出到底缺哪张。
     */
    fun finish() {
        val g = working
        if (g != null) {
            val missing = buildList {
                if (g.aodPickable && g.aodSrc == null) add("AOD")
                if (g.lock == null) add("锁屏")
                if (!g.followLock && g.desktop == null) add("桌面")
            }
            if (missing.isNotEmpty()) {
                AppToast.show(ctx, "${missing.joinToString("/")}还没有选择图片")
                return
            }
        }
        android.util.Log.i(
            "HX-ALIVE-UI",
            "finish: isNew=$isNew id=${g?.id} style=${g?.style} lock=${g?.lock?.name} " +
                "desk=${g?.desktop?.name} isEmpty=${g?.isEmpty} followLock=${g?.followLock}",
        )
        draft?.commit()
        // 新建的组排到第一个（与光栅 `RasterGroupSheet.finish` **完全同位置、同条件**）：
        // 顺序持久化在 `aliveGroupOrder`，页面 onDismiss 的 reload 会按它重排。
        // 编辑已有组不动顺序（v1.22.37 用户报过：一编辑就跳到第一个）。
        if (isNew && g != null && !g.isEmpty) {
            val p = MaskPrefs(ctx)
            p.setAliveGroupOrder(listOf(g.id) + p.aliveGroupOrder.filterNot { it == g.id })
        }
        onDismiss()
    }

    WindowBottomSheet(
        show = show,
        title = if (isNew) "新建Alive壁纸" else "编辑Alive壁纸",
        onDismissRequest = { close() },
        startAction = {
            IconButton(onClick = { haptic(); close() }) {
                AliveSheetIcon(AliveIconKind.Close, "关闭")
            }
        },
        endAction = {
            IconButton(onClick = { haptic(); finish() }) {
                AliveSheetIcon(AliveIconKind.Check, "完成")
            }
        },
    ) {
        val g = working
        val idle = runningTask == null && !importing && !generating && !maskBusy
        val hasSubject = g?.lockSubject != null
        val hasLock = g?.lock != null

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = NavBarBottomGap),
        ) {
            // ── 三张卡 ──（尺寸 / 间距与光栅浮页的槽位逐值一致：0.45×0.8 屏宽、12dp 间距）
            val density = LocalDensity.current
            val slotW: Dp = with(density) { (screen.width * CARD_WIDTH_RATIO * ALIVE_SLOT_SCALE).toDp() }
            val slotH: Dp = with(density) {
                (slotW.value * screen.height / screen.width).dp
            }
            // ⚠️ 徽标探出量必须留成 **Row 自己的 padding**（照光栅 `RasterGroupSheet` 的注释）：
            //    写在 `horizontalScroll` 外面只是把**视口**缩小，内容照样贴边、徽标照样被裁；
            //    `end` 还要额外加 badgePad，否则滚到底时末卡右半个钮会被裁掉。
            val badgePad = cardBadgeOverflow(slotW)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(slotsScroll),
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(
                    // ⚠️ **不加 `padding(horizontal = 12.dp)`**（2026-11-09 用户报「按钮 / 卡片流
                    //    感觉在控件的框里面」）：光栅那页的卡片流是贴到浮页内容边界的
                    //    （`RasterGroupSheet` 的 Row 只有 `padding(end = badgePad)`），
                    //    这里多加 12dp 就会整体右移、视觉上「套了一层框」。
                    //    卡宽公式两边本来就一致（`屏宽 × 0.45 × 0.8`），不用动。
                    modifier = Modifier.padding(
                        end = badgePad,
                        top = badgePad,
                        bottom = badgePad,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(ALIVE_SLOT_GAP),
                ) {
                // AOD：默认是「算出来的预览」、不可点；
                // 只有「留光 + 关『AOD 与锁屏一致』」才允许给 AOD 单独选图
                val aodPickable = g?.aodPickable == true
                AliveSlot(
                    title = "AOD",
                    bitmap = aodBmp,
                    enabled = idle && aodPickable,
                    // 只有「没有旧图可看」时才盖忙碌暗罩（首次生成 / 清空后重建）。
                    // 切样式时旧预览要一直留着，等新图来了由 `Crossfade` 交叉过渡 ——
                    // 中间压一层暗罩会让人误以为图没了（2026-11-09 用户要求）。
                    busy = generating && aodBmp == null,
                    width = slotW,
                    height = slotH,
                    // 自己选的图才给删（删完回到「新建」态）；跟随锁屏时这张是生成物，不给删
                    onRemove = if (aodPickable && g?.aodSrc != null) {
                        ({ removeSlot(AliveStore.Slot.AOD) })
                    } else {
                        null
                    },
                    onClick = {
                        haptic()
                        aodPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
                // 锁屏：导入
                AliveSlot(
                    title = "锁屏",
                    bitmap = lockBmp,
                    enabled = idle,
                    width = slotW,
                    height = slotH,
                    onRemove = if (lockBmp != null) ({ removeSlot(AliveStore.Slot.LOCK) }) else null,
                    onClick = {
                        haptic()
                        lockPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
                // 桌面：导入；开了「一致」就置灰
                AliveSlot(
                    title = "桌面",
                    bitmap = deskBmp,
                    enabled = idle && g?.followLock != true,
                    width = slotW,
                    height = slotH,
                    // 开「一致」时桌面显示的是锁屏那张，删除钮属于锁屏卡
                    onRemove = if (deskBmp != null && g?.followLock != true) {
                        ({ removeSlot(AliveStore.Slot.DESKTOP) })
                    } else {
                        null
                    },
                    onClick = {
                        haptic()
                        deskPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )
                }
            }

            // 「尺寸与壁纸不一致」（照光栅同一条文案与交互）
            val pm = pendingMask
            WindowDialog(
                title = "尺寸与壁纸不一致",
                summary = if (pm == null) "" else {
                    val what = if (pm.third.kind == RasterStore.SubjectKind.MASK) "遮罩" else "主体"
                    "所选$what ${pm.third.srcW}×${pm.third.srcH}，" +
                        "原图 ${pm.third.dstW}×${pm.third.dstH}。\n" +
                        "尺寸不一致时${what}可能错位。仍要继续吗？"
                },
                show = pm != null,
                onDismissRequest = { pendingMask = null },
            ) {
                val dismissState = LocalDismissState.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(
                        text = "取消",
                        onClick = {
                            pendingMask = null
                            dismissState?.invoke()
                        },
                    )
                    TextButton(
                        text = "确定",
                        onClick = {
                            val p = pm
                            pendingMask = null
                            dismissState?.invoke()
                            if (p != null) applyDepth(p.second, p.third.kind)
                        },
                    )
                }
            }

            // ── 选项 ──
            //
            // 三行**共用一个看不见的卡片**（2026-11-09 用户要求）：
            // 整块只有一圈 `squircleClip(16dp)`（把各行的 ripple 裁成卡片形状），
            // **不填色、不加描边**。每行各自可点，行内 16/12 内边距，行间不再加额外间距。
            val isLiuguang = g?.style == AliveStore.STYLE_LIUGUANG
            // 三行共用一块卡片（2026-11-09 用户要求「色值跟『删除锁屏景深』按钮一样」）。
            // 那个按钮是 miuix `Button` 的默认样式，容器色 = `colorScheme.secondaryVariant`
            //（`ButtonDefaults.buttonColors()` 的默认 containerColor）。
            // ⚠️ 不能用 `Card`：`CardDefaults` 的默认色是 `surface`，与浮页底色几乎相同 ⇒ 看不出卡片。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // 不加 horizontal 内边距：要和上面卡片流的第一张卡左对齐
                    .padding(top = 12.dp)
                    .squircleClip(ALIVE_CARD_RADIUS)
                    .background(MiuixTheme.colorScheme.secondaryVariant),
            ) {
                // ① 息屏样式：下拉，无 summary
                AliveStyleRow(
                    title = "息屏样式",
                    options = ALIVE_STYLE_LABELS,
                    selectedIndex = g?.style ?: AliveStore.STYLE_QIHANG,
                    enabled = idle,
                    onSelect = { index ->
                        haptic()
                        val d = draft ?: return@AliveStyleRow
                        val next = d.setStyle(index)
                        working = next
                        // 样式变了预览必须重算（球 ↔ 相片）
                        scope.launch { regenerateAod(next) }
                    },
                )

                // ② AOD 与锁屏一致：只在「留光」下可点，默认开
                val aodRowEnabled = idle && isLiuguang
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = aodRowEnabled) {
                            haptic()
                            val d = draft ?: return@clickable
                            val cur = working ?: return@clickable
                            val next = d.setAodFollowLock(!cur.aodFollowLock)
                            working = next
                            scope.launch { regenerateAod(next) }
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "AOD与锁屏一致",
                                style = MiuixTheme.textStyles.headline1,
                                // 不可点时标题跟着变淡（2026-11-09 用户要求）——
                                // 只让开关置灰的话，文字看着还能点
                                color = if (aodRowEnabled) {
                                    Color.Unspecified
                                } else {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                },
                            )
                            Text(
                                text = "仅在留光样式生效",
                                style = MiuixTheme.textStyles.body2,
                                // ⚠️ **不要 `copy(alpha = 1f)`**（2026-11-10 用户对比 miuix
                                //    标准 `SwitchRow` 发现）：`onSurfaceVariantSummary` 本身就是
                                //    约 60% 的黑，抬到 1 就变成**纯黑**、和标题一样。
                                //    不可点时用 `disabledOnSurface`（比 summary 更淡）。
                                color = if (aodRowEnabled) {
                                    MiuixTheme.colorScheme.onSurfaceVariantSummary
                                } else {
                                    MiuixTheme.colorScheme.disabledOnSurface
                                },
                            )
                        }
                        Switch(
                            checked = g?.aodFollowLock != false,
                            onCheckedChange = { on ->
                                haptic()
                                val d = draft ?: return@Switch
                                val next = d.setAodFollowLock(on)
                                working = next
                                scope.launch { regenerateAod(next) }
                            },
                            enabled = aodRowEnabled,
                        )
                    }
                }

                // ③ 桌面与锁屏一致
                // 整行可点：点文字 / 点空白 / 点开关都是同一个意思
                //（开关自己的 `onCheckedChange` 也会被触发，但它会先吃掉这次点击，不会双翻转）
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = idle) {
                            haptic()
                            val d = draft ?: return@clickable
                            val cur = working ?: return@clickable
                            working = d.setFollowLock(!cur.followLock)
                        },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "桌面与锁屏一致",
                            style = MiuixTheme.textStyles.headline1,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(
                            checked = g?.followLock == true,
                            onCheckedChange = { on ->
                                haptic()
                                val d = draft ?: return@Switch
                                working = d.setFollowLock(on)
                            },
                            enabled = idle,
                        )
                    }
                }
            }

            // ── 景深按钮（照光栅：常规底 + 彩色字，各占一半）──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                AliveDepthButton(
                    text = "计算锁屏景深",
                    tint = MiuixTheme.colorScheme.primary,
                    enabled = idle && hasLock && !hasSubject,
                    running = runningTask == "compute",
                    modifier = Modifier.weight(1f),
                    onClick = { computeDepth() },
                )
                AliveDepthButton(
                    text = "删除锁屏景深",
                    tint = MiuixTheme.colorScheme.error,
                    enabled = idle && hasSubject,
                    running = runningTask == "delete",
                    modifier = Modifier.weight(1f),
                    onClick = { deleteDepth() },
                )
            }

            // ── 遮罩 / 主体 ──
            TextButton(
                text = when {
                    maskBusy -> "处理中…"
                    // 遮罩导入后合成的是**主体**（lockSubject），所以按它判「更换」
                    g?.lockSubject != null -> "更换锁屏的遮罩或主体"
                    else -> "导入锁屏的遮罩或主体"
                },
                onClick = {
                    haptic()
                    maskPicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                enabled = idle && hasLock,
                colors = ButtonDefaults.textButtonColorsPrimary(),
            )
        }

    }
}

/**
 * 「息屏样式」那一行（2026-11-09）。
 *
 * 入口是**下拉的样子**（标题 + 当前值 + 箭头），点开用 [WindowDialog] 列选项；
 * 弹窗样式照 `RestartDialog`（「选择要重启的应用」），选项左边带一张默认预览图。
 *
 * ⚠️ 为什么不用 miuix 的 `OverlayListPopup` / `DropdownRow`：它们的弹层是**独立 Popup 窗口**，
 * 而浮页 `WindowBottomSheet` 本身也是一个高层的 WindowManager 窗口 —— 层级压不过去，
 * 表现就是「点了没反应」或「弹出来埋在浮层下面」。浮页里唯一能盖在上面的只有 `WindowDialog`。
 */
@Composable
private fun AliveStyleRow(
    title: String,
    options: List<String>,
    selectedIndex: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    var pickerOpen by remember { mutableStateOf(false) }
    val haptic = rememberClickHaptic()
    val actionColor = MiuixTheme.colorScheme.onSurfaceVariantActions

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) {
                haptic()
                pickerOpen = true
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MiuixTheme.textStyles.headline1,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = options.getOrElse(selectedIndex) { "" },
            fontSize = MiuixTheme.textStyles.body2.fontSize,
            color = if (enabled) actionColor else actionColor.copy(alpha = 0.4f),
            modifier = Modifier.padding(end = 8.dp),
        )
        DropdownArrowEndAction(
            actionColor = if (enabled) actionColor else actionColor.copy(alpha = 0.4f),
        )
    }

    WindowDialog(
        title = title,
        show = pickerOpen,
        onDismissRequest = { pickerOpen = false },
    ) {
        val dismissState = LocalDismissState.current
        val primary = MiuixTheme.colorScheme.primary
        Column(Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, label ->
                val selected = index == selectedIndex
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            // 选项也要有振动（2026-11-09 用户要求）——
                            // 之前在弹窗里点选项是「静默」的，手感与其它地方不一致
                            haptic()
                            onSelect(index)
                            pickerOpen = false
                            dismissState?.invoke()
                        }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 左边的默认预览图（512×512，圆角在这里裁）
                    Image(
                        painter = painterResource(
                            if (index == AliveStore.STYLE_LIUGUANG) {
                                R.drawable.alive_style_liuguang
                            } else {
                                R.drawable.alive_style_qihang
                            },
                        ),
                        contentDescription = label,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(56.dp)
                            // 平滑圆角（squircle），与卡片同一套，不用 RoundedCornerShape
                            .squircleClip(12.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Text(
                        text = label,
                        style = MiuixTheme.textStyles.main,
                        color = if (selected) primary else Color.Unspecified,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        Text(
                            text = "✓",
                            style = MiuixTheme.textStyles.main,
                            color = primary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 一个槽位：有图显示封面，没图显示「新建」卡；卡片下方 8dp 是标题。
 *
 * 与光栅的 `SlotCard` 的区别：**没有选中描边、没有删除钮、没有拖拽** ——
 * 用户 2026-11-08 明确「不用加选中与换序」。
 */
/**
 * 一个槽位：有图显示封面，没图显示「新建」卡；卡片下方 8dp 是**居中**的标题。
 *
 * 卡宽 / 卡高由调用方按光栅浮页那套算好传进来（`屏宽 × 0.45 × 0.8`、高度按屏幕比例），
 * 保证三张与光栅的槽位一模一样大。
 *
 * 与光栅 `SlotCard` 的区别：**没有选中描边、没有删除钮、没有拖拽** ——
 * 用户 2026-11-08 明确「不用加选中与换序」。
 */
@Composable
private fun AliveSlot(
    title: String,
    bitmap: android.graphics.Bitmap?,
    enabled: Boolean,
    width: Dp,
    height: Dp,
    busy: Boolean = false,
    /** 有图时右上角出现删除钮（照光栅 `PickCard` 的 `RemoveBadge`）；null = 这个槽不能删。 */
    onRemove: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    // 徽标淡出（点击后 150ms 才真的清数据，让缩放/淡出播完 —— 与光栅同一条动画）
    var removing by remember(bitmap) { mutableStateOf(false) }
    val badge by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (removing) 0f else 1f,
        animationSpec = androidx.compose.animation.core.tween(BADGE_FADE_MS),
        label = "aliveBadge",
    )
    Column(
        modifier = Modifier.width(width),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // ⚠️ **外层不裁**（照光栅 `PickCard` 的注释）：删除钮用 `offset` 探出卡角，
        //    `squircleClip` 必须留在内层，否则钮探出去的那半颗会被一起裁成半圆。
        Box(modifier = Modifier.size(width, height)) {
            Box(
                modifier = Modifier
                    .size(width, height)
                    .squircleClip(ALIVE_CARD_RADIUS)
                    // 内描边：与光栅 `SlotCard` 同一条（1dp 纯黑 alpha 0.1，画在内容之上）。
                    // `squircleStroke` 必须在 `squircleClip` 之后 —— 它用 `onDrawWithContent`
                    // 先画内容再描边，所以图片铺满时也盖不掉（2026-11-09 用户要求补齐）。
                    //
                    // ⚠️ **空槽（「新建」卡）不描边**（2026-11-09 用户要求，照光栅 `NewCard`）：
                    //    加号卡本身就是一块实心底，描边只会多出一圈轮廓；
                    //    有图才需要描边把边界从那块底色上勾出来。
                    .then(
                        if (bitmap != null) {
                            Modifier.squircleStroke(
                                { CARD_STROKE_WIDTH }, { CARD_STROKE }, ALIVE_CARD_RADIUS,
                            )
                        } else {
                            Modifier
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // 换图走 **150ms 叠化**（2026-11-08 用户要求「交叉过渡」）：
                // 直接换 bitmap 是硬切，而卡里经常变（导入、算完 AOD 预览、删除）。
                // 用 `Crossfade` 而不是光栅 `CardImage` 的「新图淡入」——
                // 那个旧图当帧就没了，只有新图从 0 抬到 1，不是真正的交叉。
                Crossfade(
                    targetState = bitmap,
                    animationSpec = androidx.compose.animation.core.tween(COVER_FADE_MS),
                    label = "aliveSlot",
                ) { bmp ->
                    if (bmp == null) {
                        NewCard(
                            cardW = width,
                            cardH = height,
                            radius = ALIVE_CARD_RADIUS,
                            modifier = Modifier,
                            surface = rememberCardSurface(),
                            enabled = enabled,
                            // 不可点（AOD 跟随锁屏 / 桌面跟着锁屏）时把加号淡出
                            hideAddWhenDisabled = true,
                            onClick = onClick,
                        )
                    } else {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(width, height),
                        )
                    }
                }
                if (busy) {
                    // 只压一层暗（不再写「生成中…」文案 —— 2026-11-09 用户要求去掉）
                    Box(
                        modifier = Modifier
                            .size(width, height)
                            .background(Color.Black.copy(alpha = 0.35f)),
                    )
                }
            }
            // 删除钮：右上角、圆心落在卡片 1/4 处（与光栅 `CARD_BADGE_CENTER_RATIO` 一致），
            // 缩放 0.75→1 + 淡入淡出走 150ms。**有图就常驻**（Alive 没有选中态）。
            if (onRemove != null && badge > 0.01f) {
                val d = width * DELETE_CIRCLE_RATIO
                RemoveBadge(
                    diameter = d,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .zIndex(1f)
                        .offset(
                            x = d * CARD_BADGE_CENTER_RATIO,
                            y = -d * CARD_BADGE_CENTER_RATIO,
                        )
                        .graphicsLayer {
                            val sc = BADGE_FROM_SCALE + (1f - BADGE_FROM_SCALE) * badge
                            scaleX = sc
                            scaleY = sc
                            alpha = badge
                        },
                    onClick = {
                        if (removing) return@RemoveBadge
                        removing = true
                        scope.launch {
                            kotlinx.coroutines.delay(BADGE_FADE_MS.toLong())
                            onRemove()
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = MiuixTheme.textStyles.body2,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** 景深按钮：常规按钮底 + 彩色字（与光栅 `DepthButton` 逐字一致）。 */
@Composable
private fun AliveDepthButton(
    text: String,
    tint: Color,
    enabled: Boolean,
    running: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(contentColor = tint),
    ) {
        Text(
            text = if (running) "处理中…" else text,
            style = MiuixTheme.textStyles.button,
            maxLines = 1,
        )
    }
}

private enum class AliveIconKind { Close, Check }

/** 顶栏 ✕ / ✓（自绘，与光栅 `SheetActionIcon` 同一条，保证笔画粗细一致）。 */
@Composable
private fun AliveSheetIcon(kind: AliveIconKind, contentDescription: String) {
    val tint = MiuixTheme.colorScheme.onSurface
    Canvas(
        modifier = Modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val stroke = 2.dp.toPx()
        when (kind) {
            AliveIconKind.Close -> {
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.22f, size.height * 0.22f),
                    end = Offset(size.width * 0.78f, size.height * 0.78f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = tint,
                    start = Offset(size.width * 0.78f, size.height * 0.22f),
                    end = Offset(size.width * 0.22f, size.height * 0.78f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round,
                )
            }
            AliveIconKind.Check -> {
                val path = Path().apply {
                    moveTo(size.width * 0.20f, size.height * 0.54f)
                    lineTo(size.width * 0.40f, size.height * 0.74f)
                    lineTo(size.width * 0.80f, size.height * 0.28f)
                }
                drawPath(
                    path = path,
                    color = tint,
                    style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}
