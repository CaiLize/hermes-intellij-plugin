package com.hermes.intellij.security

import com.intellij.openapi.diagnostic.Logger
import java.io.File

/**
 * 安全验证结果
 */
sealed class ValidationResult {
    data object Valid : ValidationResult()
    data class Invalid(val reason: String) : ValidationResult() {
        override fun toString() = "Invalid: $reason"
    }

    val isValid: Boolean get() = this is Valid
}

/**
 * 文件路径安全验证器
 * 防止读取/写入敏感文件
 * 
 * FIX: Issue #3 - 使用 canonicalPath 防止路径遍历攻击
 */
object FileSecurityValidator {
    private val LOG = Logger.getInstance(FileSecurityValidator::class.java)

    // 敏感文件扩展名
    private val SENSITIVE_EXTENSIONS = setOf(
        "env", "key", "pem", "p12", "jks", "keystore",
        "credentials", "secrets", "pfx", "ppk"
    )

    // 敏感文件名（精确匹配）
    private val SENSITIVE_FILE_NAMES = setOf(
        ".env", ".env.local", ".env.production", ".env.development",
        ".env.test", ".env.staging",
        "secrets.yaml", "secrets.yml", "secrets.json",
        "credentials.json", "credentials.yaml",
        "id_rsa", "id_dsa", "id_ecdsa", "id_ed25519",
        "known_hosts", "authorized_keys", "config",
        "google-services.json", "GoogleService-Info.plist",
        "service-account.json", "firebase-config.json"
    )

    // 敏感目录
    private val SENSITIVE_DIRS = setOf(
        ".ssh", ".gnupg", ".aws", ".azure", ".gcp",
        "secrets", "credentials", ".secrets"
    )

    /**
     * 规范化路径：将 Windows 和 Unix 路径分隔符统一，并解析符号链接
     * FIX: Issue #3 - 使用 canonicalPath 防止通过软链接/相对路径绕过
     */
    private fun normalizePath(path: String): String? {
        return try {
            File(path).canonicalPath
        } catch (e: Exception) {
            LOG.warn("[FileSecurity] Failed to normalize path: $path", e)
            null
        }
    }

    /**
     * 验证文件路径是否安全（可用于读取）
     * FIX: Issue #3 - 使用 canonicalPath 进行真实路径比较
     */
    fun validateReadPath(path: String, projectBasePath: String? = null): ValidationResult {
        // 检查扩展名
        val ext = path.substringAfterLast('.', "")
        if (ext.lowercase() in SENSITIVE_EXTENSIONS) {
            LOG.warn("[FileSecurity] Blocked read by extension: $ext in $path")
            return ValidationResult.Invalid("禁止读取敏感文件类型: .$ext")
        }

        // 检查文件名
        val fileName = path.substringAfterLast('/', path.substringAfterLast('\\'))
        if (fileName in SENSITIVE_FILE_NAMES) {
            LOG.warn("[FileSecurity] Blocked read by filename: $fileName")
            return ValidationResult.Invalid("禁止读取敏感文件: $fileName")
        }

        // 检查目录路径
        val pathLower = path.lowercase()
        for (sensitiveDir in SENSITIVE_DIRS) {
            if (pathLower.contains("/.$sensitiveDir/") || pathLower.contains("/$sensitiveDir/")) {
                LOG.warn("[FileSecurity] Blocked read from sensitive directory: $sensitiveDir")
                return ValidationResult.Invalid("禁止访问敏感目录: .$sensitiveDir")
            }
        }

        // FIX: Issue #3 - 使用 canonicalPath 进行真实路径比较，防止路径遍历
        projectBasePath?.let { base ->
            val canonicalPath = normalizePath(path)
            val canonicalBase = normalizePath(base)

            if (canonicalPath == null || canonicalBase == null) {
                LOG.warn("[FileSecurity] Failed to resolve canonical paths for: $path vs $base")
                return ValidationResult.Invalid("无法解析文件路径")
            }

            // 确保路径以项目根目录开头（防止 ../ 遍历）
            if (!canonicalPath.startsWith(canonicalBase)) {
                LOG.warn("[FileSecurity] Blocked read outside project: $path (canonical: $canonicalPath vs $canonicalBase)")
                return ValidationResult.Invalid("只能读取项目内文件")
            }

            // 额外检查：防止通过符号链接访问项目外文件
            if (canonicalPath == canonicalBase) {
                // 允许访问项目根目录本身
            } else if (!canonicalPath.startsWith("$canonicalBase/")) {
                LOG.warn("[FileSecurity] Blocked read via symlink outside project: $path")
                return ValidationResult.Invalid("只能读取项目内文件")
            }
        }

        return ValidationResult.Valid
    }

    /**
     * 验证文件路径是否安全（可用于写入）
     */
    fun validateWritePath(path: String, projectBasePath: String? = null): ValidationResult {
        val readResult = validateReadPath(path, projectBasePath)
        if (!readResult.isValid) return readResult

        // 额外检查：禁止写入脚本文件
        val ext = path.substringAfterLast('.', "").lowercase()
        if (ext in setOf("sh", "bash", "bat", "cmd", "ps1", "zsh", "fish")) {
            LOG.warn("[FileSecurity] Blocked write of script file: $ext")
            return ValidationResult.Invalid("禁止创建脚本文件: .$ext")
        }

        return ValidationResult.Valid
    }

    /**
     * 检查文件是否为敏感文件（用于 UI 警告）
     */
    fun isSensitiveFile(path: String): Boolean {
        return !validateReadPath(path).isValid
    }
}

/**
 * 命令行安全验证器
 * 防止执行危险命令
 * 
 * FIX: Issue #4 - 增强命令注入检测
 */
object CommandSecurityValidator {
    private val LOG = Logger.getInstance(CommandSecurityValidator::class.java)

    // 危险命令前缀
    private val DANGEROUS_COMMANDS = setOf(
        "rm ", "del ", "rmdir ", "rd ",
        "format ", "mkfs ", "fdisk ", "parted ",
        "dd ", "shred ",
        "curl ", "wget ", "fetch ",
        "bash ", "sh ", "zsh ", "fish ", "powershell ", "pwsh ",
        "python ", "python3 ", "perl ", "ruby ", "lua ", "php ",
        "nc ", "netcat ", "ncat ",
        "socat ",
        "eval ", "exec ",
        "chmod 777", "chown ",
        "sudo ", "su ",
        "mount ", "umount ",
        "iptables ", "firewall-cmd "
    )

    // FIX: Issue #4 - 扩展危险字符集，包含转义序列检测
    private val INJECTION_CHARS = setOf(';', '|', '&', '`', '$', '(', ')', '\n', '\r', '\\', '<', '>', '!')

    // 危险模式
    private val DANGEROUS_PATTERNS = listOf(
        Regex("/dev/tcp/"),
        Regex("/dev/udp/"),
        Regex("\\|\\s*bash"),
        Regex("\\|\\s*sh"),
        Regex(">&\\s*/dev/"),
        Regex("base64\\s+-d"),
        Regex("base64\\s+--decode"),
        Regex("openssl\\s+enc"),
        Regex("nc\\s+-[elv]"),
        // FIX: Issue #4 - 检测命令替换语法
        Regex("\\$\\(.*\\)"),      // $(command)
        Regex("`.*`"),             // `command`
        Regex("\\$\\{.*\\}"),      // ${var} 在命令上下文中可能危险
        // FIX: Issue #4 - 检测转义序列
        Regex("\\\\\\$"),          // \$ 转义
        Regex("\\\\\\;"),          // \; 转义
        Regex("\\\\\\|"),          // \| 转义
        Regex("\\\\\\&"),          // \& 转义
        Regex("\\\\\\`"),          // \` 转义
        // FIX: Issue #4 - 检测重定向
        Regex(">>\\s*"),           // 追加重定向
        Regex(">&\\s*"),           // 文件描述符重定向
        Regex("2>&1"),             // stderr 重定向到 stdout
        Regex("exec\\s+\\d+>&"),   // exec 文件描述符操作
    )

    // FIX: Issue #4 - 允许的命令白名单（更安全的方式）
    private val ALLOWED_COMMANDS = setOf(
        "ls", "dir", "cat", "type", "head", "tail", "wc", "grep", "find",
        "echo", "printf", "date", "pwd", "whoami", "id",
        "git", "npm", "yarn", "pnpm", "gradle", "mvn", "make",
        "java", "kotlinc", "scalac",
        "ps", "top", "htop", "free", "df", "du",
        "chmod", "chown", "cp", "mv", "mkdir", "rmdir", "touch",
        "sed", "awk", "sort", "uniq", "cut", "tr", "xargs"
    )

    /**
     * 检查命令是否包含转义的危险字符
     * FIX: Issue #4 - 检测转义序列绕过
     */
    private fun hasEscapedInjectionChars(command: String): Boolean {
        // 检测反斜杠转义的注入字符
        val escapedPattern = Regex("\\\\[$;|&`\n\r]")
        return escapedPattern.containsMatchIn(command)
    }

    /**
     * 验证 shell 命令是否安全
     * FIX: Issue #4 - 增强检测逻辑
     */
    fun validateCommand(command: String): ValidationResult {
        if (command.isBlank()) {
            return ValidationResult.Invalid("命令不能为空")
        }

        // FIX: Issue #4 - 检查转义序列（防止 \$; \| 等绕过）
        if (hasEscapedInjectionChars(command)) {
            LOG.warn("[CommandSecurity] Blocked command with escaped injection chars")
            return ValidationResult.Invalid("命令包含转义的危险字符（可能用于命令注入绕过）")
        }

        // 检查命令前缀
        val cmdLower = command.lowercase()
        for (dangerous in DANGEROUS_COMMANDS) {
            if (cmdLower.startsWith(dangerous)) {
                LOG.warn("[CommandSecurity] Blocked dangerous command prefix: $dangerous")
                return ValidationResult.Invalid("禁止执行危险命令: $dangerous*")
            }
        }

        // 检查命令注入字符（未转义的）
        for (char in INJECTION_CHARS) {
            if (char in command) {
                LOG.warn("[CommandSecurity] Blocked command with injection char: '$char'")
                return ValidationResult.Invalid("命令包含危险字符: '$char'（可能用于命令注入）")
            }
        }

        // 检查危险模式
        for (pattern in DANGEROUS_PATTERNS) {
            if (pattern.containsMatchIn(command)) {
                LOG.warn("[CommandSecurity] Blocked command with dangerous pattern: $pattern")
                return ValidationResult.Invalid("检测到危险命令模式")
            }
        }

        // 检查命令长度
        if (command.length > 1000) {
            LOG.warn("[CommandSecurity] Blocked overly long command: ${command.length} chars")
            return ValidationResult.Invalid("命令过长（最大 1000 字符）")
        }

        // FIX: Issue #4 - 对于非白名单命令，要求更严格的检查
        val firstWord = command.substringBefore(' ', command).substringBefore('\n', command)
        if (firstWord.isNotEmpty() && firstWord !in ALLOWED_COMMANDS) {
            // 如果命令不在白名单中，且包含任何特殊字符，拒绝执行
            val hasSpecialChars = INJECTION_CHARS.any { it in command }
            if (hasSpecialChars) {
                LOG.warn("[CommandSecurity] Non-whitelisted command with special chars: $firstWord")
                return ValidationResult.Invalid("命令不在允许列表中，且包含特殊字符")
            }
        }

        return ValidationResult.Valid
    }
}

/**
 * 代码内容安全验证器
 * 防止插入恶意代码
 */
object CodeSecurityValidator {
    private val LOG = Logger.getInstance(CodeSecurityValidator::class.java)

    // 危险代码模式
    private val DANGEROUS_PATTERNS = listOf(
        // 命令执行
        Regex("Runtime\\.getRuntime\\(\\)\\.exec"),
        Regex("ProcessBuilder"),
        Regex("shell_exec"),
        Regex("exec\\("),
        Regex("system\\("),
        Regex("passthru"),
        Regex("popen"),
        Regex("pcntl_exec"),

        // 文件操作
        Regex("new\\s+File\\(.*\\)\\.delete"),
        Regex("Files\\.delete"),
        Regex("Files\\.move.*\\$\\{.*\\}"),
        Regex("\\.readAllBytes.*\\.env"),
        Regex("Files\\.read.*\\.env"),
        Regex("FileInputStream.*\\.env"),

        // 网络操作
        Regex("curl.*\\|.*bash"),
        Regex("wget.*\\|.*bash"),
        Regex("\\.connect\\("),
        Regex("Socket\\("),
        Regex("ServerSocket\\("),
        Regex("HttpURLConnection"),
        Regex("OkHttpClient"),

        // 代码注入
        Regex("eval\\("),
        Regex("Function\\("),
        Regex("new\\s+ScriptEngine"),
        Regex("GroovyShell"),
        Regex("PythonInterpreter"),

        // 加密/解密（可能用于混淆）
        Regex("Base64\\.getDecoder"),
        Regex("Cipher\\.getInstance"),

        // 反射（可能用于绕过安全检查）
        Regex("Class\\.forName"),
        Regex("getClass\\(\\)\\.forName"),
        Regex("getDeclaredMethods"),
        Regex("setAccessible\\(true\\)")
    )

    // 敏感文件引用
    private val SENSITIVE_FILE_REFS = listOf(
        ".env", "credentials", "secrets", "api_key", "apikey", "secret_key",
        "access_token", "private_key", "id_rsa"
    )

    /**
     * 验证代码内容是否安全
     */
    @Suppress("UNUSED_PARAMETER")
    fun validateCode(code: String, language: String): ValidationResult {
        if (code.isBlank()) {
            return ValidationResult.Valid
        }

        // 检查危险模式
        for (pattern in DANGEROUS_PATTERNS) {
            if (pattern.containsMatchIn(code)) {
                LOG.warn("[CodeSecurity] Blocked dangerous code pattern: $pattern")
                return ValidationResult.Invalid("检测到危险代码模式，插入被阻止")
            }
        }

        // 检查敏感文件引用
        val codeLower = code.lowercase()
        for (ref in SENSITIVE_FILE_REFS) {
            if (ref in codeLower) {
                LOG.warn("[CodeSecurity] Blocked code referencing sensitive file: $ref")
                return ValidationResult.Invalid("代码引用了敏感文件/密钥：$ref")
            }
        }

        // 检查代码长度
        if (code.length > 50000) {
            LOG.warn("[CodeSecurity] Blocked overly large code: ${code.length} chars")
            return ValidationResult.Invalid("代码过长（最大 50000 字符）")
        }

        return ValidationResult.Valid
    }

    /**
     * 验证文件名是否安全
     */
    fun validateFileName(fileName: String): ValidationResult {
        val ext = fileName.substringAfterLast('.', "").lowercase()

        // 禁止创建脚本文件
        if (ext in setOf("sh", "bash", "bat", "cmd", "ps1", "zsh", "fish", "py", "rb", "pl")) {
            // 允许.py，但需要警告
            if (ext == "py") {
                LOG.info("[CodeSecurity] Warning: Python file creation allowed but review recommended")
            } else {
                LOG.warn("[CodeSecurity] Blocked script file creation: .$ext")
                return ValidationResult.Invalid("禁止创建脚本文件：.$ext")
            }
        }

        // 检查文件名是否包含敏感词
        val nameLower = fileName.lowercase()
        for (sensitive in SENSITIVE_FILE_REFS) {
            if (sensitive in nameLower) {
                LOG.warn("[CodeSecurity] Blocked file with sensitive name: $sensitive")
                return ValidationResult.Invalid("文件名包含敏感词：$sensitive")
            }
        }

        return ValidationResult.Valid
    }
}

/**
 * 会话 ID 验证器
 */
object SessionSecurityValidator {
    private val LOG = Logger.getInstance(SessionSecurityValidator::class.java)

    /**
     * 验证会话 ID 格式是否有效
     */
    fun validateSessionId(sessionId: String?): ValidationResult {
        if (sessionId.isNullOrBlank()) {
            return ValidationResult.Invalid("会话 ID 不能为空")
        }

        // 长度检查
        if (sessionId.length < 16 || sessionId.length > 128) {
            LOG.warn("[SessionSecurity] Invalid session ID length: ${sessionId.length}")
            return ValidationResult.Invalid("会话 ID 长度无效")
        }

        // 字符检查（只允许字母数字、连字符、下划线）
        if (!sessionId.matches(Regex("^[a-zA-Z0-9_-]+$"))) {
            LOG.warn("[SessionSecurity] Invalid session ID characters")
            return ValidationResult.Invalid("会话 ID 包含无效字符")
        }

        // 防止常见伪造模式
        if (sessionId.lowercase() in setOf("admin", "root", "system", "null", "none", "test")) {
            LOG.warn("[SessionSecurity] Blocked suspicious session ID: $sessionId")
            return ValidationResult.Invalid("会话 ID 无效")
        }

        return ValidationResult.Valid
    }
}

/**
 * URL/端点验证器
 */
object EndpointSecurityValidator {
    private val LOG = Logger.getInstance(EndpointSecurityValidator::class.java)

    // 内网 IP 段
    private val PRIVATE_IP_PATTERNS = listOf(
        Regex("^10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
        Regex("^172\\.(1[6-9]|2\\d|3[01])\\.\\d{1,3}\\.\\d{1,3}$"),
        Regex("^192\\.168\\.\\d{1,3}\\.\\d{1,3}$"),
        Regex("^127\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}$"),
        Regex("^169\\.254\\.\\d{1,3}\\.\\d{1,3}$"),  // 云元数据
        Regex("^0\\.0\\.0\\.0$"),
        Regex("^::1$"),
        Regex("^fe80::"),
        Regex("^fc00::")
    )

    /**
     * 验证 API 端点 URL 是否安全
     */
    fun validateEndpoint(url: String): ValidationResult {
        if (url.isBlank()) {
            return ValidationResult.Invalid("端点 URL 不能为空")
        }

        return try {
            val uri = java.net.URI(url)
            val scheme = uri.scheme?.lowercase()

            // 只允许 http/https
            if (scheme !in setOf("http", "https")) {
                LOG.warn("[EndpointSecurity] Invalid scheme: $scheme")
                return ValidationResult.Invalid("只支持 HTTP/HTTPS 协议")
            }

            // 强制 HTTPS（除本地回环外）
            val host = uri.host ?: return ValidationResult.Invalid("无效的主机名")

            if (scheme == "http" && !isLocalhost(host)) {
                LOG.warn("[EndpointSecurity] HTTP not allowed for non-localhost: $host")
                return ValidationResult.Invalid("非本地端点必须使用 HTTPS")
            }

            // 检查是否为内网地址（排除 localhost）
            if (isPrivateIp(host) && !isLocalhost(host)) {
                LOG.warn("[EndpointSecurity] Blocked private IP: $host")
                return ValidationResult.Invalid("禁止访问内网地址")
            }

            ValidationResult.Valid
        } catch (e: Exception) {
            LOG.warn("[EndpointSecurity] Invalid URL format: $url", e)
            ValidationResult.Invalid("无效的 URL 格式")
        }
    }

    private fun isLocalhost(host: String): Boolean {
        return host.lowercase() in setOf("localhost", "127.0.0.1", "::1", "0.0.0.0")
    }

    private fun isPrivateIp(host: String): Boolean {
        // 先尝试解析为 IP
        return try {
            val inetAddress = java.net.InetAddress.getByName(host)
            inetAddress.isSiteLocalAddress ||
                inetAddress.isLoopbackAddress ||
                inetAddress.isAnyLocalAddress ||
                inetAddress.isLinkLocalAddress
        } catch (e: Exception) {
            // 不是 IP 地址，检查模式
            PRIVATE_IP_PATTERNS.any { it.matches(host) }
        }
    }
}
