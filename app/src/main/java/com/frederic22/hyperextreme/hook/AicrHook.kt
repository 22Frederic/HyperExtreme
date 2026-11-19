package com.frederic22.hyperextreme.hook

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.ResultReceiver
import com.frederic22.hyperextreme.aicr.AicrProtocol
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedModule
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AICR（小米澎湃 AI 引擎）**服务端兜底** —— 测试版（2026-11-05）。
 *
 * ## 为什么要有它
 *
 * 光栅壁纸的「景深主体」走 `AicrVision.segmentFile()` 直连 `com.xiaomi.aicr`。
 * 真机反馈里有设备返回 `getPluginBinder 返回空（Status=-1）`，而服务端反编译
 * （`analyse/re-glass/out/aiengine`）里**每条失败分支都会 `ret.putInt("Status", …)`**
 * ⇒ 只可能是三种情况之一：
 *
 * 1. 返回的 Bundle 根本没写回来（`onTransact` 顺序/版本不一致）；
 * 2. `mPlugInManager == null`（服务没就绪）；
 * 3. 插件没注册 / 版本对不上（`f27.b(desc)` / `f27.e(desc)`）。
 *
 * 本 hook 挂在服务端方法上，**只做两件事**：
 *
 * * **诊断**：把 `ret` 的 keySet/值、`mPlugInManager` 是否为 null、插件注册与否、
 *   插件版本全打进 LSPosed 日志 —— 一看就知道断在哪一条；
 * * **兜底**：`proceed` 后如果 binder 为 null 或 Status != 0，反射插件管理器
 *   （`AiCrCoreService#mPlugInManager`，类型 `f27`）直接 `a(desc, address)` 取 binder，
 *   写回 `Status = 0` 并返回。
 *
 * ## 挂点为什么有两个
 *
 * AIDL 的实现是 `AiCrCoreService` 里的**匿名内部类**（`new IAiCrCoreService.Stub() {…}`）。
 * 真机第一次装上来时 `declaredClasses` 里**没找到**它（2026-11-05 20:38 日志），
 * 所以再加一条兜底：hook `Service#onCreate`，从 `mBinder` 字段拿实例、
 * 再 hook 它的 `getPluginBinder`。两条路谁先成功用谁，方法级去重。
 *
 * ## v1.17.6（2026-11-05 深夜）：AICR 4.13.8 加了调用方签名白名单
 *
 * 新版本在 `onTransact` 门口加了 `AccessGuardUtils`：第三方应用直接 `return false`，
 * reply 全空 ⇒ 客户端读到 `binder=null / Status=-1`，而 `getPluginBinder` **根本不被调用**
 * （所以本 hook 的诊断日志一条都不会有）。绕法见 [hookOnTransact]：自实现 case 1 分发。
 */
internal object AicrHook {

    /** 服务实现类（匿名 Stub 的宿主）。 */
    private const val SERVICE = "com.xiaomi.aicr.access.AiCrCoreService"

    private const val METHOD = "getPluginBinder"

    /** `IAiCrCoreService` 的事务码 / 描述符（与客户端 `AicrVision` 同一份口径）。 */
    private const val IFACE_CORE = "com.xiaomi.aicr.IAiCrCoreService"
    private const val CODE_GET_PLUGIN = 1
    private const val CODE_INTERFACE = 1598968902

    /** 方法级去重：同一个 Method 被 hook 两次会抛「already hooked」。 */
    private val hooked = ConcurrentHashMap<String, Boolean>()

    /** `onTransact` 放行日志只打一次（每条 binder 事务都会过它，全打会刷屏）。 */
    private val firstPass = AtomicBoolean(false)

    fun install(module: XposedModule, loader: ClassLoader): Boolean {
        val service = runCatching { Class.forName(SERVICE, false, loader) }.getOrNull()
        if (service == null) {
            log("未找到 $SERVICE（本进程没有这个类，跳过）")
            return false
        }

        var ok = false
        // ① 直接按内部类找（正常情况：匿名 Stub 会出现在 declaredClasses 里）
        val classes = runCatching { service.declaredClasses }.getOrDefault(emptyArray())
        log("declaredClasses(${classes.size}) = ${classes.joinToString { it.simpleName }}")
        for (c in classes) {
            val m = runCatching {
                c.declaredMethods.firstOrNull { it.name == METHOD && it.parameterCount == 5 }
            }.getOrNull() ?: continue
            ok = hookMethod(module, m) || ok
            hookOnTransact(module, c)
        }

        // ② **无条件** hook Service#onCreate（2026-11-19，issue #4）：
        //    ① 保存实例 —— 广播通道要拿它去本地调 mPlugInManager；
        //    ② 注册广播接收器 —— 跨应用 bind 在部分设备上解析不到，改由 AICR 进程内自取；
        //    ③ 顺带保留原来的 mBinder 兜底（直接找内部类没成功时才走）。
        val onCreate = runCatching { service.getDeclaredMethod("onCreate") }.getOrNull()
        if (onCreate == null) {
            log("兜底失败：$SERVICE 没有 onCreate")
            runCatching { ensureReceiver() }
            return ok
        }
        onCreate.isAccessible = true
        runCatching {
            module.hook(onCreate).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    val result = chain.proceed()
                    val svc = chain.getThisObject()
                    coreService = svc
                    // 包一层：这里是 Service.onCreate 的 hook，任何异常冒出去都会让
                    // AICR 的 coreService 创建失败 ⇒ mPlugInManager 永远为 null。
                    runCatching { ensureReceiver() }
                    // ⚠️ **无条件**补 hook（2026-11-19 修回归）：这里原先写的是 `if (!ok)`，
                    //    而 `ok` 在下方「挂上 onCreate」后就被置 true —— Kotlin 的局部 var
                    //    被闭包捕获会装箱成 Ref，于是本闭包读到的永远是 true，补 hook 一次
                    //    都没执行过。后果：`declaredClasses` 恒为 0 的设备（本机和用户机都是，
                    //    见类注释）上 `getPluginBinder` / `onTransact` 全没挂 ⇒ 被 AccessGuard
                    //    拒 ⇒ 空 reply ⇒ `Status=-1`。
                    //    `hookMethod` / `hookOnTransact` 内部都有 `hooked` 去重，服务重建多次
                    //    重复进来也安全。
                    val binder = runCatching {
                        svc?.javaClass?.getDeclaredField("mBinder")
                            ?.apply { isAccessible = true }
                            ?.get(svc)
                    }.getOrNull()
                    if (binder == null) {
                        log("兜底：onCreate 后拿不到 mBinder（字段名变了？）")
                    } else {
                        val m = binder.javaClass.declaredMethods.firstOrNull {
                            it.name == METHOD && it.parameterCount == 5
                        }
                        if (m == null) {
                            log("兜底：mBinder 里没有 $METHOD(5 参数)，类=${binder.javaClass.name}")
                        } else {
                            hookMethod(module, m)
                        }
                        // 访问校验在 onTransact 里（新版 4.13+），必须单独挂
                        hookOnTransact(module, binder.javaClass)
                    }
                    return result
                }
            })
            log("已挂上 $SERVICE#onCreate（实例保存 + 广播注册）")
            ok = true
        }.onFailure { log("挂 onCreate 失败：${it.javaClass.simpleName}: ${it.message}") }
        // 主进程 / 其他子进程里没有 `AiCrCoreService.onCreate` 可挂 ⇒ 这里再试一次
        // （`onPackageReady` 时 Application 可能还没建好，取不到 Context 就等 onCreate 兜底）。
        runCatching { ensureReceiver() }
        return ok
    }

    // ──────────────────────────────────────────────────────────── hook

    private fun hookMethod(module: XposedModule, method: java.lang.reflect.Method): Boolean {
        val key = "${method.declaringClass.name}#${method.name}"
        if (hooked.putIfAbsent(key, true) != null) return true
        return runCatching {
            method.isAccessible = true
            module.hook(method).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // (callback, address, desc, minVersion, ret)
                    val args = chain.getArgs()
                    val address = args.getOrNull(1) as? String
                    val desc = args.getOrNull(2) as? String
                    val minVersion = args.getOrNull(3) as? String
                    val ret = args.getOrNull(4) as? Bundle

                    val result = chain.proceed() as? IBinder
                    val status = ret?.getInt("Status", Int.MIN_VALUE) ?: Int.MIN_VALUE
                    log(
                        "调用：address=$address desc=$desc minVersion=$minVersion " +
                            "→ 常规 binder=${result != null} Status=$status keys=${ret?.keySet()}",
                    )

                    // **hook 侧优先**（2026-11-05 用户定）：proceed 只为让 reply 合法（客户端要 readException），
                    // 返回值一律以 `mPlugInManager` 直取为准 —— 它绕过了版本校验、客户端限流和
                    // Status 分支；拿不到才回落到常规结果。
                    val pm = pluginManager(chain.getThisObject())
                    if (pm == null) {
                        log("mPlugInManager 为 null（服务未就绪）⇒ 回落常规结果")
                        return result
                    }
                    val direct = runCatching { callBinder(pm, desc, address) }.getOrNull()
                    if (direct != null) {
                        runCatching { ret?.putInt("Status", 0) }
                        log(
                            "hook 优先：从 mPlugInManager 取到 binder" +
                                "（常规 binder=${result != null} Status=$status）",
                        )
                        return direct
                    }
                    val registered = runCatching { callRegistered(pm, desc) }.getOrNull()
                    val version = runCatching { callVersion(pm, desc) }.getOrNull()
                    log(
                        "mPlugInManager 拿不到 binder（registered=$registered version=$version）" +
                            "⇒ 回落常规结果",
                    )
                    return result
                }
            })
            log("已挂上 ${method.declaringClass.name}#${method.name}（诊断 + 插件管理兜底）")
            true
        }.getOrElse {
            hooked.remove(key)
            log("挂 ${method.declaringClass.name}#${method.name} 失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }

    /**
     * **绕过新版 AICR 的调用方签名校验**（2026-11-05，AICR 4.13.8 实测）。
     *
     * ## 新版本加了什么
     *
     * `AiCrCoreService$2` 重写了 `onTransact`，所有事务进门先过 `AccessGuardUtils`：
     *
     * ```
     * if (v8.a(this)) return super.onTransact(...)      // 放行
     * Log("onTransact denied for uid: ..."); return false   // 拒绝：reply 一个字节都不写
     * ```
     *
     * 放行条件只有四种：debug/root build、调用方==自己 / uid 1000、包名==com.xiaomi.aicr、
     * 或 **APK 签名 SHA-256 命中内置的 11 条白名单**。我们是第三方签名 ⇒ 必然被拒。
     *
     * 被拒时客户端读到的是**空 reply**：`readException()=0`（不抛）、`readStrongBinder()=null`、
     * `readInt()=0` ⇒ `Status=-1`，正是「getPluginBinder 返回空（Status=-1）」的来源。
     * 而 `getPluginBinder` 根本没被调用，所以挂在它上面的 hook 一条日志也不会有。
     *
     * ## 怎么绕
     *
     * **不能**反射调父类 `Stub#onTransact` —— `Method.invoke` 是**虚分派**，
     * 会重新落回子类的重写 ⇒ 自己调自己，栈溢出（v1.17.6 第一版就这么炸的，
     * 日志里是一串 `onTransact → Method.invoke → intercept → proceed → onTransact`）。
     *
     * 正解：**自己复刻 AIDL 存根的 case 1 分发**。闸门只在外层 `onTransact`，
     * 真正的 `getPluginBinder` 上没有 —— 所以直接读 Parcel、调实现、按原格式写回：
     *
     * ```
     * readStrongBinder → readString ×3 → new Bundle
     * getPluginBinder(cb, address, desc, minVersion, ret)     ← 虚分派，落到实现类
     * writeNoException → writeStrongBinder → writeInt(1) → ret.writeToParcel(reply, 1)
     * ```
     *
     * 其余事务（`isSupport` / `releaseConnect` 等）照常 `proceed()`：我们用不到，
     * 系统应用本来就在白名单里。旧版本没有这个重写 ⇒ `getDeclaredMethod` 找不到 ⇒ 直接跳过。
     */
    private fun hookOnTransact(module: XposedModule, implCls: Class<*>): Boolean {
        val key = "${implCls.name}#onTransact"
        if (hooked.putIfAbsent(key, true) != null) return true
        val params = arrayOf(
            Int::class.javaPrimitiveType!!,
            Parcel::class.java,
            Parcel::class.java,
            Int::class.javaPrimitiveType!!,
        )
        val implMethod = runCatching { implCls.getDeclaredMethod("onTransact", *params) }.getOrNull()
        if (implMethod == null) {
            hooked.remove(key)
            log("${implCls.simpleName} 没重写 onTransact（旧版无访问校验，无需绕过）")
            return false
        }
        // 真正的业务实现：`AiCrCoreService$2#getPluginBinder`（它自己**没有**闸门，闸门只在外层 onTransact）
        val pluginMethod = implCls.declaredMethods.firstOrNull {
            it.name == METHOD && it.parameterCount == 5
        }
        val cbStub = pluginMethod?.let { m ->
            runCatching {
                Class.forName(m.parameterTypes[0].name + "\$Stub", false, implCls.classLoader)
            }.getOrNull()
        }
        val asInterface = cbStub?.let {
            runCatching { it.getMethod("asInterface", IBinder::class.java) }.getOrNull()
        }
        if (pluginMethod == null || asInterface == null) {
            hooked.remove(key)
            log("找不到 $METHOD / ICoreServiceCallback.Stub，跳过 onTransact 绕过")
            return false
        }
        return runCatching {
            implMethod.isAccessible = true
            pluginMethod.isAccessible = true
            module.hook(implMethod).intercept(object : XposedInterface.Hooker {
                override fun intercept(chain: XposedInterface.Chain): Any? {
                    // 取最后 4 个：签名为 (int code, Parcel data, Parcel reply, int flags)，
                    // 万一 getArgs() 把接收者也带进来，末尾四位仍然是参数。
                    val args = chain.getArgs().takeLast(4)
                    val code = args.getOrNull(0) as? Int ?: -1
                    val data = args.getOrNull(1) as? Parcel
                    val reply = args.getOrNull(2) as? Parcel
                    if (data == null || reply == null) return chain.proceed()

                    // `Binder.transact(INTERFACE_TRANSACTION)` —— 原实现也不查闸门，照抄
                    if (code == CODE_INTERFACE) {
                        reply.writeString(IFACE_CORE)
                        return true
                    }
                    // 其余事务（isSupport / releaseConnect 等）照常走原逻辑；
                    // 只有我们自己要用的 getPluginBinder 走下面这段自实现分发。
                    if (code != CODE_GET_PLUGIN) return chain.proceed()

                    data.enforceInterface(IFACE_CORE)
                    val cbRaw = data.readStrongBinder()
                    val address = data.readString()
                    val desc = data.readString()
                    val minVersion = data.readString()
                    val ret = Bundle()
                    val cb = try {
                        asInterface.invoke(null, cbRaw)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException ?: e
                    }
                    val binder = try {
                        // 虚分派落到 AiCrCoreService$2 的实现，再经我们的 getPluginBinder hook
                        // （hook 侧优先：直接找 mPlugInManager 要 binder）
                        pluginMethod.invoke(chain.getThisObject(), cb, address, desc, minVersion, ret)
                    } catch (e: InvocationTargetException) {
                        throw e.targetException ?: e
                    }
                    // 与 AIDL 存根 `Stub.onTransact` 的 case 1 逐字一致：
                    // writeNoException → writeStrongBinder → writeInt(1) → Bundle.writeToParcel(_, 1)
                    reply.writeNoException()
                    reply.writeStrongBinder(binder as? IBinder)
                    reply.writeInt(1)
                    ret.writeToParcel(reply, 1)
                    if (firstPass.compareAndSet(false, true)) {
                        log(
                            "onTransact 自行分发 case 1：跳过 ${implCls.simpleName} 的访问校验" +
                                "（address=$address desc=$desc）",
                        )
                    }
                    return true
                }
            })
            log("已挂上 ${implCls.name}#onTransact（自实现 case 1 分发，绕过访问校验）")
            true
        }.getOrElse {
            hooked.remove(key)
            log("挂 ${implCls.name}#onTransact 失败：${it.javaClass.simpleName}: ${it.message}")
            false
        }
    }


    /**
     * 从服务实现对象里摸出 `mPlugInManager`。
     *
     * 实现类是**匿名内部类**，字段在外部类 `AiCrCoreService` 上 ⇒ 先取 `this$0`。
     * 字段名在系统 app 里通常保留（反编译可见 `mPlugInManager`）；拿不到就按
     * "类型名像插件管理器"兜底，最后再不行返回 null。
     */
    private fun pluginManager(impl: Any?): Any? = runCatching {
        val self = impl ?: return null
        val outer = self.javaClass.declaredFields
            .firstOrNull { it.name == "this\$0" }
            ?.apply { isAccessible = true }
            ?.get(self) ?: self
        val fields = outer.javaClass.declaredFields
        val f = fields.firstOrNull { it.name == "mPlugInManager" }
            ?: fields.firstOrNull { !it.type.isPrimitive && it.type.name.startsWith("com.xiaomi.aicr") }
            ?: return null
        f.isAccessible = true
        f.get(outer)
    }.getOrNull()

    /** `f27.b(String): boolean` —— 插件是否注册。 */
    private fun callRegistered(pm: Any, desc: String?): Boolean? {
        if (desc == null) return null
        val m = pm.javaClass.methods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java &&
                it.returnType == Boolean::class.javaPrimitiveType
        } ?: return null
        return m.invoke(pm, desc) as? Boolean
    }

    /** `f27.e(String): String` —— 插件版本。 */
    private fun callVersion(pm: Any, desc: String?): String? {
        if (desc == null) return null
        val m = pm.javaClass.methods.firstOrNull {
            it.parameterCount == 1 && it.parameterTypes[0] == String::class.java &&
                it.returnType == String::class.java
        } ?: return null
        return m.invoke(pm, desc) as? String
    }

    /** `f27.a(String, String): IBinder` —— 取插件 binder（第二个参数是 address）。 */
    private fun callBinder(pm: Any, desc: String?, address: String?): IBinder? {
        if (desc == null || address == null) return null
        val m = pm.javaClass.methods.firstOrNull {
            it.parameterCount == 2 &&
                it.parameterTypes[0] == String::class.java &&
                it.parameterTypes[1] == String::class.java &&
                IBinder::class.java.isAssignableFrom(it.returnType)
        } ?: return null
        return m.invoke(pm, desc, address) as? IBinder
    }

    // ──────────────────────────────────────────────────────────── 广播通道（2026-11-19，issue #4）

    /**
     * ## 为什么要有这一条
     *
     * 用户设备（K80 Pro / HyperOS 4）上 App 侧 `bindService` **两种 Intent 都返回 false**
     * （显式 ComponentName 也 false），`resolveService` 直接解析不到。但 AICR **自己**调自己
     * 是同包同 uid，不受包可见性 / 组件管控的任何限制。
     *
     * 所以改成：App 发定向广播 → **任意** AICR 进程收到后，用 `bindService` 绑**自己的**
     * service（同包同 uid，必成）→ 走标准 AIDL 抠图 → 结果写回 App 给的 pipe。
     *
     * 注册点选在 `onCreate`（而不是 `install` 当下）：那时进程的 Application 一定就绪，
     * `currentApplication()` 拿得到 Context。
     *
     * ## 协议
     *
     * ```
     * App:  Intent(ACTION_SEGMENT).setPackage("com.xiaomi.aicr")
     *         .putExtra(EXTRA_IN,  原图 fd 只读)
     *         .putExtra(EXTRA_OUT, 结果 pipe 写端)
     * AICR: 收到 → 立刻写 FLAG_ACCEPTED（表示「已接手」）→ 本地 bind 自己 → segmentByFd
     *         → 写 PNG → 关闭写端（App 读到 EOF）
     * ```
     */

    @Volatile private var coreService: Any? = null
    @Volatile private var selfCore: IBinder? = null
    @Volatile private var receiverReady = false

    /** `onPackageReady` 时 Application 还没建好 ⇒ 延迟重试（最多 12 次 ≈ 6 秒）。 */
    private val receiverRetry = java.util.concurrent.atomic.AtomicInteger(0)

    private fun ensureReceiver() {
        if (receiverReady) return
        // 2026-11-19 P2：**不再按进程名过滤**。`Application.getProcessName()` 在
        // `onPackageReady` 这个时机拿到的不是本进程名（真机日志实证：主进程 + coreService +
        // cognitionService + actionprovider + searchService + searchDataService 全注册了），
        // 靠它筛等于没筛。改成「所有进程都注册 + 有序广播由第一个收到的进程
        // `abortBroadcast()` 独占」—— 覆盖最全（主进程被回收也有人接），且只处理一次。
        val app = HookRuntime.appContext()
        if (app == null) {
            // 主进程 / 非 coreService 子进程没有 `AiCrCoreService.onCreate` 可等，
            // 只能等 Application 建好 —— 每个进程独立计数，不会互相拖累。
            if (receiverRetry.getAndIncrement() < 12) {
                runCatching {
                    android.os.Handler(android.os.Looper.getMainLooper())
                        .postDelayed({ ensureReceiver() }, 500L)
                }
            }
            return
        }
        synchronized(this) {
            if (receiverReady) return
            val filter = IntentFilter(AicrProtocol.ACTION_SEGMENT)
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (intent.getStringExtra(AicrProtocol.EXTRA_MAGIC) != AicrProtocol.MAGIC) return
                    // **有序广播：第一个收到的进程独占**（AICR 有 5+ 个进程都注册了接收器，
                    // 不独占的话它们会同时往同一个 pipe 写，数据直接错乱）。
                    // abortBroadcast 必须在 onReceive 里同步调用，之后才轮到 goAsync。
                    runCatching {
                        if (isOrderedBroadcast) {
                            resultCode = 1
                            abortBroadcast()
                        }
                    }
                    val pending = goAsync()
                    Thread({
                        try {
                            handleSegmentBroadcast(context, intent)
                        } catch (t: Throwable) {
                            log("广播：处理异常 ${t.javaClass.simpleName}: ${t.message}")
                        } finally {
                            runCatching { pending.finish() }
                        }
                    }, "hx-aicr-seg").start()
                }
            }
            runCatching {
                app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                receiverReady = true
                log("广播通道已注册（进程=${runCatching { android.app.Application.getProcessName() }.getOrNull() ?: "?"}）")
            }.onFailure { log("注册广播失败：${it.javaClass.simpleName}: ${it.message}") }
        }
    }

    /** 让 AICR **自己**绑自己的 coreService —— 同包同 uid，必成。 */
    private fun bindSelf(app: Context): IBinder? {
        selfCore?.let { if (it.isBinderAlive) return it }
        val latch = CountDownLatch(1)
        var b: IBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                b = service
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                selfCore = null
            }
        }
        val intent = Intent().setComponent(
            ComponentName(AicrProtocol.PKG, AicrProtocol.CORE_CLASS),
        )
        val ok = runCatching { app.bindService(intent, conn, Context.BIND_AUTO_CREATE) }
            .getOrDefault(false)
        if (!ok) {
            log("广播：本地 bind 自己失败（bindService=false）")
            return null
        }
        runCatching { latch.await(10, TimeUnit.SECONDS) }
        val binder = b
        if (binder == null) {
            log("广播：本地 bind 超时")
            return null
        }
        selfCore = binder
        return binder
    }

    private fun handleSegmentBroadcast(ctx: Context, intent: Intent) {
        val rr = intent.getParcelableExtra(AicrProtocol.EXTRA_RESULT, ResultReceiver::class.java)
        val inUri = intent.getStringExtra(AicrProtocol.EXTRA_IN_URI)?.let(Uri::parse)
        val outUri = intent.getStringExtra(AicrProtocol.EXTRA_OUT_URI)?.let(Uri::parse)
        if (inUri == null || outUri == null) {
            log("广播：缺少 uri（in=$inUri out=$outUri）")
            replyErr(rr, "广播缺少 in_uri / out_uri")
            return
        }
        var inPfd: ParcelFileDescriptor? = null
        var out: OutputStream? = null
        try {
            // 广播自带的 FLAG_GRANT_READ/WRITE_URI_PERMISSION 已把这两个文件授权给本 uid。
            inPfd = ctx.contentResolver.openFileDescriptor(inUri, "r")
            if (inPfd == null) {
                replyErr(rr, "打不开输入 Uri（临时授权失效？）")
                return
            }
            out = ctx.contentResolver.openOutputStream(outUri, "wt")
            if (out == null) {
                replyErr(rr, "打不开输出 Uri（临时授权失效？）")
                return
            }

            val core = bindSelf(ctx)
            if (core == null) {
                replyErr(rr, "本地 bind 自己失败")
                return
            }
            val (plugin, status) = AicrProtocol.awaitPlugin(core)
            if (plugin == null) {
                replyErr(rr, "getPluginBinder 返回空（${AicrProtocol.statusText(status)}）")
                return
            }
            if (status != AicrProtocol.STATUS_OK) {
                replyErr(rr, "插件未就绪（${AicrProtocol.statusText(status)}）")
                return
            }
            val result = AicrProtocol.segmentByFd(plugin, inPfd)
            val bmp = result?.bitmaps?.firstOrNull()
            if (bmp == null) {
                replyErr(rr, "服务返回空位图")
                return
            }
            val png = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.PNG, 100, png)
            out.write(png.toByteArray())
            out.flush()
            // 先关流再回 OK —— App 收到 OK 会立刻读文件。
            runCatching { out.close() }
            out = null
            runCatching { rr?.send(AicrProtocol.RESULT_OK, null) }
            log("广播：完成 ${bmp.width}×${bmp.height}")
        } catch (t: Throwable) {
            log("广播：失败 ${t.javaClass.simpleName}: ${t.message}")
            replyErr(rr, "${t.javaClass.simpleName}: ${t.message}")
        } finally {
            runCatching { out?.close() }
            runCatching { inPfd?.close() }
        }
    }

    /** 把失败原因回传给 App（App 会写进 run_log 并继续回退 binder）。 */
    private fun replyErr(rr: ResultReceiver?, msg: String) {
        runCatching {
            rr?.send(
                AicrProtocol.RESULT_ERR,
                Bundle().apply { putString(AicrProtocol.EXTRA_ERROR, msg) },
            )
        }
    }


    private fun log(msg: String) = HookRuntime.log("AICR $msg")
}
