package com.frederic22.hyperextreme.ui

import android.app.WallpaperManager
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.frederic22.hyperextreme.data.AliveControl
import com.frederic22.hyperextreme.data.AodState
import com.frederic22.hyperextreme.data.AliveFlag
import com.frederic22.hyperextreme.data.AliveItem
import com.frederic22.hyperextreme.data.AliveStore
import com.frederic22.hyperextreme.data.MaskPrefs
import com.frederic22.hyperextreme.data.RootShell
import com.frederic22.hyperextreme.data.RunLog
import com.frederic22.hyperextreme.data.WallpaperVersion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.BasicComponentColors
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

/**
 * 二级页「Alive壁纸」（2026-11-07）。
 *
 * 只做 openalive 照片版「启航」：照片 → 透镜小球，AOD 自转，锁屏 / 桌面铺满原图。
 *
 * ## 页面结构（四块，自上而下）
 *
 * 1. **卡片流** —— 与光栅同一套 [CoverCarousel]，一张卡 = 一张照片，最左边是「新建」；
 * 2. **主按钮** —— 三态（无服务模式：`新图片` / `应用` / `取消应用`）；
 * 3. **参数占位卡** —— 用户 2026-11-07 明确「下面那些选项就改成一个卡片，内容占位就行」；
 * 4. **清除所有搭配** —— 红字危险卡 + 二次确认 + 卡片流清除动画（照光栅）。
 *
 * ## 和光栅的差别
 *
 * * 没有编辑浮页（一张图没什么可编的），点卡只选中；
 * * 没有挂载模式（AOD 动画只能挂 SystemUI 的 leash，服务模式做不到）；
 * * 没有生效范围 / 锁屏景深 / 角度这些参数 —— 全部进占位卡。
 *
 * @author 22Frederic
 */
@Composable
internal fun AliveWallpaperDetail(state: MaskUiState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptic = rememberClickHaptic()

    var items by remember { mutableStateOf<List<AliveItem>>(emptyList()) }
    var reload by remember { mutableIntStateOf(0) }
    var loaded by remember { mutableStateOf(false) }
    var located by remember { mutableStateOf(false) }
    var focused by remember { mutableIntStateOf(0) }

    var pendingDelete by remember { mutableStateOf<AliveItem?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var shiftDir by remember { mutableFloatStateOf(0f) }
    var shiftFromPage by remember { mutableIntStateOf(-1) }
    var shiftToken by remember { mutableIntStateOf(0) }
    var clearSeq by remember { mutableIntStateOf(0) }

    var showClearAll by remember { mutableStateOf(false) }
    var showConflict by remember { mutableStateOf(false) }
    var conflictOther by remember { mutableStateOf<RunningWallpaper?>(null) }
    var busy by remember { mutableStateOf(false) }
    // 「壁纸重置提示」（2026-11-10）：pendingApply = 等确认的那张卡；
    // resetting = 重置流程在跑（挡住重复点击）。
    var showWallpaperReset by remember { mutableStateOf(false) }
    var pendingApply by remember { mutableStateOf<AliveItem?>(null) }
    var resetting by remember { mutableStateOf(false) }

    val prefs = remember { MaskPrefs(ctx) }
    var activeId by remember { mutableStateOf(prefs.aliveActiveId) }

    // 编辑浮页（2026-11-08）：新建卡 → 建空组 → 开浮页；点已有卡 → 开浮页
    var sheetOpen by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<AliveItem?>(null) }
    /** 当前这一次是「新建」—— 取消（或空着关）要把整组删掉。 */
    var creating by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        val list = withContext(Dispatchers.IO) { AliveStore.list(ctx, prefs.aliveGroupOrder) }
        // 指向的卡已经不在了（被删 / 目录损坏）⇒ 清指针并关渲染器，不留幽灵状态
        var active = prefs.aliveActiveId
        if (active.isNotEmpty() && list.none { it.id == active }) {
            prefs.setAliveActiveId(null)
            prefs.setAliveWallpaperFp("")
            AliveFlag.push(false, null)
            active = ""
            activeId = ""
        }
        items = list
        if (!located) {
            located = true
            focused = if (list.isEmpty()) 0 else 1
        }
        loaded = true
    }

    LaunchedEffect(items.size) {
        if (focused > items.size) focused = items.size
    }

    LaunchedEffect(items, deletingId) {
        if (deletingId != null && items.none { it.id == deletingId }) deletingId = null
    }

    LaunchedEffect(loaded, items, activeId) {
        if (loaded && activeId.isNotEmpty() && items.none { it.id == activeId }) {
            prefs.setAliveActiveId(null)
            activeId = ""
        }
    }

    val selected = items.getOrNull(focused - 1)

    /**
     * 打开浮页。`item == null` = 新建（先建一个空组，取消时再删）。
     *
     * 与光栅同一套入口：新建卡与编辑卡走同一个浮页，列表为空时只是没有行。
     */
    fun openSheet(item: AliveItem?) {
        if (!state.requireActivated()) return
        // **正在应用的那张不能编辑**（照光栅 `RasterWallpaperPage` 的语义）：
        // 要改它得先「取消应用」，否则改到一半渲染器还在读旧图。
        // 光栅在这里是要 toast 的（「正在应用的组合无法编辑」），别静默 ——
        // 静默会让人以为卡坏了（2026-11-08 用户反馈漏抄）。
        if (item != null && item.id == activeId) {
            Toast.makeText(ctx, "正在应用的壁纸无法编辑", Toast.LENGTH_SHORT).show()
            return
        }
        haptic()
        editing = if (item == null) AliveStore.create(ctx) else item
        creating = item == null
        sheetOpen = true
    }

    fun apply(item: AliveItem) {
        if (!state.requireActivated()) return
        // **AOD 没开就别应用**（2026-11-10 用户要求）：Alive 的两种样式（启航 / 留光）
        // 都是给息屏显示做的，AOD 关着应用上去等于什么都没发生，白让用户以为坏了。
        // 判据 = 系统「息屏显示」总开关（`doze_always_on`，见 [AodState]）。
        if (!AodState.enabled(ctx)) {
            Toast.makeText(ctx, "AOD未开启，请先开启AOD再使用", Toast.LENGTH_LONG).show()
            return
        }
        val other = WallpaperConflict.running(ctx, state, WallpaperChain.ALIVE)
        if (other != null) {
            conflictOther = other
            showConflict = true
            return
        }
        // **壁纸重置提示**（2026-11-10 用户要求）：无条件弹，确认并把壁纸重置成
        // 系统默认之后才真正应用。原因见 [WallpaperResetDialog] 的类注释 ——
        // 不干净的壁纸组合会让解锁动画寄生在「解锁即销毁」的锁屏壁纸窗口上，
        // 或者让两条 leash 换层时露出系统壁纸。
        pendingApply = item
        showWallpaperReset = true
    }

    /**
     * 真正应用（原 [apply] 的主体）—— 由 [WallpaperResetDialog] 的「继续」在
     * 壁纸重置完成之后调用。
     */
    fun applyNow(item: AliveItem) {
        haptic()
        prefs.setAliveActiveId(item.id)
        activeId = item.id
        // 应用即置顶，但只写顺序、不动当前列表（与光栅一致）
        prefs.setAliveGroupOrder(listOf(item.id) + prefs.aliveGroupOrder.filterNot { it == item.id })
        // 景深主体要不要一起推：SystemUI 靠它决定 `keyguard_foreground_layer`
        // 是「保留并换系统主体」还是「整层压掉」（2026-11-08）
        // 开关关掉时即使有主体也不推 —— SystemUI 那边会把整层压掉
        // 留光也推 depth（2026-11-09 用户要求接景深）：主体层的几何由 `AliveOverlay` 的
        // `uPaperMode` 分支按**相纸映射**算（不是球），alpha 用相纸缩放进度控制，
        // AOD 稳态严格 0 ⇒ 不会再有「AOD 里一块人形残影」。
        //
        // ⚠️ 早先这里写着「留光不推 depth」，那是因为当时主体层恒 0（根本不推帧），
        //    保留 `keyguard_foreground_layer` 只会把系统抠图留在屏幕上。现在推帧了。
        val hasDepth = prefs.aliveLockDepth &&
            (item.lockSubject != null || item.lockMask != null)
        AliveFlag.push(
            true, item.id, depth = hasDepth, style = item.style,
            // 换图不改 id ⇒ 必须带上图片版本，否则渲染器不重启、新图进不了 GL
            ver = item.imageVer,
        )
        // 记「壁纸基准」：aod 侧换壁纸检测拿它当比对值
        val app = ctx.applicationContext ?: ctx
        Thread {
            runCatching {
                val version = WallpaperVersion.compute(app)
                if (version.usable) prefs.setAliveWallpaperFp(version.value)
            }
        }.apply { isDaemon = true }.start()
        Toast.makeText(ctx, "Alive壁纸已生效", Toast.LENGTH_LONG).show()
    }

    fun clearActive() {
        if (activeId.isEmpty()) return
        haptic()
        AliveControl.disable(ctx, "Alive壁纸已关闭")
        activeId = ""
    }

    fun deleteItem(item: AliveItem) {
        pendingDelete = null
        if (deletingId != null) return
        deletingId = item.id
    }

    fun commitDelete() {
        val id = deletingId ?: return
        val idx = items.indexOfFirst { it.id == id }
        if (idx < 0) return
        val item = items[idx]
        val page = idx + 1
        val lastPage = items.size
        shiftDir = if (page < lastPage) 1f else -1f
        shiftFromPage = page
        shiftToken++
        items = items.filterNot { it.id == id }
        prefs.setAliveGroupOrder(prefs.aliveGroupOrder.filterNot { it == id })
        deletingId = null
        scope.launch {
            withContext(Dispatchers.IO) { AliveStore.delete(item) }
        }
    }

    fun commitClearAll() {
        activeId = ""
        focused = 0
        items = emptyList()
        prefs.setAliveGroupOrder(emptyList())
        prefs.setAliveActiveId(null)
        scope.launch {
            val n = withContext(Dispatchers.IO) { AliveStore.deleteAll(ctx) }
            reload++
            Toast.makeText(ctx, "已清除 $n 项搭配", Toast.LENGTH_SHORT).show()
        }
    }

    val screen = rememberScreenSizePx()
    val density = LocalDensity.current
    val cardWidthDp: Dp = with(density) { (screen.width * CARD_WIDTH_RATIO).toDp() }
    val buttonWidth: Dp = cardWidthDp * 5f / 6f
    val pagerPadding = pagerVPadding(cardWidthDp)
    val carouselHeight: Dp = with(density) {
        (screen.width * CARD_WIDTH_RATIO * screen.height / screen.width).toDp()
    } + pagerPadding * 2

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberPageScroll())
            .padding(top = LocalTopBarHeight.current, bottom = 32.dp),
    ) {
        Spacer(Modifier.height(BLOCK_GAP))

        if (loaded) {
            CoverCarousel(
                items = items.map { CoverItem(it.id, it.coverFile) },
                initialPage = if (items.isEmpty()) 0 else 1,
                activeId = activeId,
                deletingId = deletingId,
                shiftDir = shiftDir,
                shiftFromPage = shiftFromPage,
                shiftToken = shiftToken,
                clearToken = clearSeq,
                screen = screen,
                onFocus = { focused = it },
                onNew = { openSheet(null) },
                // 点已有卡 → 编辑浮页（2026-11-08）。侧卡本来就无响应，由组件保证。
                onClick = { item ->
                    items.firstOrNull { it.id == item.id }?.let { openSheet(it) }
                },
                onDelete = { item ->
                    val g = items.firstOrNull { it.id == item.id }
                    when {
                        g == null -> Unit
                        g.id == activeId ->
                            Toast.makeText(ctx, "请先取消应用", Toast.LENGTH_SHORT).show()
                        else -> {
                            haptic()
                            pendingDelete = g
                        }
                    }
                },
                onDeleteSettled = { commitDelete() },
                onClearSettled = { commitClearAll() },
            )
        } else {
            Spacer(Modifier.height(carouselHeight))
        }

        Spacer(Modifier.height(BLOCK_GAP - pagerPadding))

        // 主按钮：三态（没有服务模式）
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val isActive = selected != null && selected.id == activeId
            val label = when {
                selected == null -> "新图片"
                isActive -> "取消应用"
                else -> "应用"
            }
            val bg by animateColorAsState(
                targetValue = if (isActive) {
                    MiuixTheme.colorScheme.surfaceContainerHigh
                } else {
                    MiuixTheme.colorScheme.primary
                },
                animationSpec = tween(BUTTON_FADE_MS),
            )
            val fg by animateColorAsState(
                targetValue = if (isActive) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onPrimary
                },
                animationSpec = tween(BUTTON_FADE_MS),
            )
            Button(
                onClick = {
                    val g = selected
                    when {
                        g == null -> openSheet(null)
                        g.id == activeId -> clearActive()
                        else -> apply(g)
                    }
                },
                enabled = !busy,
                modifier = Modifier.width(buttonWidth),
                colors = ButtonDefaults.buttonColorsPrimary(
                    color = bg,
                    disabledColor = bg,
                    contentColor = fg,
                    disabledContentColor = fg,
                ),
            ) {
                Crossfade(targetState = label, animationSpec = tween(BUTTON_FADE_MS)) { text ->
                    Text(
                        text = text,
                        style = MiuixTheme.textStyles.button,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(Modifier.height(BLOCK_GAP))

        // 「启用 Alive 壁纸锁屏景深」（2026-11-08 用户要求：照光栅那项抄过来，取代参数占位卡）。
        // 语义完全一致：开 = 保留 `keyguard_foreground_layer` 并把我们的主体换上去；
        // 关 = 整层压掉（旧壁纸的人像不能浮在新图上）。
        // Alive 只有 LSPosed 一条链 ⇒ 不存在"服务模式置灰"那档。
        var lockDepth by remember { mutableStateOf(prefs.aliveLockDepth) }
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            SwitchRow(
                title = "启用 Alive 壁纸锁屏景深",
                checked = lockDepth,
                onCheckedChange = { on ->
                    lockDepth = on
                    prefs.setAliveLockDepth(on)
                    // 与光栅同一条提示：生效中改开关要等下一次「应用」才下发。
                    if (activeId.isNotEmpty()) {
                        Toast.makeText(
                            ctx,
                            "应用范围将在下一次应用时生效",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                },
            )
        }

        // 「清除所有搭配」独立成一张卡 + 语义红 + 无箭头（照光栅 / 空间）
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp)
                .padding(top = 12.dp),
            insideMargin = PaddingValues(0.dp),
        ) {
            BasicComponent(
                title = "清除所有搭配",
                summary = null,
                titleColor = BasicComponentColors(
                    color = MiuixTheme.colorScheme.error,
                    disabledColor = MiuixTheme.colorScheme.disabledOnSurface,
                ),
                onClick = {
                    haptic()
                    showClearAll = true
                },
            )
        }
    }

    // 删除确认（常驻组合树、用 show 控制 —— 条件包裹会让 miuix 的退出动画播不出来）
    WindowDialog(
        title = "删除此搭配？",
        summary = "搭配内的图片缓存将被删除",
        show = pendingDelete != null,
        onDismissRequest = { pendingDelete = null },
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        pendingDelete = null
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "确认",
                    danger = true,
                    onClick = {
                        pendingDelete?.let { deleteItem(it) }
                        dismissState?.invoke()
                    },
                )
            }
        }
    }

    WindowDialog(
        title = "删除所有搭配？",
        summary = if (activeId.isNotEmpty()) {
            "所有搭配内的图片缓存将被删除。当前 Alive壁纸正在运行，点击“清除”后将自动关闭替换。"
        } else {
            "所有搭配内的图片缓存将被删除。"
        },
        show = showClearAll,
        onDismissRequest = { showClearAll = false },
    ) {
        val dismissState = LocalDismissState.current
        Column(modifier = Modifier.fillMaxWidth()) {
            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButtonLike(
                    text = "取消",
                    onClick = {
                        showClearAll = false
                        dismissState?.invoke()
                    },
                )
                Spacer(Modifier.width(20.dp))
                TextButtonLike(
                    text = "清除",
                    danger = true,
                    onClick = {
                        showClearAll = false
                        scope.launch {
                            if (activeId.isNotEmpty()) {
                                withContext(Dispatchers.IO) { AliveControl.disable(ctx, null) }
                            }
                            // 剩下的（清列表 + 删盘 + toast）交给卡片流：所有非新建卡淡出后回调
                            clearSeq++
                        }
                        dismissState?.invoke()
                    },
                )
            }
        }
    }

    // 编辑浮页（常驻组合树、用 show 控制 —— 条件包起来 miuix 来不及播退出动画）。
    AliveGroupSheet(
        show = sheetOpen,
        group = editing,
        isNew = creating,
        onDismiss = {
            // 空组清理**交给浮页**（`AliveGroupSheet.close()`）—— 页面手里的 `editing`
            // 是打开时的快照，浮页里导入的图它看不到，按它判空会把刚导入的整组删掉
            // （2026-11-08 真机 bug：点 ✓ 不保存）。这里只负责重扫。
            //
            // ⚠️ **不能在关闭时重排顺序**（2026-11-09 用户报「只要点开编辑浮页，那张卡就跳到
            //    第一个」）：原来这里写 `setAliveGroupOrder(listOf(id) + …)`，等于每次编辑完
            //    都把这张卡置顶。光栅的 `onDismiss` 只做「关浮页 + 重扫」，**不重排**
            //    （置顶只发生在「应用」那一步）。而新建组本来就排在前面：
            //    `AliveStore.list` 无显式顺序时按 `createdAt` 倒序。
            sheetOpen = false
            editing = null
            creating = false
            reload++
        },
    )

    // 功能冲突弹窗（常驻组合树、用 show 控制）。「关闭」只关对方、不继续本次应用。
    FeatureConflictDialog(
        show = showConflict,
        summary = conflictOther?.let { WallpaperConflict.summary(it, WallpaperChain.ALIVE) } ?: "",
        onDismissRequest = {
            showConflict = false
            conflictOther = null
        },
        onConfirmClose = {
            val other = conflictOther
            showConflict = false
            conflictOther = null
            if (other != null) scope.launch { WallpaperConflict.disable(ctx, state, other) }
        },
    )

    // 壁纸重置提示（2026-11-10，用户要求）：无条件弹，确认后先把壁纸重置成系统默认，
    // 等壁纸服务把壁纸层重建完，再走 [applyNow] 的原流程。
    WallpaperResetDialog(
        show = showWallpaperReset,
        onDismissRequest = {
            showWallpaperReset = false
            pendingApply = null
        },
        onConfirm = {
            showWallpaperReset = false
            val target = pendingApply
            pendingApply = null
            if (target != null && !resetting) {
                resetting = true
                // 重置壁纸 + 重建壁纸层要好几秒，先给个反馈（2026-11-10 用户要求）
                Toast.makeText(ctx, "正在应用请稍后", Toast.LENGTH_LONG).show()
                Thread {
                    val ok = runCatching {
                        // 1) 先停渲染器：它正攥着即将被销毁的壁纸 leash
                        if (prefs.aliveActiveId.isNotEmpty()) AliveControl.disable(ctx, null)
                        // 2) 回到**系统默认**壁纸 —— 与服务模式「恢复默认」同一条路。
                        //    只清 FLAG_LOCK 会保留当前系统壁纸，与「重置到系统默认」不符
                        //    （2026-11-10 用户确认用 both）。
                        WallpaperManager.getInstance(ctx).clear(
                            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK,
                        )
                        // 3) 等壁纸服务重建壁纸层：不重建的话旧 `he-alive-*` 会残留
                        Thread.sleep(1200L)
                        RootShell.run("killall com.miui.miwallpaper")
                        Thread.sleep(1800L)
                    }.isSuccess
                    if (!ok) {
                        RunLog.append(
                            ctx,
                            "壁纸重置",
                            listOf("clear 或 killall 失败，照常继续应用"),
                            ok = false,
                        )
                    }
                    Handler(Looper.getMainLooper()).post {
                        resetting = false
                        applyNow(target)
                    }
                }.apply { isDaemon = true }.start()
            }
        },
    )
}
