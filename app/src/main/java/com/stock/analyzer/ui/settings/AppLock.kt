package com.stock.analyzer.ui.settings

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * 应用锁要用到的生物识别能力判断与调用。
 *
 * 诚实边界：androidx.biometric 1.1.0 的 canAuthenticate 只告诉「能不能用」，
 * 不告诉具体是面部还是指纹（那是 1.2 之后的位掩码返回），所以界面上统一写
 * 「面部或指纹」，不冒充知道是哪一种。
 */
object AppLock {

    /** 本机生物识别状态，直接拿来显示给用户 */
    data class Availability(
        val usable: Boolean,
        /** 一句话状态，给开关下面那行灰字用 */
        val status: String,
        /** 真正调用时会用的 authenticator 组合；usable=false 时为 0 */
        val authenticators: Int
    )

    fun check(context: Context): Availability {
        val manager = BiometricManager.from(context)
        // 先试最宽的「强+弱」；不支持就退到弱、再退到强，试出来才敢开锁
        val combos = listOf(BIOMETRIC_STRONG or BIOMETRIC_WEAK, BIOMETRIC_WEAK, BIOMETRIC_STRONG)
        var firstFailure: Int? = null
        for (combo in combos) {
            val code = manager.canAuthenticate(combo)
            if (code == BiometricManager.BIOMETRIC_SUCCESS) {
                return Availability(
                    usable = true,
                    status = "可用：解锁时用系统登记的面部或指纹，数据只在本机校验",
                    authenticators = combo
                )
            }
            // 没录入是终态，换别的组合也一样，直接说清楚
            if (code == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED) {
                return Availability(false, reason(code), 0)
            }
            if (firstFailure == null) firstFailure = code
        }
        return Availability(false, reason(firstFailure), 0)
    }

    private fun reason(code: Int?): String = when (code) {
        null -> "暂时读不到生物识别状态，等几秒再刷新"
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED ->
            "系统里还没有录入面部或指纹。请到手机「设置 → 生物识别和密码」录入后再打开应用锁"
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE ->
            "本设备没有可用的生物识别硬件，无法开启应用锁"
        BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE ->
            "生物识别硬件当前不可用（刚开机或系统忙），稍后再试"
        BiometricManager.BIOMETRIC_ERROR_SECURITY_UPDATE_REQUIRED ->
            "系统要求先完成安全更新，才能使用生物识别"
        BiometricManager.BIOMETRIC_ERROR_UNSUPPORTED ->
            "系统把生物识别对该应用禁用了（检查应用权限或企业策略）"
        BiometricManager.BIOMETRIC_STATUS_UNKNOWN ->
            "暂时读不到生物识别状态，等几秒再刷新"
        else -> "生物识别不可用（错误码 $code）"
    }

    /**
     * 弹系统解锁框。callback 一定回调一次；[onResult] 的第一个参数是「是否放行」。
     *
     * 取消（负按钮）与识别失败都算不放行，但只有取消会带上 [CANCELLED] 标记，
     * 界面据此区分「你自己点了取消」和「没认出来，再试一次」。
     */
    fun authenticate(activity: FragmentActivity, title: String, onResult: (Boolean, String?) -> Unit) {
        val availability = check(activity)
        if (!availability.usable) {
            onResult(false, availability.status)
            return
        }
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onResult(true, null)
            }

            override fun onAuthenticationError(code: Int, message: CharSequence) {
                val text = when (code) {
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON,
                    BiometricPrompt.ERROR_CANCELED -> CANCELLED
                    BiometricPrompt.ERROR_TIMEOUT -> "识别超时，再试一次"
                    BiometricPrompt.ERROR_LOCKOUT, BiometricPrompt.ERROR_LOCKOUT_PERMANENT ->
                        "输错次数太多，生物识别暂时锁定，请稍后再试"
                    else -> message.toString().ifBlank { "解锁失败（错误码 $code）" }
                }
                onResult(false, text)
            }

            override fun onAuthenticationFailed() {
                onResult(false, "没有识别出来，再试一次")
            }
        }
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle("面部或指纹，任一都行")
            .setDescription("生物特征只在设备本地比对，本应用不读取、不上传、不保存")
            .setAllowedAuthenticators(availability.authenticators)
            .setConfirmationRequired(false)
            .setNegativeButtonText("先不进")
            .build()
        prompt.authenticate(info)
    }

    /** onResult 的第二参数等于这个值时，表示用户主动取消而不是识别失败 */
    const val CANCELLED = "__cancelled__"
}
