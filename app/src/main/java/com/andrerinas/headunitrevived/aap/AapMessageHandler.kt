package com.andrerinas.headunitrevived.aap

internal interface AapMessageHandler {
    @Throws(HandleException::class)
    fun handle(message: AapMessage)

    class HandleException internal constructor(cause: Throwable) : Exception(cause)
}
