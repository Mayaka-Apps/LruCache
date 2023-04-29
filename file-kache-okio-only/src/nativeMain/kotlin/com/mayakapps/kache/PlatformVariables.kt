package com.mayakapps.kache

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.newFixedThreadPoolContext
import okio.FileSystem

internal actual fun getDefaultFileSystem(): FileSystem = FileSystem.SYSTEM

@OptIn(ExperimentalCoroutinesApi::class)
internal actual fun getIODispatcher(): CoroutineDispatcher =
    newFixedThreadPoolContext(2048, "IO").limitedParallelism(64)