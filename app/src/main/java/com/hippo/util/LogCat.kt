/*
 * Copyright 2015 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.hippo.util

import com.hippo.lib.yorozuya.FileUtils
import com.hippo.lib.yorozuya.IOUtils
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志工具。
 *
 * 两种工作模式：
 *  1. [save]（原版行为，保留兼容）—— `logcat -d` 一次性 dump 系统环形缓冲区。
 *     问题：缓冲区全系统共享，DEBUG_LOG 开启后只能留 10~15 秒。
 *  2. [startRecording] / [stopRecording]（★ 个性化改造，方案③）—— 流式捕获。
 *     只订阅关心的 tag，边读边落盘，不受系统缓冲区大小限制。
 *
 * 流式模式的两条安全线：
 *  - 单文件达到 [MAX_FILE_BYTES] 自动切新文件，避免单个文件过大打不开
 *  - 总会话达到 [MAX_TOTAL_BYTES] 自动停录，避免写爆存储
 */
object LogCat {

    /** 单文件上限（约 32 MB） */
    private const val MAX_FILE_BYTES = 32L * 1024 * 1024

    /** 整个录制会话上限（约 256 MB） */
    private const val MAX_TOTAL_BYTES = 256L * 1024 * 1024

    /**
     * 订阅哪些 tag。
     * `SpiderQueen`         —— 下载链路（Start download image N / succeed / End / targetImageUrl）
     * `EhSSLSocketFactory`  —— H@H 建连时打印 Host 与解析到的 IP，能看出指派到哪台机器
     *
     * 不订阅 `EhEngine`：实测它每页都会打一次 api.php（25 次 / 27 秒），
     * 会把真正关键的 SpiderQueen 记录挤走。
     */
    private val TAG_FILTER = arrayOf(
        "SpiderQueen:D",
        "EhSSLSocketFactory:D",
    )

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var outDir: File? = null

    @Volatile
    private var seq = 0

    @Volatile
    private var totalBytes = 0L

    /** 最后写出的文件（供 UI 显示路径） */
    @Volatile
    private var lastFileRef: File? = null

    @JvmStatic
    fun getLastFile(): File? = lastFileRef

    /**
     * 是否正在录制。
     * 用 stopped 标志而非只看 isAlive：interrupt() 后线程要过一会儿才真正退出，
     * 用户快速连点两次时会误判，导致无法重新开始录制。
     */
    @JvmStatic
    fun isRecording(): Boolean = worker?.isAlive == true && !stopped

    @Volatile
    private var stopped = false

    /**
     * 原版行为：一次性 dump 系统缓冲区。
     * 保留以兼容外部调用；开启 DEBUG_LOG 时窗口只有 10~15 秒，建议改用流式。
     */
    @JvmStatic
    fun save(file: File?): Boolean {
        if (!FileUtils.ensureFile(file)) {
            return false
        }

        try {
            val p = Runtime.getRuntime().exec("logcat -d")
            IOUtils.copy(p.inputStream, FileOutputStream(file))
            return true
        } catch (e: IOException) {
            e.printStackTrace()
            return false
        }
    }

    /**
     * 开始流式录制。
     *
     * @param dir 输出目录，文件名为 `logcat-stream-<时间>-<序号>.txt`
     * @return true 表示已启动；false 表示已在录制中或启动失败
     */
    @JvmStatic
    @Synchronized
    fun startRecording(dir: File?): Boolean {
        if (dir == null) {
            return false
        }
        if (isRecording()) {
            return false
        }
        if (!FileUtils.ensureDirectory(dir)) {
            return false
        }

        outDir = dir
        seq = 0
        totalBytes = 0L
        lastFileRef = null
        stopped = false

        val t = Thread({
            try {
                // -v threadtime : 带日期、精确到毫秒、含线程号
                // -s TAG:D       : 只收指定 tag（Silence 其它）
                val cmd = mutableListOf("logcat", "-v", "threadtime", "-s")
                cmd.addAll(TAG_FILTER)

                val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
                process = p
                streamLoop(p.inputStream)
            } catch (e: IOException) {
                e.printStackTrace()
            } catch (e: InterruptedException) {
                // 正常停止
            } finally {
                process?.destroy()
                process = null
            }
        }, "LogCatRecorder")

        t.isDaemon = true
        worker = t
        t.start()
        return true
    }

    /** 停止录制并等待写盘完成（最多 2 秒）。 */
    @JvmStatic
    @Synchronized
    fun stopRecording() {
        stopped = true
        worker?.interrupt()
        try {
            process?.destroy()
        } catch (e: Exception) {
            // 忽略：进程可能已退出
        }
        // ★ 必须等待线程跑完 finally 里的 flush + close，
        //   否则调用方立刻读文件会读到被截断的尾巴。
        try {
            worker?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        process = null
        worker = null
    }

    private fun streamLoop(input: InputStream) {
        var out: FileOutputStream? = null
        var written = 0L
        try {
            out = FileOutputStream(openNextFile())
            var fos: FileOutputStream = out
            val buf = ByteArray(8192)
            while (!Thread.currentThread().isInterrupted) {
                val n = input.read(buf)
                if (n < 0) {
                    break
                }
                if (n == 0) {
                    continue
                }
                fos.write(buf, 0, n)
                written += n
                totalBytes += n

                // 总量到顶 ⇒ 停录
                if (totalBytes >= MAX_TOTAL_BYTES) {
                    break
                }
                // 单文件到顶 ⇒ 切新文件
                if (written >= MAX_FILE_BYTES) {
                    fos.flush()
                    fos.close()
                    fos = FileOutputStream(openNextFile())
                    out = fos
                    written = 0L
                }
            }
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                out?.flush()
                out?.close()
            } catch (e: IOException) {
                // 忽略
            }
        }
    }

    private fun openNextFile(): File {
        val dir = outDir ?: throw IOException("output dir is null")
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val f = File(dir, "logcat-stream-$ts-${seq++}.txt")
        lastFileRef = f
        return f
    }
}
