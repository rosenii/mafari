package dev.dsh.miuixbrowser

import android.content.Context
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import android.widget.Toast

/*
 * 身份验证闸门：先走生物识别（指纹 / 人脸），失败或不想用时可退回手机锁屏密码 / PIN / 图案
 * （系统验证框自带「使用密码」入口，由 DEVICE_CREDENTIAL 提供）。
 *
 * 这是界面层的把关（防止别人拿着已解锁的手机查看口令、恢复备份），并不把密钥绑定到生物识别——
 * 自动推送要在无人值守时用默认口令，所以口令本身仍由本机密钥库加密保存。
 * minSdk 33，直接用系统 android.hardware.biometrics，不引入额外依赖。
 */
object BiometricGate {
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    /** [onResult]：true = 验证通过（或设备没设锁屏、无从验证而放行）；false = 取消或失败。回调在主线程。 */
    fun authenticate(context: Context, title: String, subtitle: String, onResult: (Boolean) -> Unit) {
        val manager = context.getSystemService(BiometricManager::class.java)
        val can = manager?.canAuthenticate(AUTHENTICATORS) ?: BiometricManager.BIOMETRIC_ERROR_HW_UNAVAILABLE
        when (can) {
            BiometricManager.BIOMETRIC_SUCCESS -> Unit
            BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED, BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> {
                // 没有设置任何锁屏：系统无法验证，放行并提示
                Toast.makeText(context, "未设置锁屏，已跳过验证", Toast.LENGTH_SHORT).show()
                onResult(true)
                return
            }
            else -> {
                Toast.makeText(context, "暂时无法使用系统验证", Toast.LENGTH_SHORT).show()
                onResult(false)
                return
            }
        }
        val prompt = BiometricPrompt.Builder(context)
            .setTitle(title)
            .setSubtitle(subtitle)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(
            CancellationSignal(),
            context.mainExecutor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    val silent = errorCode == BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED ||
                        errorCode == BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                    if (!silent) Toast.makeText(context, "验证失败：" + (errString ?: "未知错误"), Toast.LENGTH_SHORT).show()
                    onResult(false)
                }
                // onAuthenticationFailed：单次识别不通过，系统会让用户重试，不是终态，无需处理
            },
        )
    }
}
