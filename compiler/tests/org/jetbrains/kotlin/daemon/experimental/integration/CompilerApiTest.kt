/*
 * Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.daemon.experimental.integration

import com.intellij.openapi.application.ApplicationManager
import kotlinx.coroutines.experimental.runBlocking
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageLocation
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.OutputMessageUtil
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import org.jetbrains.kotlin.daemon.client.experimental.DaemonReportingTargets
import org.jetbrains.kotlin.daemon.client.experimental.KotlinCompilerClient
import org.jetbrains.kotlin.daemon.common.*
import org.jetbrains.kotlin.daemon.common.experimental.findPortForSocket
import org.jetbrains.kotlin.daemon.experimental.CompileServiceServerSideImpl
import org.jetbrains.kotlin.daemon.experimental.KotlinCompileDaemon
import org.jetbrains.kotlin.integration.KotlinIntegrationTestBase
import org.jetbrains.kotlin.scripts.captureOut
import org.jetbrains.kotlin.test.KotlinTestUtils
import org.jetbrains.kotlin.test.testFramework.KtUsefulTestCase
import org.junit.Assert
import java.io.File
import java.net.URLClassLoader
import java.util.*
import kotlin.concurrent.schedule

class CompilerApiTest : KotlinIntegrationTestBase() {

    private val compilerLibDir = getCompilerLib()

    val compilerClassPath = listOf(
        File(compilerLibDir, "kotlin-compiler.jar")
    )
    val scriptRuntimeClassPath = listOf(
        File(compilerLibDir, "kotlin-runtime.jar"),
        File(compilerLibDir, "kotlin-script-runtime.jar")
    )
    val compilerId by lazy(LazyThreadSafetyMode.NONE) { CompilerId.makeCompilerId(compilerClassPath) }

    private fun compileLocally(
        messageCollector: TestMessageCollector,
        vararg args: String
    ): Pair<Int, Collection<OutputMessageUtil.Output>> {
        val application = ApplicationManager.getApplication()
        try {
            val code = K2JVMCompiler().exec(messageCollector,
                                            Services.EMPTY,
                                            K2JVMCompilerArguments().apply { K2JVMCompiler().parseArguments(args, this) }).code
            val outputs = messageCollector.messages.filter { it.severity == CompilerMessageSeverity.OUTPUT }.mapNotNull {
                OutputMessageUtil.parseOutputMessage(it.message)?.let { outs ->
                    outs.outputFile?.let { OutputMessageUtil.Output(outs.sourceFiles, it) }
                }
            }
            return code to outputs
        } finally {
            KtUsefulTestCase.resetApplicationToNull(application)
        }
    }

    private fun compileOnDaemon(
        clientAliveFile: File,
        compilerId: CompilerId,
        daemonJVMOptions: DaemonJVMOptions,
        daemonOptions: DaemonOptions,
        messageCollector: MessageCollector,
        vararg args: String
    ): Pair<Int, Collection<OutputMessageUtil.Output>> {

        println("KotlinCompilerClient.connectToCompileService() call")
        val daemon = KotlinCompilerClient.connectToCompileService(
            compilerId,
            clientAliveFile,
            daemonJVMOptions,
            daemonOptions,
            DaemonReportingTargets(messageCollector = messageCollector),
            autostart = true
        )
        println("KotlinCompilerClient.connectToCompileService() called!")

        assertNotNull("failed to connect daemon", daemon)

        println("runBlocking { ")
        runBlocking {
            println("register client...")
            daemon?.registerClient(clientAliveFile.absolutePath)
            println("   client registered")
        }
        println("} ^ runBlocking")


        val outputs = arrayListOf<OutputMessageUtil.Output>()

        val code = KotlinCompilerClient.compile(
            daemon!!,
            CompileService.NO_SESSION,
            CompileService.TargetPlatform.JVM,
            args,
            messageCollector,
            { outFile, srcFiles -> outputs.add(OutputMessageUtil.Output(srcFiles, outFile)) },
            reportSeverity = ReportSeverity.DEBUG
        )
        return code to outputs
    }

    private fun getHelloAppBaseDir(): String = KotlinTestUtils.getTestDataPathBase() + "/integration/smoke/helloApp"
    private fun getSimpleScriptBaseDir(): String = KotlinTestUtils.getTestDataPathBase() + "/integration/smoke/simpleScript"

    private fun run(baseDir: String, logName: String, vararg args: String): Int = runJava(baseDir, logName, *args)

    private fun runScriptWithArgs(
        testDataDir: String,
        logName: String?,
        scriptClassName: String,
        classpath: List<File>,
        vararg arguments: String
    ) {

        val cl = URLClassLoader(classpath.map { it.toURI().toURL() }.toTypedArray())
        val scriptClass = cl.loadClass(scriptClassName)

        val scriptOut = captureOut { scriptClass.constructors.first().newInstance(arguments) }

        if (logName != null) {
            val expectedFile = File(testDataDir, logName + ".expected")
            val normalizedContent = normalizeOutput(File(testDataDir), "OUT:\n$scriptOut\nReturn code: 0")

            KotlinTestUtils.assertEqualsToFile(expectedFile, normalizedContent)
        }
    }

    fun testScriptResolverEnvironmentArgsParsing() {

        fun args(body: K2JVMCompilerArguments.() -> Unit): K2JVMCompilerArguments =
            K2JVMCompilerArguments().apply(body)

        val longStr = (1..100).joinToString { """\" $it aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa \\""" }
        val unescapeRe = """\\(["\\])""".toRegex()
        val messageCollector = TestMessageCollector()
        Assert.assertEquals(
            hashMapOf("abc" to "def", "11" to "ab cd \\ \"", "long" to unescapeRe.replace(longStr, "\$1")),
            K2JVMCompiler.createScriptResolverEnvironment(
                args { scriptResolverEnvironment = arrayOf("abc=def", """11="ab cd \\ \""""", "long=\"$longStr\"") },
                messageCollector
            )
        )
    }

    fun testHelloAppLocal() {
        val messageCollector = TestMessageCollector()
        val jar = tmpdir.absolutePath + File.separator + "hello.jar"
        val (code, outputs) = compileLocally(
            messageCollector, "-include-runtime", File(getHelloAppBaseDir(), "hello.kt").absolutePath,
            "-d", jar, "-Xreport-output-files"
        )
        Assert.assertEquals(0, code)
        Assert.assertTrue(outputs.isNotEmpty())
        Assert.assertEquals(jar, outputs.first().outputFile?.absolutePath)
        run(getHelloAppBaseDir(), "hello.run", "-cp", jar, "Hello.HelloKt")
    }

    fun testHelloApp() {
        withFlagFile(getTestName(true), ".alive") { flagFile ->
            println("sarting test...")

            println("assigning daemonOptions")
            val daemonOptions = DaemonOptions(
                runFilesPath = File(tmpdir, getTestName(true)).absolutePath,
                verbose = true,
                reportPerf = true
            )
            println("daemonOptions assigned")

            println("creating logFile")
            val logFile = createTempFile("kotlin-daemon-experimental-test.", ".log")
            println("logFile created")

            println("creating daemonJVMOptions")
            val daemonJVMOptions = configureDaemonJVMOptions(
                "D$COMPILE_DAEMON_LOG_PATH_PROPERTY=\"${logFile.loggerCompatiblePath}\"",
                inheritMemoryLimits = false,
                inheritOtherJvmOptions = false,
                inheritAdditionalProperties = false
            )
            println("daemonJVMOptions created")

            println("creating jar")
            val jar = tmpdir.absolutePath + File.separator + "hello.jar"
            println("jar created")

            try {
                println("compileOnDaemon call")
                val (code, outputs) = compileOnDaemon(
                    flagFile,
                    compilerId,
                    daemonJVMOptions,
                    daemonOptions,
                    TestMessageCollector(),
                    "-include-runtime",
                    File(getHelloAppBaseDir(), "hello.kt").absolutePath,
                    "-d",
                    jar,
                    "-Xreport-output-files"
                )
                println("compileOnDaemon called")

                Assert.assertEquals(0, code)
                Assert.assertTrue(outputs.isNotEmpty())
                Assert.assertEquals(jar, outputs.first().outputFile?.absolutePath)
                run(getHelloAppBaseDir(), "hello.run", "-cp", jar, "Hello.HelloKt")
            } finally {
                runBlocking {
                    KotlinCompilerClient.shutdownCompileService(compilerId, daemonOptions)
                }
                logFile.delete()
            }
        }
    }

//    fun testSimpleScriptLocal() {
//        val messageCollector = TestMessageCollector()
//        val (code, outputs) = compileLocally(
//            messageCollector, File(getSimpleScriptBaseDir(), "script.kts").absolutePath,
//            "-d", tmpdir.absolutePath, "-Xreport-output-files"
//        )
//        Assert.assertEquals(0, code)
//        Assert.assertTrue(outputs.isNotEmpty())
//        Assert.assertEquals(File(tmpdir, "Script.class").absolutePath, outputs.first().outputFile?.absolutePath)
//        runScriptWithArgs(getSimpleScriptBaseDir(), "script", "Script", scriptRuntimeClassPath + tmpdir, "hi", "there")
//    }
//
//    fun testSimpleScript() {
//        withFlagFile(getTestName(true), ".alive") { flagFile ->
//            val daemonOptions = DaemonOptions(
//                runFilesPath = File(tmpdir, getTestName(true)).absolutePath,
//                verbose = true,
//                reportPerf = true
//            )
//
//            val logFile = createTempFile("kotlin-daemon-test.", ".log")
//
//            val daemonJVMOptions = configureDaemonJVMOptions(
//                "D$COMPILE_DAEMON_LOG_PATH_PROPERTY=\"${logFile.loggerCompatiblePath}\"",
//                inheritMemoryLimits = false, inheritOtherJvmOptions = false, inheritAdditionalProperties = false
//            )
//            try {
//                val (code, outputs) = compileOnDaemon(
//                    flagFile, compilerId, daemonJVMOptions, daemonOptions, TestMessageCollector(),
//                    File(getSimpleScriptBaseDir(), "script.kts").absolutePath, "-Xreport-output-files", "-d", tmpdir.absolutePath
//                )
//                Assert.assertEquals(0, code)
//                Assert.assertTrue(outputs.isNotEmpty())
//                Assert.assertEquals(File(tmpdir, "Script.class").absolutePath, outputs.first().outputFile?.absolutePath)
//                runScriptWithArgs(getSimpleScriptBaseDir(), "script", "Script", scriptRuntimeClassPath + tmpdir, "hi", "there")
//            } finally {
//                KotlinCompilerClient.shutdownCompileService(compilerId, daemonOptions)
//                logFile.delete()
//            }
//        }
//    }

    fun testConnectionMechanism() {
        withFlagFile(getTestName(true), ".alive") { flagFile ->
            val daemonJVMOptions = configureDaemonJVMOptions(
                inheritMemoryLimits = true,
                inheritOtherJvmOptions = true,
                inheritAdditionalProperties = true
            )
            val compilerId = CompilerId()
            val daemonOptions = DaemonOptions()
            val port = findPortForSocket(
                COMPILE_DAEMON_FIND_PORT_ATTEMPTS,
                COMPILE_DAEMON_PORTS_RANGE_START,
                COMPILE_DAEMON_PORTS_RANGE_END
            )
            // timer with a daemon thread, meaning it should not prevent JVM to exit normally
            val timer = Timer(true)
            val compilerService = CompileServiceServerSideImpl(
                port,
                compilerId,
                daemonOptions,
                daemonJVMOptions,
                port,
                timer,
                {
                    if (daemonOptions.forceShutdownTimeoutMilliseconds != COMPILE_DAEMON_TIMEOUT_INFINITE_MS) {
                        // running a watcher thread that ensures that if the daemon is not exited normally (may be due to RMI leftovers), it's forced to exit
                        timer.schedule(daemonOptions.forceShutdownTimeoutMilliseconds) {
                            cancel()
                            KotlinCompileDaemon.log.info("force JVM shutdown")
                            System.exit(0)
                        }
                    } else {
                        timer.cancel()
                    }
                })
            compilerService.runServer()
            println("service started")
            val runFileDir = File(daemonOptions.runFilesPathOrDefault)
            runFileDir.mkdirs()
            val runFile = File(
                runFileDir,
                makeRunFilenameString(
                    timestamp = "%tFT%<tH-%<tM-%<tS.%<tLZ".format(Calendar.getInstance(TimeZone.getTimeZone("Z"))),
                    digest = compilerId.compilerClasspath.map { File(it).absolutePath }.distinctStringsDigest().toHexString(),
                    port = port.toString()
                )
            )
            val daemons = walkDaemons(
                File(daemonOptions.runFilesPathOrDefault),
                compilerId,
                runFile,
                filter = { _, p -> p != port },
                report = { _, msg -> println(msg) }
            ).toList()
            println("daemons : $daemons")
            assert(daemons.isNotEmpty())
            val daemon = daemons[0].daemon
            val info = daemon.getDaemonInfo()
            println("info : $info")
            assert(info.isGood)
        }
    }

}

class TestMessageCollector : MessageCollector {
    data class Message(val severity: CompilerMessageSeverity, val message: String, val location: CompilerMessageLocation?)

    val messages = arrayListOf<Message>()

    override fun clear() {
        messages.clear()
    }

    override fun report(severity: CompilerMessageSeverity, message: String, location: CompilerMessageLocation?) {
        messages.add(Message(severity, message, location))
    }

    override fun hasErrors(): Boolean =
        messages.any { it.severity == CompilerMessageSeverity.EXCEPTION || it.severity == CompilerMessageSeverity.ERROR }

    override fun toString(): String {
        return messages.joinToString("\n") { "${it.severity}: ${it.message}${it.location?.let { " at $it" } ?: ""}" }
    }
}

fun TestMessageCollector.assertHasMessage(msg: String, desiredSeverity: CompilerMessageSeverity? = null) {
    assert(messages.any { it.message.contains(msg) && (desiredSeverity == null || it.severity == desiredSeverity) }) {
        "Expecting message \"$msg\" with severity ${desiredSeverity?.toString() ?: "Any"}, actual:\n" +
                messages.joinToString("\n") { it.severity.toString() + ": " + it.message }
    }
}
