package com.jarvis.assistant.service

import android.util.Log

data class ExecutedAction(
    val actionId: String = java.util.UUID.randomUUID().toString(),
    val description: String,
    val actionType: ActionType,
    val packageName: String,
    val elementIdentifier: String,
    val previousState: String,
    val newState: String,
    val timestamp: Long = System.currentTimeMillis()
)

enum class ActionType {
    TOGGLE_SWITCH,
    CLICK_ELEMENT,
    TEXT_INPUT,
    SYSTEM_SETTING,
    APP_LAUNCH
}

class ActionHistoryManager private constructor() {

    companion object {
        private const val TAG = "JarvisActionHistory"

        @Volatile
        private var instance: ActionHistoryManager? = null

        fun getInstance(): ActionHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: ActionHistoryManager().also { instance = it }
            }
        }
    }

    private val historyStack = mutableListOf<ExecutedAction>()

    fun recordAction(
        description: String,
        actionType: ActionType,
        packageName: String,
        elementIdentifier: String,
        previousState: String,
        newState: String
    ) {
        val action = ExecutedAction(
            description = description,
            actionType = actionType,
            packageName = packageName,
            elementIdentifier = elementIdentifier,
            previousState = previousState,
            newState = newState
        )
        synchronized(historyStack) {
            historyStack.add(action)
            if (historyStack.size > 50) {
                historyStack.removeAt(0)
            }
        }
        Log.d(TAG, "Recorded action for undo: $description | Previous: $previousState -> New: $newState")
    }

    fun getLastAction(): ExecutedAction? {
        synchronized(historyStack) {
            return if (historyStack.isNotEmpty()) historyStack.last() else null
        }
    }

    fun popLastAction(): ExecutedAction? {
        synchronized(historyStack) {
            return if (historyStack.isNotEmpty()) historyStack.removeAt(historyStack.size - 1) else null
        }
    }

    fun getHistorySummary(): String {
        synchronized(historyStack) {
            if (historyStack.isEmpty()) return "No actions recorded yet."
            return historyStack.takeLast(5).joinToString("\n") { 
                "- [${it.actionType}] ${it.description}: ${it.previousState} -> ${it.newState}" 
            }
        }
    }
}
