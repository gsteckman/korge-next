package com.soywiz.kmem

internal actual val CurrentNativePlatform: NativePlatform = NativePlatform.IOS
internal actual val CurrentNativeRuntimeVariant: NativeRuntimeVariant by lazy { NativeRuntimeVariant("IOS") }