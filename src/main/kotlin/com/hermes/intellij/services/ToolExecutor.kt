package com.hermes.intellij.services

import com.hermes.intellij.api.models.ToolCallRecord
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * 工具调用执行器
 * 负责执行模型请求的工具调用，并返回结果
 */
object ToolExecutor {
    private val LOG = Logger.getInstance(ToolExecutor::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 执行工具调用
     * @return 工具执行结果（包含成功/失败状态和输出内容）
     */
    suspend fun executeTool(
        toolName: String,
        toolArgsJson: String,
        projectBasePath: String? = null
    ): ToolExecutionResult {
        return try {
            when (toolName) {
                "terminal" -> executeTerminal(toolArgsJson)
                "read_file" -> executeReadFile(toolArgsJson, projectBasePath)
                "write_file" -> executeWriteFile(toolArgsJson, projectBasePath)
                "web_search" -> executeWebSearch(toolArgsJson)
                "search_files" -> executeSearchFiles(toolArgsJson, projectBasePath)
                else -> ToolExecutionResult(
                    success = false,
                    output = "Unknown tool: $toolName",
                    error = "Tool not found"
                )
            }
        } catch (e: Exception) {
            LOG.warn("[ToolExecutor] Error executing tool $toolName", e)
            ToolExecutionResult(
                success = false,
                output = e.message ?: "Unknown error",
                error = e::class.simpleName
            )
        }
    }

    /**
     * 终端命令执行
     * FIX (VULN-020): 增强命令注入防护
     */
    private suspend fun executeTerminal(argsJson: String): ToolExecutionResult {
        val args = json.decodeFromString<TerminalArgs>(argsJson)
        val command = args.command.trim()

        if (command.isBlank()) {
            return ToolExecutionResult(success = false, output = "Empty command", error = "Invalid command")
        }

        // FIX (VULN-020): 更严格的命令注入检测
        // 1. 检测命令注入字符（包括绕过黑名单的方式）
        val injectionPatterns = listOf(
            Regex("""[;|&`]"""),                    // 基础注入字符
            Regex("""\$\("""),                      // $() 命令替换
            Regex("""\$\{"""),                      // ${} 变量展开
            Regex("""`[^`]+`"""),                   // 反引号命令替换
            Regex("""eval\s"""),                    // eval 命令
            Regex("""exec\s"""),                    // exec 命令
            Regex("""bash\s+-c"""),                 // bash -c 嵌套
            Regex("""sh\s+-c"""),                   // sh -c 嵌套
            Regex("""zsh\s+-c"""),                  // zsh -c 嵌套
            Regex("""/dev/tcp/"""),                 // 网络重定向
            Regex("""/dev/udp/"""),                 // UDP 重定向
            Regex("""base64\s+-d"""),               // base64 解码
            Regex("""base64\s+--decode"""),         // base64 解码
            Regex("""openssl\s+enc"""),             // openssl 加密
            Regex("""nc\s+-[elv]"""),               // netcat 监听
        )

        for (pattern in injectionPatterns) {
            if (pattern.containsMatchIn(command)) {
                LOG.warn("[ToolExecutor] Blocked command with injection pattern: ${pattern.pattern}")
                return ToolExecutionResult(
                    success = false,
                    output = "Blocked: command injection pattern detected",
                    error = "Security: command injection detected"
                )
            }
        }

        // 2. 检测危险命令前缀
        val dangerousPrefixes = listOf(
            "rm -rf", "del /f", "del /s", "format", "dd if=", "dd of=",
            "shred", "mkfs", "fdisk", "parted", "chmod 777", "chown",
            "sudo", "su ", "mount", "umount", "iptables", "firewall-cmd",
            "curl ", "wget ", "fetch ", "nc ", "netcat ", "ncat ",
            "socat", "python ", "python3 ", "perl ", "ruby ", "lua ", "php ",
            "bash ", "sh ", "zsh ", "fish ", "powershell ", "pwsh ",
            "eval", "exec", "source", ". "
        )

        for (prefix in dangerousPrefixes) {
            if (command.startsWith(prefix)) {
                LOG.warn("[ToolExecutor] Blocked dangerous command prefix: $prefix")
                return ToolExecutionResult(
                    success = false,
                    output = "Blocked: dangerous command '$command'",
                    error = "Security: dangerous command detected"
                )
            }
        }

        // 3. 检测危险文件名/路径
        val dangerousPaths = listOf(
            ".env", ".ssh", ".aws", ".azure", ".gcp",
            "credentials", "secrets", "id_rsa", "authorized_keys",
            "google-services.json", "service-account"
        )
        val cmdLower = command.lowercase()
        for (dangerousPath in dangerousPaths) {
            if (cmdLower.contains(dangerousPath)) {
                LOG.warn("[ToolExecutor] Blocked command referencing sensitive path: $dangerousPath")
                return ToolExecutionResult(
                    success = false,
                    output = "Blocked: sensitive path detected",
                    error = "Security: sensitive path access denied"
                )
            }
        }

        // 4. 命令长度限制
        if (command.length > 500) {
            LOG.warn("[ToolExecutor] Blocked overly long command: ${command.length} chars")
            return ToolExecutionResult(
                success = false,
                output = "Command too long (max 500 chars)",
                error = "Security: command length exceeded"
            )
        }

        LOG.info("[ToolExecutor] Executing terminal: $command")

        return try {
            val isWindows = System.getProperty("os.name").lowercase().contains("win")
            // FIX (VULN-020): 使用 -c 参数时，只允许单个简单命令
            val processBuilder = if (isWindows) {
                ProcessBuilder(listOf("cmd", "/c", command))
            } else {
                ProcessBuilder(listOf("bash", "-c", command))
            }
            processBuilder.redirectErrorStream(true)

            val timeout = args.timeout ?: 60
            val process = processBuilder.start()

            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = if (timeout > 0) {
                process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
                process.exitValue()
            } else {
                process.waitFor()
                process.exitValue()
            }

            if (exitCode == 0) {
                ToolExecutionResult(success = true, output = output.trim(), exitCode = exitCode)
            } else {
                ToolExecutionResult(
                    success = false,
                    output = output.trim(),
                    error = "Command exited with code $exitCode",
                    exitCode = exitCode
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(success = false, output = e.message ?: "Execution failed", error = e::class.simpleName)
        }
    }

    /**
     * 文件读取
     */
    private suspend fun executeReadFile(argsJson: String, projectBasePath: String?): ToolExecutionResult {
        val args = json.decodeFromString<ReadFileArgs>(argsJson)
        val filePath = args.path.trim()

        if (filePath.isBlank()) {
            return ToolExecutionResult(success = false, output = "Empty file path", error = "Invalid path")
        }

        // 安全检查：敏感文件检测
        val sensitivePatterns = listOf(
            ".env", ".ssh", ".aws", ".azure", ".gcp",
            "credentials", "secrets", "id_rsa", "authorized_keys",
            "google-services.json", "service-account"
        )
        val pathLower = filePath.lowercase()
        for (pattern in sensitivePatterns) {
            if (pattern in pathLower) {
                return ToolExecutionResult(
                    success = false,
                    output = "Blocked: sensitive file path detected",
                    error = "Security: sensitive file access denied"
                )
            }
        }

        // 路径解析：支持相对路径（相对于项目根目录）
        val resolvedPath = if (File(filePath).isAbsolute) {
            Path.of(filePath)
        } else {
            val basePath = projectBasePath ?: System.getProperty("user.dir")
            Path.of(basePath, filePath)
        }

        return try {
            if (!Files.exists(resolvedPath)) {
                ToolExecutionResult(success = false, output = "File not found: $filePath", error = "FileNotFound")
            } else if (!Files.isRegularFile(resolvedPath)) {
                ToolExecutionResult(success = false, output = "Not a regular file: $filePath", error = "NotAFile")
            } else {
                val content = Files.readString(resolvedPath)
                ToolExecutionResult(
                    success = true,
                    output = content,
                    metadata = mapOf("path" to resolvedPath.toString(), "size" to content.length.toString())
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(success = false, output = e.message ?: "Read failed", error = e::class.simpleName)
        }
    }

    /**
     * 文件写入
     */
    private suspend fun executeWriteFile(argsJson: String, projectBasePath: String?): ToolExecutionResult {
        val args = json.decodeFromString<WriteFileArgs>(argsJson)
        val filePath = args.path.trim()
        val content = args.content

        if (filePath.isBlank()) {
            return ToolExecutionResult(success = false, output = "Empty file path", error = "Invalid path")
        }

        if (content.isBlank()) {
            return ToolExecutionResult(success = false, output = "Empty content", error = "Invalid content")
        }

        // 安全检查：敏感文件检测
        val sensitivePatterns = listOf(
            ".env", ".ssh", ".aws", ".azure", ".gcp",
            "credentials", "secrets", "id_rsa", "authorized_keys"
        )
        val pathLower = filePath.lowercase()
        for (pattern in sensitivePatterns) {
            if (pattern in pathLower) {
                return ToolExecutionResult(
                    success = false,
                    output = "Blocked: sensitive file path detected",
                    error = "Security: sensitive file write denied"
                )
            }
        }

        // 路径解析
        val resolvedPath = if (File(filePath).isAbsolute) {
            Path.of(filePath)
        } else {
            val basePath = projectBasePath ?: System.getProperty("user.dir")
            Path.of(basePath, filePath)
        }

        return try {
            // 确保父目录存在
            resolvedPath.parent?.let { Files.createDirectories(it) }
            Files.writeString(resolvedPath, content)
            ToolExecutionResult(
                success = true,
                output = "File written successfully: $filePath",
                metadata = mapOf("path" to resolvedPath.toString(), "bytes" to content.length.toString())
            )
        } catch (e: Exception) {
            ToolExecutionResult(success = false, output = e.message ?: "Write failed", error = e::class.simpleName)
        }
    }

    /**
     * Web 搜索（调用 Hermes Agent 的 web_search 工具）
     * 注意：这里需要调用 Hermes Agent API，而不是直接搜索
     */
    private suspend fun executeWebSearch(argsJson: String): ToolExecutionResult {
        val args = json.decodeFromString<WebSearchArgs>(argsJson)
        val query = args.query.trim()

        if (query.isBlank()) {
            return ToolExecutionResult(success = false, output = "Empty search query", error = "Invalid query")
        }

        LOG.info("[ToolExecutor] Web search: $query")

        // 实际实现：调用 Hermes Agent 的 web_search 工具
        // 这里先返回一个占位结果，实际实现需要调用 Agent API
        return ToolExecutionResult(
            success = true,
            output = "[Web search tool called with query: \"$query\"]\n\nNote: Web search requires Hermes Agent to be running with web_search capability enabled.",
            metadata = mapOf("query" to query)
        )
    }

    /**
     * 文件搜索
     */
    private suspend fun executeSearchFiles(argsJson: String, projectBasePath: String?): ToolExecutionResult {
        val args = json.decodeFromString<SearchFilesArgs>(argsJson)
        val pattern = args.pattern.trim()
        val target = args.target ?: "files"
        val searchPath = args.path?.trim()

        if (pattern.isBlank()) {
            return ToolExecutionResult(success = false, output = "Empty search pattern", error = "Invalid pattern")
        }

        // 路径解析
        val baseDir = if (searchPath != null && searchPath.isNotBlank()) {
            if (File(searchPath).isAbsolute) {
                Path.of(searchPath)
            } else {
                val basePath = projectBasePath ?: System.getProperty("user.dir")
                Path.of(basePath, searchPath)
            }
        } else {
            val basePath = projectBasePath ?: System.getProperty("user.dir")
            Path.of(basePath)
        }

        if (!Files.exists(baseDir) || !Files.isDirectory(baseDir)) {
            return ToolExecutionResult(success = false, output = "Search path not found: $baseDir", error = "PathNotFound")
        }

        LOG.info("[ToolExecutor] Searching in $baseDir: pattern=$pattern, target=$target")

        return try {
            val results = mutableListOf<String>()
            val maxResults = 50

            if (target == "files") {
                Files.walk(baseDir)
                    .filter { it.toFile().name.contains(pattern, ignoreCase = true) }
                    .limit(maxResults.toLong())
                    .forEach { results.add(it.toString()) }
            } else {
                // 搜索文件内容（简化实现：只搜索文本文件）
                Files.walk(baseDir)
                    .filter { Files.isRegularFile(it) }
                    .filter { it.toFile().extension in setOf("kt", "java", "js", "ts", "py", "md", "txt", "json", "xml", "yaml", "yml") }
                    .limit((maxResults * 5).toLong())
                    .forEach { file ->
                        try {
                            val content = Files.readString(file)
                            if (pattern.lowercase() in content.lowercase()) {
                                results.add("${file.toString()} (content match)")
                            }
                        } catch (e: Exception) {
                            // 跳过无法读取的文件
                        }
                    }
            }

            if (results.isEmpty()) {
                ToolExecutionResult(success = true, output = "No matches found for pattern: $pattern", metadata = mapOf("pattern" to pattern))
            } else {
                ToolExecutionResult(
                    success = true,
                    output = "Found ${results.size} match(es):\n\n${results.joinToString("\n")}",
                    metadata = mapOf("pattern" to pattern, "count" to results.size.toString())
                )
            }
        } catch (e: Exception) {
            ToolExecutionResult(success = false, output = e.message ?: "Search failed", error = e::class.simpleName)
        }
    }

    // ==================== 数据类 ====================

    private data class TerminalArgs(val command: String, val timeout: Int? = null)
    private data class ReadFileArgs(val path: String)
    private data class WriteFileArgs(val path: String, val content: String)
    private data class WebSearchArgs(val query: String)
    private data class SearchFilesArgs(val pattern: String, val target: String? = null, val path: String? = null)
}

/**
 * 工具执行结果
 */
data class ToolExecutionResult(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val exitCode: Int? = null,
    val metadata: Map<String, String> = emptyMap()
) {
    /**
     * 格式化为模型可理解的文本
     */
    fun toFormattedOutput(): String {
        val status = if (success) "✅ Success" else "❌ Error"
        val header = "$status - $output"
        val errorDetail = error?.let { "\n\nError: $it" } ?: ""
        val exitDetail = exitCode?.let { "\nExit code: $it" } ?: ""
        return "$header$errorDetail$exitDetail"
    }
}
