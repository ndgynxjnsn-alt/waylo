package dev.waylo.mock

import java.util.concurrent.CountDownLatch

fun main() {
    val server = DhlMockServer(MockConfig.fromEnvironment()).start()
    val stopped = CountDownLatch(1)
    Runtime.getRuntime().addShutdownHook(Thread { server.close(); stopped.countDown() })
    stopped.await()
}
