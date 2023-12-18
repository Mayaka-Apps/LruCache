package com.mayakapps.kache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext

@OptIn(ExperimentalCoroutinesApi::class)
internal actual fun getIODispatcher(): CoroutineDispatcher =
    newFixedThreadPoolContext(2048, "IO").limitedParallelism(64)