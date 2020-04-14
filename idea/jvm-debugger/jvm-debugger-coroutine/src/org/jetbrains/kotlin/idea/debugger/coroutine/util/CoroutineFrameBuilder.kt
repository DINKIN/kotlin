/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.debugger.coroutine.util

import com.intellij.debugger.engine.SuspendContextImpl
import com.intellij.debugger.jdi.StackFrameProxyImpl
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl
import com.intellij.debugger.ui.impl.watch.MethodsTracker
import com.intellij.debugger.ui.impl.watch.StackFrameDescriptorImpl
import com.sun.jdi.ObjectReference
import org.jetbrains.kotlin.idea.debugger.coroutine.data.*
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.ContinuationHolder
import org.jetbrains.kotlin.idea.debugger.coroutine.proxy.LocationStackFrameProxyImpl
import org.jetbrains.kotlin.idea.debugger.safeLineNumber
import org.jetbrains.kotlin.idea.debugger.safeLocation
import org.jetbrains.kotlin.idea.debugger.safeMethod


class CoroutineFrameBuilder {

    companion object {
        fun build(coroutine: CoroutineInfoData, suspendContext: SuspendContextImpl): DoubleFrameList? =
            when {
                coroutine.isRunning() -> buildStackFrameForActive(coroutine, suspendContext)
                coroutine.isSuspended() -> DoubleFrameList(coroutine.stackTrace, coroutine.creationStackTrace)
                else -> null
            }

        private fun buildStackFrameForActive(coroutine: CoroutineInfoData, suspendContext: SuspendContextImpl): DoubleFrameList? {
            val activeThread = coroutine.activeThread ?: return null

            val coroutineStackFrameList = mutableListOf<CoroutineStackFrameItem>()
            val threadReferenceProxyImpl = ThreadReferenceProxyImpl(suspendContext.debugProcess.virtualMachineProxy, activeThread)
            val realFrames = threadReferenceProxyImpl.forceFrames()
            for (runningStackFrameProxy in realFrames) {
                val preflightStackFrame = coroutineExitFrame(runningStackFrameProxy, suspendContext)
                if (preflightStackFrame != null) {
                    coroutineStackFrameList.add(buildRealStackFrameItem(preflightStackFrame.stackFrameProxy,))
                    val doubleFrameList = build(preflightStackFrame, suspendContext)
                    coroutineStackFrameList.addAll(doubleFrameList.stackTrace)
                    return DoubleFrameList(coroutineStackFrameList, doubleFrameList.creationStackTrace)
                } else {
                    coroutineStackFrameList.add(buildRealStackFrameItem(runningStackFrameProxy,))
                }
            }
            return DoubleFrameList(coroutineStackFrameList, emptyList())
        }

        /**
         * Used by CoroutineAsyncStackTraceProvider to build XFramesView
         */
        fun build(preflightFrame: CoroutinePreflightStackFrame, suspendContext: SuspendContextImpl): DoubleFrameList {
            val stackFrames = mutableListOf<CoroutineStackFrameItem>()

            stackFrames.addAll(preflightFrame.coroutineInfoData.restoredStackTrace())

            // rest of the stack
            stackFrames.addAll(preflightFrame.threadPreCoroutineFrames.drop(1).mapIndexedNotNull { index, stackFrameProxyImpl ->
//                if (index == 0)
//                    PreCoroutineStackFrameItem(stackFrameProxyImpl, firstRestoredFrame) // get location and variables from restored part
//                else
                    suspendContext.invokeInManagerThread { buildRealStackFrameItem(stackFrameProxyImpl) }
            })

            return DoubleFrameList(stackFrames, preflightFrame.coroutineInfoData.creationStackTrace)
        }

        data class DoubleFrameList(
            val stackTrace: List<CoroutineStackFrameItem>,
            val creationStackTrace: List<CreationCoroutineStackFrameItem>
        )

        private fun buildRealStackFrameItem(
            frame: StackFrameProxyImpl
        ) =
            RunningCoroutineStackFrameItem(frame)

        /**
         * Used by CoroutineStackFrameInterceptor to check if that frame is 'exit' coroutine frame.
         */
        fun coroutineExitFrame(
            frame: StackFrameProxyImpl,
            suspendContext: SuspendContextImpl
        ): CoroutinePreflightStackFrame? {
            return suspendContext.invokeInManagerThread {
                if (frame.location().isPreFlight()) {
                    if (coroutineDebuggerTraceEnabled())
                        ContinuationHolder.log.debug("Entry frame found: ${frame.format()}")
                    lookupContinuation(suspendContext, frame)
                } else
                    null
            }
        }

        private fun filterNegativeLineNumberInvokeSuspendFrames(frame: StackFrameProxyImpl): Boolean {
            val method = frame.safeLocation()?.safeMethod() ?: return false
            return method.isInvokeSuspend() && frame.safeLocation()?.safeLineNumber() ?: 0 < 0
        }

        fun lookupContinuation(
            suspendContext: SuspendContextImpl,
            frame: StackFrameProxyImpl
        ): CoroutinePreflightStackFrame? {
            if (threadAndContextSupportsEvaluation(suspendContext, frame)) {

                val method = frame.safeLocation()?.safeMethod() ?: return null
                val mode = when {
                    method.isSuspendLambda() -> SuspendExitMode.SUSPEND_LAMBDA
                    method.isSuspendMethod() -> SuspendExitMode.SUSPEND_METHOD
                    else -> return null
                }

                val context = suspendContext.executionContext() ?: return null
                val continuation = when (mode) {
                    SuspendExitMode.SUSPEND_LAMBDA -> getThisContinuation(frame)
                    SuspendExitMode.SUSPEND_METHOD -> getLVTContinuation(frame)
                    else -> null
                } ?: return null

                val coroutineInfo = ContinuationHolder(context).extractCoroutineInfoData(continuation, context) ?: return null
                return preflight(frame, coroutineInfo, mode)
            }
            return null
        }

        private fun preflight(
            frame: StackFrameProxyImpl,
            coroutineInfoData: CoroutineInfoData,
            mode: SuspendExitMode
        ): CoroutinePreflightStackFrame? {
            val descriptor = StackFrameDescriptorImpl(frame, MethodsTracker())
            val framesLeft = leftThreadStack(frame) ?: return null
            return CoroutinePreflightStackFrame(
                coroutineInfoData,
                descriptor,
                framesLeft,
                mode
            )
        }


        private fun getLVTContinuation(frame: StackFrameProxyImpl?) =
            frame?.continuationVariableValue()

        private fun getThisContinuation(frame: StackFrameProxyImpl?): ObjectReference? =
            frame?.thisVariableValue()

        fun leftThreadStack(frame: StackFrameProxyImpl): List<StackFrameProxyImpl>? {
            var frames = frame.threadProxy().frames()
            val indexOfCurrentFrame = frames.indexOf(frame)
            if (indexOfCurrentFrame >= 0) {
                val indexofGetCoroutineSuspended =
                    hasGetCoroutineSuspended(frames)
                // @TODO if found - skip this thread stack
                if (indexofGetCoroutineSuspended >= 0)
                    return null
                return frames.drop(indexOfCurrentFrame)
            } else
                return null
        }
    }
}