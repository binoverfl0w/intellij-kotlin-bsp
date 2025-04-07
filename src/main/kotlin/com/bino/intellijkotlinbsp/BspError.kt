package com.bino.intellijkotlinbsp

sealed class BspError : Exception()
data class BspConnectionError(override val message: String) : BspError()