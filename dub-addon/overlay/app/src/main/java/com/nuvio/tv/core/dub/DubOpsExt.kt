package com.nuvio.tv.core.dub

/** DubPipelineRunner'ın çağırdığı "js_unpack" ve "rapidrame_decode" işlemleri. */
internal fun DubOps.jsUnpack(input: String): String? = DubPacked.jsUnpack(input)

internal fun DubOps.rapidrameDecode(input: String): String = DubPacked.rapidrameDecode(input)
